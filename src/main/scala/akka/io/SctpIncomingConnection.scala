package akka.io

import com.sun.nio.sctp.{ SctpChannel }
import scala.collection.immutable
import akka.actor.ActorRef
import akka.io.SctpInet.SctpSocketOption

/**
 * An actor handling the connection state machine for an incoming, already connected
 * SocketChannel.
 *
 * INTERNAL API
 */
private[io] class SctpIncomingConnection(_sctp: SctpExt,
  _channel: SctpChannel,
  registry: ChannelRegistry,
  bindHandler: ActorRef,
  options: immutable.Traversable[SctpSocketOption])
    extends SctpConnection(_sctp, _channel) {

  context.watch(bindHandler) // sign death pact

  registry.register(channel, initialOps = 0)

  def receive = {
    case registration: ChannelRegistration ⇒ completeConnect(registration, bindHandler, options)
  }
}