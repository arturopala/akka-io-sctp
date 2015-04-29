package akka.io

import java.net.{ SocketException, InetSocketAddress }
import java.nio.channels.SelectionKey._
import java.io.{ FileInputStream, IOException }
import java.nio.channels.{ FileChannel }
import com.sun.nio.sctp._
import java.nio.ByteBuffer
import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal
import scala.concurrent.duration._
import akka.actor._
import akka.util.ByteString
import akka.io.SctpInet.SctpSocketOption
import akka.io.Sctp._
import akka.io.SelectionHandler._
import akka.dispatch.{ UnboundedMessageQueueSemantics, RequiresMessageQueue }
import akka.event.LoggingAdapter

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

  private[this] var pendingWrite: PendingWrite = EmptyPendingWrite
  private[this] var peerClosed = false
  private[this] var writingSuspended = false
  private[this] var interestedInResume: Option[ActorRef] = None
  var closedMessage: CloseInformation = _ // for ConnectionClosed message in postStop
  var isOutputShutdown = false
  val assocHandler = new AssociationHandler()

  def writePending = pendingWrite ne EmptyPendingWrite
  var association: Option[Association] = Option(channel.association)

  // STATES

  /** connection established, waiting for registration from user handler */
  def waitingForRegistration(registration: ChannelRegistration, commander: ActorRef): Receive = {
    case Register(handler, keepOpenOnPeerClosed, useResumeWriting) ⇒
      // up to this point we've been watching the commander,
      // but since registration is now complete we only need to watch the handler from here on
      if (handler != commander) {
        context.unwatch(commander)
        context.watch(handler)
      }
      if (TraceLogging) log.debug("[{}] registered as connection handler", handler)

      val info = ConnectionInfo(registration, handler, keepOpenOnPeerClosed, useResumeWriting)

      info.registration.disableInterest(OP_READ)
      doRead(info, None) // immediately try reading
      context.setReceiveTimeout(Duration.Undefined)
      context.become(connected(info))

    case cmd: CloseCommand ⇒
      val info = ConnectionInfo(registration, commander, keepOpenOnPeerClosed = false, useResumeWriting = false)
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
      case ChannelReadable   ⇒ doRead(info, None)
      case cmd: CloseCommand ⇒ handleClose(info, Some(sender()), cmd.event)
    }

  /** the peer sent EOF first, but we may still want to send */
  def peerSentEOF(info: ConnectionInfo): Receive =
    handleWriteMessages(info) orElse {
      case cmd: CloseCommand ⇒ handleClose(info, Some(sender()), cmd.event)
    }

  /** connection is closing but a write has to be finished first */
  def closingWithPendingWrite(info: ConnectionInfo, closeCommander: Option[ActorRef],
                              closedEvent: ConnectionClosed): Receive = {
    case ChannelReadable ⇒ doRead(info, closeCommander)

    case ChannelWritable ⇒
      doWrite(info)
      if (!writePending) // writing is now finished
        handleClose(info, closeCommander, closedEvent)

    case UpdatePendingWriteAndThen(remaining, work) ⇒
      pendingWrite = remaining
      work()
      if (writePending) info.registration.enableInterest(OP_WRITE)
      else handleClose(info, closeCommander, closedEvent)

    case WriteFileFailed(e) ⇒ handleError(info.handler, e) // rethrow exception from dispatcher task

    case Abort              ⇒ handleClose(info, Some(sender()), Aborted)
  }

  /** connection is closed on our side and we're waiting from confirmation from the other side */
  def closing(info: ConnectionInfo, closeCommander: Option[ActorRef]): Receive = {
    case ChannelReadable ⇒ doRead(info, closeCommander)
    case Abort           ⇒ handleClose(info, Some(sender()), Aborted)
  }

  def handleWriteMessages(info: ConnectionInfo): Receive = {
    case ChannelWritable ⇒
      if (writePending) {
        doWrite(info)
        if (!writePending && interestedInResume.nonEmpty) {
          interestedInResume.get ! WritingResumed
          interestedInResume = None
        }
      }

    case write: WriteCommand ⇒
      if (writingSuspended) {
        if (TraceLogging) log.debug("Dropping write because writing is suspended")
        sender() ! write.failureMessage

      } else if (writePending) {
        if (TraceLogging) log.debug("Dropping write because queue is full")
        sender() ! write.failureMessage
        if (info.useResumeWriting) writingSuspended = true

      } else {
        pendingWrite = PendingWrite(sender(), write)
        if (writePending) doWrite(info)
      }

    case ResumeWriting ⇒
      /*
       * If more than one actor sends Writes then the first to send this
       * message might resume too early for the second, leading to a Write of
       * the second to go through although it has not been resumed yet; there
       * is nothing we can do about this apart from all actors needing to
       * register themselves and us keeping track of them, which sounds bad.
       *
       * Thus it is documented that useResumeWriting is incompatible with
       * multiple writers. But we fail as gracefully as we can.
       */
      writingSuspended = false
      if (writePending) {
        if (interestedInResume.isEmpty) interestedInResume = Some(sender())
        else sender() ! CommandFailed(ResumeWriting)
      } else sender() ! WritingResumed

    case UpdatePendingWriteAndThen(remaining, work) ⇒
      pendingWrite = remaining
      work()
      if (writePending) info.registration.enableInterest(OP_WRITE)

    case WriteFileFailed(e) ⇒ handleError(info.handler, e) // rethrow exception from dispatcher task
  }

  // AUXILIARIES and IMPLEMENTATION

  /** used in subclasses to start the common machinery above once a channel is connected */
  def completeConnect(registration: ChannelRegistration, commander: ActorRef,
                      options: immutable.Traversable[SctpSocketOption]): Unit = {
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
      channel.association)

    context.setReceiveTimeout(RegisterTimeout)

    // !!WARNING!! The line below is needed to make Windows notify us about aborted connections, see #15766
    if (WindowsConnectionAbortWorkaroundEnabled) registration.enableInterest(OP_CONNECT)

    context.become(waitingForRegistration(registration, commander))
  }

  def doRead(info: ConnectionInfo, closeCommander: Option[ActorRef]): Unit = {
    @tailrec def innerRead(buffer: ByteBuffer, remainingLimit: Int): ReadResult =
      if (remainingLimit > 0) {
        val messageInfo: MessageInfo = channel.receive(buffer,log,assocHandler)
        if (messageInfo != null){
          if (messageInfo.bytes > 0 && messageInfo.isComplete()){
            println(messageInfo)
            buffer.flip()
            info.handler ! Received(SctpMessage(ByteString(buffer), SctpMessageInfo(messageInfo)))
            AllRead
          } else {
            innerRead(buffer, remainingLimit - buffer.position)
          }
        } else {
          association match {
            case Some(_) ⇒ AllRead
            case None ⇒  EndOfStream
          }
        }
      } else {
        BufferOverflow
      }

    val buffer = bufferPool.acquire()
    buffer.clear()
    val maxBufferSpace = math.min(DirectBufferSize, ReceivedMessageSizeLimit)
    buffer.limit(maxBufferSpace)
    try innerRead(buffer, maxBufferSpace) match {
      case AllRead ⇒
        info.registration.enableInterest(OP_READ)
      case BufferOverflow ⇒
        throw new IllegalStateException(s"Incoming message size too big: $maxBufferSpace")
      case EndOfStream if isOutputShutdown ⇒
        if (TraceLogging) log.debug("Read returned end-of-stream, our side already closed")
        doCloseConnection(info.handler, closeCommander, ConfirmedClosed)
      case EndOfStream ⇒
        if (TraceLogging) log.debug("Read returned end-of-stream, our side not yet closed")
        handleClose(info, closeCommander, PeerClosed)
    } catch {
      case e: IOException ⇒ handleError(info.handler, e)
    } finally bufferPool.release(buffer)
  }

  def doWrite(info: ConnectionInfo): Unit = pendingWrite = pendingWrite.doWrite(info)

  def closeReason =
    if (isOutputShutdown) ConfirmedClosed
    else PeerClosed

  def handleClose(info: ConnectionInfo, closeCommander: Option[ActorRef],
                  closedEvent: ConnectionClosed): Unit = closedEvent match {
    case Aborted ⇒
      if (TraceLogging) log.debug("Got Abort command. RESETing connection.")
      doCloseConnection(info.handler, closeCommander, closedEvent)
    case PeerClosed if info.keepOpenOnPeerClosed ⇒
      // report that peer closed the connection
      info.handler ! PeerClosed
      // used to check if peer already closed its side later
      peerClosed = true
      context.become(peerSentEOF(info))
    case _ if writePending ⇒ // finish writing first
      if (TraceLogging) log.debug("Got Close command but write is still pending.")
      context.become(closingWithPendingWrite(info, closeCommander, closedEvent))
    case ConfirmedClosed ⇒ // shutdown output and wait for confirmation
      if (TraceLogging) log.debug("Got ConfirmedClose command, sending FIN.")

      // If peer closed first, the socket is now fully closed.
      // Also, if shutdownOutput threw an exception we expect this to be an indication
      // that the peer closed first or concurrently with this code running.
      // also see http://bugs.sun.com/view_bug.do?bug_id=4516760
      if (peerClosed || !safeShutdownOutput())
        doCloseConnection(info.handler, closeCommander, closedEvent)
      else context.become(closing(info, closeCommander))
    case _ ⇒ // close now
      if (TraceLogging) log.debug("Got Close command, closing connection.")
      doCloseConnection(info.handler, closeCommander, closedEvent)
  }

  def doCloseConnection(handler: ActorRef, closeCommander: Option[ActorRef], closedEvent: ConnectionClosed): Unit = {
    if (closedEvent == Aborted) abort()
    else channel.close()
    stopWith(CloseInformation(Set(handler) ++ closeCommander, closedEvent))
  }

  def handleError(handler: ActorRef, exception: IOException): Unit = {
    log.debug("Closing connection due to IO error {}", exception)
    stopWith(CloseInformation(Set(handler), ErrorClosed(extractMsg(exception))))
  }
  def safeShutdownOutput(): Boolean =
    try {
      channel.shutdown()
      true
    } catch {
      case _: SocketException ⇒ false
    }

  @tailrec private[this] def extractMsg(t: Throwable): String =
    if (t == null) "unknown"
    else {
      t.getMessage match {
        case null | "" ⇒ extractMsg(t.getCause)
        case msg       ⇒ msg
      }
    }

  def abort(): Unit = {
    try {
      channel.setOption(SctpStandardSocketOptions.SO_LINGER, Int.box(0))
      isOutputShutdown = true
    } // causes the following close() to send TCP RST
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
    if (channel.isOpen)
      abort()

    if (writePending) pendingWrite.release()

    if (closedMessage != null) {
      val interestedInClose =
        if (writePending) closedMessage.notificationsTo + pendingWrite.commander
        else closedMessage.notificationsTo

      interestedInClose.foreach(_ ! closedMessage.closedEvent)
    }
  }

  override def postRestart(reason: Throwable): Unit =
    throw new IllegalStateException("Restarting not supported for connection actors.")

  def PendingWrite(commander: ActorRef, write: WriteCommand): PendingWrite = {
    @tailrec def create(head: WriteCommand, tail: WriteCommand = Write.empty): PendingWrite =
      head match {
        case Write.empty                         ⇒ if (tail eq Write.empty) EmptyPendingWrite else create(tail)
        case Write(data, ack) if data.nonEmpty   ⇒ PendingBufferWrite(commander, data, ack, tail)
        //case WriteFile(path, offset, count, ack) ⇒ PendingWriteFile(commander, path, offset, count, ack, tail)
        case CompoundWrite(h, t)                 ⇒ create(h, t)
        case x @ Write(_, ack) ⇒ // empty write with either an ACK or a non-standard NoACK
          if (x.wantsAck) commander ! ack
          create(tail)
      }
    create(write)
  }

  def PendingBufferWrite(commander: ActorRef, data: ByteString, ack: Event, tail: WriteCommand): PendingBufferWrite = {
    val buffer = bufferPool.acquire()
    try {
      val copied = data.copyToBuffer(buffer)
      buffer.flip()
      new PendingBufferWrite(commander, data.drop(copied), ack, buffer, tail)
    } catch {
      case NonFatal(e) ⇒
        bufferPool.release(buffer)
        throw e
    }
  }

  class PendingBufferWrite(
    val commander: ActorRef,
    remainingData: ByteString,
    ack: Any,
    buffer: ByteBuffer,
    tail: WriteCommand) extends PendingWrite {

    def doWrite(info: ConnectionInfo): PendingWrite = {
      /*@tailrec def writeToChannel(data: ByteString): PendingWrite = {
        val writtenBytes = channel.write(buffer) // at first we try to drain the remaining bytes from the buffer
        if (TraceLogging) log.debug("Wrote [{}] bytes to channel", writtenBytes)
        if (buffer.hasRemaining) {
          // we weren't able to write all bytes from the buffer, so we need to try again later
          if (data eq remainingData) this
          else new PendingBufferWrite(commander, data, ack, buffer, tail) // copy with updated remainingData

        } else if (data.nonEmpty) {
          buffer.clear()
          val copied = data.copyToBuffer(buffer)
          buffer.flip()
          writeToChannel(data drop copied)

        } else {
          if (!ack.isInstanceOf[NoAck]) commander ! ack
          release()
          PendingWrite(commander, tail)
        }
      }*/
      try {
        val next = /*writeToChannel(remainingData)*/EmptyPendingWrite
        if (next ne EmptyPendingWrite) info.registration.enableInterest(OP_WRITE)
        next
      } catch { case e: IOException ⇒ handleError(info.handler, e); this }
    }

    def release(): Unit = bufferPool.release(buffer)
  }

  /*def PendingWriteFile(commander: ActorRef, filePath: String, offset: Long, count: Long, ack: Event,
                       tail: WriteCommand): PendingWriteFile =
    new PendingWriteFile(commander, new FileInputStream(filePath).getChannel, offset, count, ack, tail)

  class PendingWriteFile(
    val commander: ActorRef,
    fileChannel: FileChannel,
    offset: Long,
    remaining: Long,
    ack: Event,
    tail: WriteCommand) extends PendingWrite with Runnable {

    def doWrite(info: ConnectionInfo): PendingWrite = {
      sctp.fileIoDispatcher.execute(this)
      this
    }

    def release(): Unit = fileChannel.close()

    def run(): Unit =
      try {
        val toWrite = math.min(remaining, sctp.Settings.TransferToLimit)
        val written = fileChannel.transferTo(offset, toWrite, channel)

        if (written < remaining) {
          val updated = new PendingWriteFile(commander, fileChannel, offset + written, remaining - written, ack, tail)
          self ! UpdatePendingWriteAndThen(updated, TcpConnection.doNothing)
        } else {
          release()
          val andThen = if (!ack.isInstanceOf[NoAck]) () ⇒ commander ! ack else doNothing
          self ! UpdatePendingWriteAndThen(PendingWrite(commander, tail), andThen)
        }
      } catch {
        case e: IOException ⇒ self ! WriteFileFailed(e)
      }
  }*/

  class AssociationHandler extends AbstractNotificationHandler[LoggingAdapter] {
    override def handleNotification(not: AssociationChangeNotification, log: LoggingAdapter):HandlerResult = {
      if (not.event().equals(AssociationChangeNotification.AssocChangeEvent.COMM_UP)) {
        val outbound = not.association().maxOutboundStreams()
        val inbound = not.association().maxInboundStreams()
        log.debug("New association setup with {} outbound streams, and {} inbound streams.", outbound, inbound)
      }
      HandlerResult.CONTINUE
    }

    override def handleNotification(not: ShutdownNotification, log: LoggingAdapter):HandlerResult = {
      log.debug("The association has been shutdown {}", not)
      association = None
      HandlerResult.RETURN
    }

    override def handleNotification(not: PeerAddressChangeNotification, log: LoggingAdapter):HandlerResult = {
      log.debug("The peer address has changed {}", not)
      HandlerResult.CONTINUE
    }

    override def handleNotification(not: SendFailedNotification, log: LoggingAdapter):HandlerResult = {
      log.debug("Send failed {}", not)
      HandlerResult.CONTINUE
    }
  }

}

