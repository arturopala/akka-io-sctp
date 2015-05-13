package akka.io

import java.net.{ SocketException, InetSocketAddress }
import java.nio.channels.SelectionKey._
import java.io.{ FileInputStream, IOException }
import java.nio.channels.{ FileChannel }
import com.sun.nio.sctp.{ SctpChannel, HandlerResult, MessageInfo, SctpStandardSocketOptions, AbstractNotificationHandler }
import java.nio.ByteBuffer
import scala.annotation.tailrec
import scala.collection.immutable.{ Queue, Traversable }
import scala.util.control.NonFatal
import scala.concurrent.duration._
import akka.actor._
import akka.util.{ ByteString, ByteStringBuilder }
import akka.io.SctpInet.SctpSocketOption
import akka.io.Sctp._
import akka.io.SelectionHandler._
import akka.dispatch.{ UnboundedMessageQueueSemantics, RequiresMessageQueue }
import akka.event.LoggingAdapter
import scala.collection.mutable.{ Map => MutableMap }

/**
 * Base class for SctpIncomingConnection and SctpOutgoingConnection.
 *
 * INTERNAL API
 */
private[io] abstract class SctpConnection(val sctp: SctpExt, val channel: SctpChannel)
    extends Actor with ActorLogging with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  import sctp.Settings._
  import sctp.bufferPool
  import SctpConnection._
  import scala.collection.JavaConversions._

  val readByteStringMap: MutableMap[Int, Option[ByteString]] = MutableMap.empty.withDefaultValue(None)
  def isPendingSend = !pendingSendQueue.isEmpty

  private[this] var pendingSendQueue: Queue[(Send, ActorRef)] = Queue.empty
  private[this] var closedMessage: CloseInformation = _ // for ConnectionClosed message in postStop
  private[this] var isLocalShutdown = false
  private[this] var isAssociationClosed = false

  // STATES

  /** connection established, waiting for registration from user handler */
  def waitingForRegistration(registration: ChannelRegistration, commander: ActorRef): Receive = {
    case Register(handler, notificationHandlerOpt) ⇒
      // up to this point we've been watching the commander,
      // but since registration is now complete we only need to watch the handler from here on
      if (handler != commander) {
        context.unwatch(commander)
        context.watch(handler)
      }
      if (TraceLogging) log.debug("[{}] registered as connection handler", handler)
      val info = ConnectionInfo(registration, handler, new NotificationHandler(notificationHandlerOpt))
      info.registration.disableInterest(OP_READ)
      context.setReceiveTimeout(Duration.Undefined)
      context.become(connected(info))
      doRead(info, None) // immediately try reading

    case cmd: CloseCommand ⇒
      val info = ConnectionInfo(registration, commander, new NotificationHandler(None))
      handleClose(info, Some(sender()), cmd.event)

    case ReceiveTimeout ⇒
      // after sending `Register` user should watch this actor to make sure
      // it didn't die because of the timeout
      log.debug("Configured registration timeout of [{}] expired, stopping", RegisterTimeout)
      context.stop(self)
  }

  /** normal connected state */
  def connected(info: ConnectionInfo): Receive =
    handleWriteMessages(info) orElse {
      case ChannelReadable ⇒ doRead(info, None)
      case cmd: CloseCommand ⇒ handleClose(info, Some(sender()), cmd.event)
    }

  /** connection is closing but a write has to be finished first */
  def closingWithPendingWrite(info: ConnectionInfo, closeCommander: Option[ActorRef],
    closedEvent: ConnectionClosed): Receive = {
    case ChannelReadable ⇒ doRead(info, closeCommander)
    case ChannelWritable ⇒
      while (isPendingSend) doWrite(info)
      handleClose(info, closeCommander, closedEvent)
    case Abort ⇒ handleClose(info, Some(sender()), Aborted)
  }

  /** connection is closed on our side and we're waiting from confirmation from the other side */
  def closing(info: ConnectionInfo, closeCommander: Option[ActorRef]): Receive = {
    case ChannelReadable ⇒ doRead(info, closeCommander)
    case Abort ⇒ handleClose(info, Some(sender()), Aborted)
  }

  def handleWriteMessages(info: ConnectionInfo): Receive = {
    case ChannelWritable ⇒ doWrite(info)
    case send: Send ⇒
      pendingSendQueue = pendingSendQueue.enqueue((send, sender()))
      info.registration.enableInterest(OP_WRITE)
  }

  // AUXILIARIES and IMPLEMENTATION

  /** used in subclasses to start the common machinery above once a channel is connected */
  def completeConnect(registration: ChannelRegistration, commander: ActorRef,
    options: Traversable[SctpSocketOption]): Unit = {
    // Turn off Nagle's algorithm by default
    try channel.setOption(SctpStandardSocketOptions.SCTP_NODELAY, Boolean.box(true)) catch {
      case e: SocketException ⇒
        // as reported in #16653 some versions of netcat (`nc -z`) doesn't allow setTcpNoDelay
        // continue anyway
        log.debug("Could not enable TcpNoDelay: {}", e.getMessage)
    }
    options.foreach(_.afterConnect(channel))

    commander ! Connected(
      channel.getRemoteAddresses.map(_.asInstanceOf[InetSocketAddress]).toSet,
      channel.getAllLocalAddresses.map(_.asInstanceOf[InetSocketAddress]).toSet,
      SctpAssociation(channel.association))

    context.setReceiveTimeout(RegisterTimeout)

    // !!WARNING!! The line below is needed to make Windows notify us about aborted connections, see #15766
    if (WindowsConnectionAbortWorkaroundEnabled) registration.enableInterest(OP_CONNECT)

    context.become(waitingForRegistration(registration, commander))
  }

  def doRead(info: ConnectionInfo, closeCommander: Option[ActorRef]): Unit = {
    @tailrec def innerRead(buffer: ByteBuffer): ReadResult = {
      buffer.clear()
      val messageInfo: MessageInfo = channel.receive(buffer, log, info.notificationHandler)
      if (messageInfo != null) {
        val bytesRead = messageInfo.bytes
        if (bytesRead < 0) {
          EndOfStream
        } else {
          buffer.flip()
          val streamNumber = messageInfo.streamNumber
          if (TraceLogging) log.debug(s"Read [$bytesRead] bytes from stream #$streamNumber")
          val byteString: ByteString = readByteStringMap(streamNumber) match {
            case Some(pbs) => pbs ++ ByteString(buffer)
            case None => ByteString(buffer)
          }
          if (messageInfo.isComplete()) {
            info.handler ! Received(SctpMessage(SctpMessageInfo(messageInfo, byteString.length), byteString))
            readByteStringMap(streamNumber) = None
            if (TraceLogging) log.debug(s"Read message from stream #$streamNumber, ${byteString.length} bytes")
            if (isAssociationClosed) EndOfStream else if (AllowChainingReads) innerRead(buffer) else NothingToRead
          } else {
            readByteStringMap(streamNumber) = Some(byteString)
            innerRead(buffer)
          }
        }
      } else if (isAssociationClosed) EndOfStream else NothingToRead
    }
    val buffer = bufferPool.acquire()
    try innerRead(buffer) match {
      case NothingToRead ⇒
        info.registration.enableInterest(OP_READ)
      case EndOfStream if isLocalShutdown ⇒
        if (TraceLogging) log.debug("Read returned end-of-stream, our side already closed")
        doCloseConnection(info.handler, closeCommander, ConfirmedClosed)
      case EndOfStream ⇒
        if (TraceLogging) log.debug("Read returned end-of-stream, our side not yet closed")
        handleClose(info, closeCommander, PeerClosed)
    } catch {
      case e: Throwable ⇒ handleError(info.handler, e)
    } finally bufferPool.release(buffer)
  }

  def doWrite(info: ConnectionInfo): Unit = {
    @tailrec def innerWrite(buffer: ByteBuffer): WriteResult = {
      buffer.clear()
      val ((Send(message, ack), commander), tail) = pendingSendQueue.dequeue
      val length = message.payload.length
      val buf = if (length > buffer.capacity) ByteBuffer.allocateDirect(length) else buffer
      val copied = message.payload.copyToBuffer(buf)
      buf.flip()
      val mi = message.info.asMessageInfo
      val writtenBytes = channel.send(buf, mi)
      if (writtenBytes > 0) {
        if (TraceLogging) log.debug(s"Wrote $writtenBytes bytes to channel stream #${message.info.streamNumber}")
        if (!ack.isInstanceOf[NoAck]) commander ! ack
      }
      pendingSendQueue = tail
      if (AllowChainingWrites && isPendingSend) innerWrite(buffer) else NothingToWrite
    }
    if (isPendingSend) {
      val buffer = bufferPool.acquire()
      try innerWrite(buffer) match {
        case NothingToWrite =>
          info.registration.enableInterest(OP_WRITE)
      } catch {
        case e: Throwable ⇒ handleError(info.handler, e)
      } finally bufferPool.release(buffer)
    }
  }

  def closeReason = if (isLocalShutdown) ConfirmedClosed else PeerClosed

  def handleClose(info: ConnectionInfo, closeCommander: Option[ActorRef],
    closedEvent: ConnectionClosed): Unit = closedEvent match {
    case Aborted ⇒
      if (TraceLogging) log.debug("Got Abort command. RESETing connection.")
      doCloseConnection(info.handler, closeCommander, closedEvent)
    case _ if isPendingSend ⇒ // finish writing first
      if (TraceLogging) log.debug("Got Close command but write is still pending.")
      context.become(closingWithPendingWrite(info, closeCommander, closedEvent))
    case ConfirmedClosed ⇒ // shutdown output and wait for confirmation
      if (TraceLogging) log.debug("Got ConfirmedClose command, sending FIN.")
      if (isAssociationClosed || !safeShutdown()) doCloseConnection(info.handler, closeCommander, closedEvent)
      else context.become(closing(info, closeCommander))
    case _ ⇒ // close now
      if (TraceLogging) log.debug("Got Close command, closing connection.")
      doCloseConnection(info.handler, closeCommander, closedEvent)
  }

  def doCloseConnection(handler: ActorRef, closeCommander: Option[ActorRef], closedEvent: ConnectionClosed): Unit = {
    if (closedEvent == Aborted) abort() else channel.close()
    stopWith(CloseInformation(Set(handler) ++ closeCommander, closedEvent))
  }

  def handleError(handler: ActorRef, exception: Throwable): Unit = {
    log.debug("Closing connection due to IO error {}", exception)
    stopWith(CloseInformation(Set(handler), ErrorClosed(extractMsg(exception))))
  }
  def safeShutdown(): Boolean =
    try {
      channel.shutdown()
      true
    } catch {
      case _: IOException ⇒ false
    }

  @tailrec private[this] def extractMsg(t: Throwable): String =
    if (t == null) "unknown"
    else {
      t.getMessage match {
        case null | "" ⇒ extractMsg(t.getCause)
        case msg ⇒ msg
      }
    }

  def abort(): Unit = {
    try {
      channel.setOption(SctpStandardSocketOptions.SO_LINGER, Int.box(0))
      isLocalShutdown = true
    } // causes the following close() to send SCTP RST
    catch {
      case NonFatal(e) ⇒
        // setSoLinger can fail due to http://bugs.sun.com/view_bug.do?bug_id=6799574
        // (also affected: OS/X Java 1.6.0_37)
        if (TraceLogging) log.debug("setOption(SO_LINGER, 0) failed with [{}]", e)
    }
    channel.close()
  }

  def stopWith(closeInfo: CloseInformation): Unit = {
    closedMessage = closeInfo
    context.stop(self)
  }

  override def postStop(): Unit = {
    if (channel.isOpen) abort()
    if (closedMessage != null) {
      val interestedInClose =
        if (isPendingSend) closedMessage.notificationsTo ++ (pendingSendQueue.map(_._2).toSet)
        else closedMessage.notificationsTo
      interestedInClose.foreach(_ ! closedMessage.closedEvent)
    }
    pendingSendQueue = Queue.empty
    readByteStringMap.clear()
  }

  override def postRestart(reason: Throwable): Unit =
    throw new IllegalStateException("Restarting not supported for connection actors.")

  private[this] final class NotificationHandler(handler: Option[ActorRef]) extends AbstractNotificationHandler[LoggingAdapter] {
    override def handleNotification(not: com.sun.nio.sctp.AssociationChangeNotification, log: LoggingAdapter): HandlerResult = {
      if (TraceLogging) log.debug(s"Association event ${not.event} ${not.association}")
      handler.map(_ ! AssociationNotification(not.event))
      HandlerResult.CONTINUE
    }

    override def handleNotification(not: com.sun.nio.sctp.PeerAddressChangeNotification, log: LoggingAdapter): HandlerResult = {
      if (TraceLogging) log.debug(s"Peer address event ${not.event} ${not.address}")
      handler.map(_ ! PeerAddressNotification(not.event))
      HandlerResult.CONTINUE
    }

    override def handleNotification(not: com.sun.nio.sctp.SendFailedNotification, log: LoggingAdapter): HandlerResult = {
      if (TraceLogging) log.debug(s"Send failed $not")
      handler.map(_ ! SendFailedNotification(ByteString(not.buffer), not.streamNumber, not.errorCode, not.address.asInstanceOf[InetSocketAddress]))
      HandlerResult.CONTINUE
    }

    override def handleNotification(not: com.sun.nio.sctp.Notification, log: LoggingAdapter): HandlerResult = {
      if (TraceLogging) log.debug(s"Custom event ${not}")
      HandlerResult.CONTINUE
    }

    override def handleNotification(not: com.sun.nio.sctp.ShutdownNotification, log: LoggingAdapter): HandlerResult = {
      if (TraceLogging) log.debug(s"The association has been shutdown ${not.association}")
      isAssociationClosed = true
      handler.map(_ ! AssociationNotification.SHUTDOWN)
      HandlerResult.RETURN
    }
  }

}

/**
 * INTERNAL API
 */
private[io] object SctpConnection {
  sealed trait ReadResult
  object EndOfStream extends ReadResult
  object NothingToRead extends ReadResult
  sealed trait WriteResult
  object NothingToWrite extends WriteResult

  /**
   * Used to transport information to the postStop method to notify
   * interested party about a connection close.
   */
  final case class CloseInformation(notificationsTo: Set[ActorRef], closedEvent: Event)

  /**
   * Groups required connection-related data that are only available once the connection has been fully established.
   */
  final case class ConnectionInfo(registration: ChannelRegistration, handler: ActorRef, notificationHandler: com.sun.nio.sctp.NotificationHandler[LoggingAdapter])

  val doNothing: () ⇒ Unit = () ⇒ ()
}
