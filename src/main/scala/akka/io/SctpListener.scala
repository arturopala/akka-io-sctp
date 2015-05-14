package akka.io

import java.nio.channels.{ SelectionKey }
import com.sun.nio.sctp.{ SctpChannel, SctpServerChannel, SctpMultiChannel, SctpStandardSocketOptions }
import java.net.InetSocketAddress
import scala.annotation.tailrec
import scala.util.control.NonFatal
import akka.actor._
import akka.io.SelectionHandler._
import akka.io.Sctp._
import akka.dispatch.{ UnboundedMessageQueueSemantics, RequiresMessageQueue }

/**
 * INTERNAL API
 */
private[io] object SctpListener {

  final case class RegisterIncoming(sctpServerChannel: SctpChannel) extends HasFailureMessage with NoSerializationVerificationNeeded {
    def failureMessage = FailedRegisterIncoming(sctpServerChannel)
  }

  final case class FailedRegisterIncoming(sctpServerChannel: SctpChannel) extends NoSerializationVerificationNeeded

}

/**
 * INTERNAL API
 */
private[io] final class SctpListener(selectorRouter: ActorRef,
  sctp: SctpExt,
  channelRegistry: ChannelRegistry,
  bindCommander: ActorRef,
  bind: Bind)
    extends Actor with ActorLogging with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  import SctpListener._
  import sctp.Settings._
  import scala.collection.JavaConversions._

  context.watch(bind.handler) // sign death pact

  val sctpServerChannel: SctpServerChannel = SctpServerChannel.open
  sctpServerChannel.configureBlocking(false)
  sctpServerChannel.setOption(SctpStandardSocketOptions.SCTP_INIT_MAXSTREAMS,
    SctpStandardSocketOptions.InitMaxStreams.create(
      bind.maxInboundStreams,
      bind.maxOutboundStreams))

  var acceptLimit = BatchAcceptLimit

  val localAddresses: Set[InetSocketAddress] =
    try {
      bind.options.foreach(_.beforeBind(sctpServerChannel))
      sctpServerChannel.bind(bind.localAddress, bind.backlog)
      bind.additionalAddresses.foreach(sctpServerChannel.bindAddress)
      val ret = sctpServerChannel.getAllLocalAddresses() map {
        case isa: InetSocketAddress ⇒ isa
        case x ⇒ throw new IllegalArgumentException(s"bound to unknown SocketAddress [$x]")
      }
      channelRegistry.register(sctpServerChannel, SelectionKey.OP_ACCEPT)
      log.debug("Successfully bound to {}", ret)
      bind.options.foreach(_.afterConnect(sctpServerChannel))
      ret.toSet
    } catch {
      case NonFatal(e) ⇒
        bindCommander ! bind.failureMessage
        log.warning("Bind failed for SCTP sctp server channel on endpoint [{},{}]", bind.localAddress, bind.additionalAddresses)
        context.stop(self)
        Set.empty
    }

  override def supervisorStrategy = SelectionHandler.connectionSupervisorStrategy

  def receive: Receive = {
    case registration: ChannelRegistration ⇒
      bindCommander ! Bound(localAddresses, localAddresses.head.getPort)
      context.become(bound(registration))
  }

  def bound(registration: ChannelRegistration): Receive = {
    case ChannelAcceptable ⇒
      acceptLimit = acceptAllPending(registration, acceptLimit)
      if (acceptLimit > 0) registration.enableInterest(SelectionKey.OP_ACCEPT)

    case FailedRegisterIncoming(sctpChannel) ⇒
      log.warning("Could not register incoming connection since selector capacity limit is reached, closing connection")
      try sctpChannel.close()
      catch {
        case NonFatal(e) ⇒ log.debug("Error closing sctp channel: {}", e)
      }

    case cmd @ BindAddress(address) =>
      try sctpServerChannel.bindAddress(address)
      catch {
        case NonFatal(e) => sender() ! CommandFailed(cmd)
      }

    case cmd @ UnbindAddress(address) =>
      try sctpServerChannel.unbindAddress(address)
      catch {
        case NonFatal(e) => sender() ! CommandFailed(cmd)
      }

    case Unbind ⇒
      log.debug("Unbinding endpoint {}", localAddresses)
      sctpServerChannel.close()
      sender() ! Unbound
      log.debug("Unbound endpoint {}, stopping listener", localAddresses)
      context.stop(self)
  }

  @tailrec final def acceptAllPending(registration: ChannelRegistration, limit: Int): Int = {
    val sctpChannel: SctpChannel =
      if (limit > 0) {
        try sctpServerChannel.accept()
        catch {
          case NonFatal(e) ⇒ { log.error(e, "Accept error: could not accept new connection"); null }
        }
      } else null
    if (sctpChannel != null) {
      log.debug("New connection accepted")
      sctpChannel.configureBlocking(false)
      def props(registry: ChannelRegistry) =
        Props(classOf[SctpIncomingConnection], sctp, sctpChannel, registry, bind.handler, bind.options)
      selectorRouter ! WorkerForCommand(RegisterIncoming(sctpChannel), self, props)
      acceptAllPending(registration, limit - 1)
    } else BatchAcceptLimit
  }

  override def postStop() {
    try {
      if (sctpServerChannel.isOpen) {
        log.debug("Closing serverSctpChannel after being stopped")
        sctpServerChannel.close()
      }
    } catch {
      case NonFatal(e) ⇒ log.debug("Error closing SctpServerChannel: {}", e)
    }
  }
}