/**
 * INTERNAL API
 */
private[io] object SctpConnection {
  sealed trait ReadResult
  object EndOfStream extends ReadResult
  object AllRead extends ReadResult
  object BufferOverflow extends ReadResult

  /**
   * Used to transport information to the postStop method to notify
   * interested party about a connection close.
   */
  final case class CloseInformation(notificationsTo: Set[ActorRef], closedEvent: Event)

  /**
   * Groups required connection-related data that are only available once the connection has been fully established.
   */
  final case class ConnectionInfo(registration: ChannelRegistration,
                                  handler: ActorRef,
                                  keepOpenOnPeerClosed: Boolean,
                                  useResumeWriting: Boolean)

  // INTERNAL MESSAGES

  final case class UpdatePendingWriteAndThen(remainingWrite: PendingWrite, work: () ⇒ Unit) extends NoSerializationVerificationNeeded
  final case class WriteFileFailed(e: IOException)

  sealed abstract class PendingWrite {
    def commander: ActorRef
    def doWrite(info: ConnectionInfo): PendingWrite
    def release(): Unit // free any occupied resources
  }

  object EmptyPendingWrite extends PendingWrite {
    def commander: ActorRef = throw new IllegalStateException
    def doWrite(info: ConnectionInfo): PendingWrite = throw new IllegalStateException
    def release(): Unit = throw new IllegalStateException
  }

  val doNothing: () ⇒ Unit = () ⇒ ()
}
