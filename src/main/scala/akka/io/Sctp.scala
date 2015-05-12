package akka.io

import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Socket
import akka.ConfigurationException
import java.nio.channels.SocketChannel
import akka.io.SctpInet._
import com.typesafe.config.Config
import scala.concurrent.duration._
import scala.collection.immutable
import scala.collection.JavaConverters._
import akka.util.{ Helpers, ByteString }
import akka.util.Helpers.Requiring
import akka.actor._
import java.lang.{ Iterable ⇒ JIterable }
import akka.actor._
import com.sun.nio.sctp.{ SctpChannel, SctpStandardSocketOptions, Association, MessageInfo }

object Sctp extends ExtensionId[SctpExt] with ExtensionIdProvider {

  override def lookup = Sctp

  override def createExtension(system: ExtendedActorSystem): SctpExt = new SctpExt(system)

  /**
   * Java API: retrieve the Sctp extension for the given system.
   */
  override def get(system: ActorSystem): SctpExt = super.get(system)

  object SO extends SctpInet.SoForwarders {

    // general socket options

    /**
     * [[akka.io.SctpInet.SctpSocketOption]] to enable or disable SCTP_NODELAY
     * (disable or enable Nagle's algorithm)
     *
     * Please note, that SCTP_NODELAY is enabled by default.
     *
     * For more information see [[com.sun.nio.sctp.SctpStandardSocketOptions.SCTP_NODELAY]]
     */
    final case class SctpNoDelay(on: Boolean) extends SctpSocketOption {
      override def afterConnect(c: SctpChannel): Unit = c.setOption(SctpStandardSocketOptions.SCTP_NODELAY, Boolean.box(on))
    }

    /**
     *  SCTP_DISABLE_FRAGMENTS
     *  Enables or disables message fragmentation.
     *  The value of this socket option is a Boolean that represents whether the option is enabled or disabled.
     *  If enabled no SCTP message fragmentation will be performed. Instead if a message being sent exceeds the current PMTU size,
     *  the message will NOT be sent and an error will be indicated to the user.
     *  It is implementation specific whether or not this option is supported.
     */

    final case class SctpDisableFragments(on: Boolean) extends SctpSocketOption {
      override def afterConnect(c: SctpChannel): Unit = c.setOption(SctpStandardSocketOptions.SCTP_DISABLE_FRAGMENTS, Boolean.box(on))
    }

    /**
     *  SCTP_EXPLICIT_COMPLETE
     *  Enables or disables explicit message completion.
     *  The value of this socket option is a Boolean that represents whether the option is enabled or disabled.
     *  When this option is enabled, the send method may be invoked multiple times to a send message.
     *  The isComplete parameter of the MessageInfo must only be set to true for the final send to indicate that the message is complete.
     *  If this option is disabled then each individual send invocation is considered complete.
     *  The default value of the option is false indicating that the option is disabled.
     *  It is implementation specific whether or not this option is supported.
     */

    final case class SctpExplicitComplete(on: Boolean) extends SctpSocketOption {
      override def afterConnect(c: SctpChannel): Unit = c.setOption(SctpStandardSocketOptions.SCTP_EXPLICIT_COMPLETE, Boolean.box(on))
    }

    /**
     *  SCTP_FRAGMENT_INTERLEAVE
     *  Fragmented interleave controls how the presentation of messages occur for the message receiver.
     *  This option takes an Integer value. It can be set to a value of 0, 1 or 2.
     *
     *  Setting the three levels provides the following receiver interactions:
     *  <ul>
     *  <li>level 0 - Prevents the interleaving of any messages.
     *  <li>level 1 - Allows interleaving of messages that are from different associations.
     *  <li>level 2 - Allows complete interleaving of messages.
     *  </ul>
     *  It is implementation specific whether or not this option is supported.
     */

    final case class SctpFragmentInterleave(level: Int) extends SctpSocketOption {
      override def afterConnect(c: SctpChannel): Unit = c.setOption(SctpStandardSocketOptions.SCTP_FRAGMENT_INTERLEAVE, Int.box(level))
    }

    /**
     *  SCTP_PRIMARY_ADDR
     *  Requests that the local SCTP stack use the given peer address as the association primary.
     *  The value of this socket option is a SocketAddress that represents the peer address that the local SCTP stack should use as the association primary.
     *  The address must be one of the association peer's addresses.
     */
    final case class SctpPeerPrimaryAddress(address: InetSocketAddress) extends SctpSocketOption {
      override def afterConnect(c: SctpChannel): Unit = c.setOption(SctpStandardSocketOptions.SCTP_PRIMARY_ADDR, address)
    }

    /**
     *  SCTP_SET_PEER_PRIMARY_ADDR
     *  Requests that the peer mark the enclosed address as the association primary.
     *  The value of this socket option is a SocketAddress that represents the local address that the peer should use as its primary address.
     *  The given address must be one of the association's locally bound addresses.
     *  It is implementation specific whether or not this option is supported.
     */
    final case class SctpLocalPrimaryAddress(address: InetSocketAddress) extends SctpSocketOption {
      override def afterConnect(c: SctpChannel): Unit = c.setOption(SctpStandardSocketOptions.SCTP_SET_PEER_PRIMARY_ADDR, address)
    }

    /**
     *  SO_SNDBUF
     *  The size of the socket send buffer.
     *  The value of this socket option is an Integer that is the size of the socket send buffer in bytes.
     *  The socket send buffer is an output buffer used by the networking implementation.
     *  It may need to be increased for high-volume connections.
     *  The value of the socket option is a hint to the implementation to size the buffer and the actual size may differ.
     *
     *  An implementation allows this socket option to be set before the socket is bound or connected.
     *  Whether an implementation allows the socket send buffer to be changed after the socket is bound is system dependent.
     */
    final case class SctpSendBufferSize(size: Int) extends SctpSocketOption {
      override def afterConnect(c: SctpChannel): Unit = c.setOption(SctpStandardSocketOptions.SO_SNDBUF, Int.box(size))
    }

    /**
     *  SO_RCVBUF
     *  The size of the socket receive buffer.
     *  The value of this socket option is an Integer that is the size of the socket receive buffer in bytes.
     *  The socket receive buffer is an input buffer used by the networking implementation.
     *  It may need to be increased for high-volume connections or decreased to limit the possible backlog of incoming data.
     *  The value of the socket option is a hint to the implementation to size the buffer and the actual size may differ.
     *  An implementation allows this socket option to be set before the socket is bound or connected.
     *  Whether an implementation allows the socket receive buffer to be changed after the socket is bound is system dependent.
     */
    final case class SctpReceiveBufferSize(size: Int) extends SctpSocketOption {
      override def afterConnect(c: SctpChannel): Unit = c.setOption(SctpStandardSocketOptions.SO_RCVBUF, Int.box(size))
    }

  }

  /**
   * The common interface for [[Command]] and [[Event]].
   */
  sealed trait Message extends NoSerializationVerificationNeeded

  /// SCTP MESSAGE

  final case class SctpAssociation(id: Int, maxInboundStreams: Int, maxOutboundStreams: Int)
  object SctpAssociation {
    def apply(association: Association): SctpAssociation = Option(association) map (a =>
      new SctpAssociation(a.associationID(), a.maxInboundStreams(), a.maxOutboundStreams())
    ) getOrElse null
  }

  final case class SctpMessage(info: SctpMessageInfo, payload: ByteString) {
    override def toString: String = if (payload.length <= 256) super.toString else s"SctpMessage($info,${payload.take(256)} ...)"
  }
  object SctpMessage {
    def apply(payload: ByteString, streamNumber: Int, payloadProtocolID: Int = 0, timeToLive: Long = 0): SctpMessage = new SctpMessage(SctpMessageInfo(streamNumber, payloadProtocolID, timeToLive), payload)
  }

  final case class SctpMessageInfo(streamNumber: Int, bytes: Int, payloadProtocolID: Int, timeToLive: Long, association: SctpAssociation, address: InetSocketAddress) {
    def asMessageInfo: MessageInfo = {
      val mi = MessageInfo.createOutgoing(null, streamNumber)
      mi.payloadProtocolID(payloadProtocolID)
      mi.timeToLive(timeToLive)
      mi
    }
  }
  object SctpMessageInfo {
    def apply(messageInfo: MessageInfo, length: Int): SctpMessageInfo = new SctpMessageInfo(messageInfo.streamNumber(), length, messageInfo.payloadProtocolID(), messageInfo.timeToLive(), SctpAssociation(messageInfo.association()), messageInfo.address().asInstanceOf[InetSocketAddress])
    def apply(streamNumber: Int, payloadProtocolID: Int = 0, timeToLive: Long = 0): SctpMessageInfo = {
      val mi = MessageInfo.createOutgoing(null, streamNumber)
      mi.payloadProtocolID(payloadProtocolID)
      mi.timeToLive(timeToLive)
      SctpMessageInfo(mi, -1)
    }
  }

  /// COMMANDS

  /**
   * This is the common trait for all commands understood by SCTP actors.
   */
  trait Command extends Message with SelectionHandler.HasFailureMessage {
    def failureMessage = CommandFailed(this)
  }

  /**
   * The Connect message is sent to the SCTP manager actor, which is obtained via
   * [[StcpExt#manager]]. Either the manager replies with a [[CommandFailed]]
   * or the actor handling the new connection replies with a [[Connected]]
   * message.
   *
   * @param remoteAddress is the address to connect to
   * @param localAddress optionally specifies a specific address to bind to
   * @param options Please refer to the [[SO]] object for a list of all supported options.
   */
  final case class Connect(
      remoteAddress: InetSocketAddress,
      maxOutboundStreams: Int = 0,
      maxInboundStreams: Int = 0,
      localAddress: Option[InetSocketAddress] = None,
      options: immutable.Traversable[SctpSocketOption] = Nil,
      timeout: Option[FiniteDuration] = None) extends Command {
    require(maxInboundStreams >= 0, "maxInboundStreams must be greater or equal to 0")
    require(maxOutboundStreams >= 0, "maxOutboundStreams must be greater or equal to 0")
    require(maxInboundStreams <= 65536, "maxInboundStreams must be lower or equal to 65536")
    require(maxOutboundStreams <= 65536, "maxOutboundStreams must be lower or equal to 65536")
  }

  /**
   * The Bind message is send to the SCTP manager actor, which is obtained via
   * [[StcpExt#manager]] in order to bind to a listening socket. The manager
   * replies either with a [[CommandFailed]] or the actor handling the listen
   * socket replies with a [[Bound]] message. If the local port is set to 0 in
   * the Bind message, then the [[Bound]] message should be inspected to find
   * the actual port which was bound to.
   *
   * @param handler The actor which will receive all incoming connection requests
   *                in the form of [[Connected]] messages.
   *
   * @param localAddress The socket address to bind to; use port zero for
   *                automatic assignment (i.e. an ephemeral port, see [[Bound]])
   *
   * @param backlog This specifies the number of unaccepted connections the O/S
   *                kernel will hold for this port before refusing connections.
   *
   * @param options Please refer to the [[SO]] object for a list of all supported options.
   */
  final case class Bind(
      handler: ActorRef,
      localAddress: InetSocketAddress,
      maxInboundStreams: Int = 0,
      maxOutboundStreams: Int = 0,
      additionalAddresses: Set[InetAddress] = Set.empty,
      backlog: Int = 100,
      options: immutable.Traversable[SctpSocketOption] = Nil) extends Command {
    require(maxInboundStreams >= 0, "maxInboundStreams must be greater or equal to 0")
    require(maxOutboundStreams >= 0, "maxOutboundStreams must be greater or equal to 0")
    require(maxInboundStreams <= 65536, "maxInboundStreams must be lower or equal to 65536")
    require(maxOutboundStreams <= 65536, "maxOutboundStreams must be lower or equal to 65536")
  }

  /**
   * This message must be sent to a SCTP connection actor after receiving the
   * [[Connected]] message. The connection will not read any data from the
   * socket until this message is received, because this message defines the
   * actor which will receive all inbound data.
   *
   * @param handler The actor which will receive all incoming data and which
   *                will be informed when the connection is closed.
   *
   * @param keepOpenOnPeerClosed If this is set to true then the connection
   *                is not automatically closed when the peer closes its half,
   *                requiring an explicit [[Closed]] from our side when finished.
   */
  final case class Register(handler: ActorRef, keepOpenOnPeerClosed: Boolean = false) extends Command

  /**
   * In order to close down a listening socket, send this message to that socket’s
   * actor (that is the actor which previously had sent the [[Bound]] message). The
   * listener socket actor will reply with a [[Unbound]] message.
   */
  case object Unbind extends Command

  /**
   * Common interface for all commands which aim to close down an open connection.
   */
  sealed trait CloseCommand extends Command {
    /**
     * The corresponding event which is sent as an acknowledgment once the
     * close operation is finished.
     */
    def event: ConnectionClosed
  }

  /**
   * A normal close operation will first flush pending writes and then close the
   * socket. The sender of this command and the registered handler for incoming
   * data will both be notified once the socket is closed using a [[Closed]]
   * message.
   */
  case object Close extends CloseCommand {
    /**
     * The corresponding event which is sent as an acknowledgment once the
     * close operation is finished.
     */
    override def event = Closed
  }

  /**
   * A confirmed close operation will flush pending writes and half-close the
   * connection, waiting for the peer to close the other half. The sender of this
   * command and the registered handler for incoming data will both be notified
   * once the socket is closed using a [[ConfirmedClosed]] message.
   */
  case object ConfirmedClose extends CloseCommand {
    /**
     * The corresponding event which is sent as an acknowledgment once the
     * close operation is finished.
     */
    override def event = ConfirmedClosed
  }

  /**
   * An abort operation will not flush pending writes and will issue a SCTP ABORT
   * command to the O/S kernel which should result in a TCP_RST packet being sent
   * to the peer. The sender of this command and the registered handler for
   * incoming data will both be notified once the socket is closed using a
   * [[Aborted]] message.
   */
  case object Abort extends CloseCommand {
    /**
     * The corresponding event which is sent as an acknowledgment once the
     * close operation is finished.
     */
    override def event = Aborted
  }

  /**
   * Each [[Send]] can optionally request a positive acknowledgment to be sent
   * to the commanding actor. If such notification is not desired the [[Send#ack]]
   * must be set to an instance of this class. The token contained within can be used
   * to recognize which write failed when receiving a [[CommandFailed]] message.
   */
  case class NoAck(token: Any) extends Event

  /**
   * Default [[NoAck]] instance which is used when no acknowledgment information is
   * explicitly provided. Its “token” is `null`.
   */
  object NoAck extends NoAck(null)

  /**
   * Send data to the SCTP connection. If no ack is needed use the special
   * `NoAck` object. The connection actor will reply with a [[CommandFailed]]
   * message if the write could not be enqueued. If [[Send#wantsAck]]
   * returns true, the connection actor will reply with the supplied [[Send#ack]]
   * token once the write has been successfully enqueued to the O/S kernel.
   * <b>Note that this does not in any way guarantee that the data will be
   * or have been sent!</b> Unfortunately there is no way to determine whether
   * a particular write has been sent by the O/S.
   */
  final case class Send(message: SctpMessage, ack: Event) extends Command {
    require(ack != null, "ack must be non-null. Use NoAck if you don't want acks.")

    def wantsAck: Boolean = !ack.isInstanceOf[NoAck]
  }

  /// EVENTS
  /**
   * Common interface for all events generated by the SCTP layer actors.
   */
  trait Event extends Message

  /**
   * Whenever sctp message is read from a socket it will be transferred within this
   * class to the handler actor which was designated in the [[Register]] message.
   */
  final case class Received(message: SctpMessage) extends Event

  /**
   * The connection actor sends this message either to the sender of a [[Connect]]
   * command (for outbound) or to the handler for incoming connections designated
   * in the [[Bind]] message. The connection is characterized by the `remoteAddresses`
   * and `localAddresses` SCTP endpoints, and `association`.
   */
  final case class Connected(remoteAddresses: Set[InetSocketAddress], localAddresses: Set[InetSocketAddress], association: SctpAssociation) extends Event

  /**
   * Whenever a command cannot be completed, the queried actor will reply with
   * this message, wrapping the original command which failed.
   */
  final case class CommandFailed(cmd: Command) extends Event

  /**
   * When `useResumeWriting` is in effect as indicated in the [[Register]] message,
   * the [[ResumeWriting]] command will be acknowledged by this message type, upon
   * which it is safe to send at least one write. This means that all writes preceding
   * the first [[CommandFailed]] message have been enqueued to the O/S kernel at this
   * point.
   */
  sealed trait WritingResumed extends Event
  case object WritingResumed extends WritingResumed

  /**
   * The sender of a [[Bind]] command will—in case of success—receive confirmation
   * in this form. If the bind address indicated a 0 port number, then the contained
   * `localAddress` can be used to find out which port was automatically assigned.
   */
  final case class Bound(localAddresses: Set[InetSocketAddress], port: Int) extends Event

  /**
   * The sender of an [[Unbind]] command will receive confirmation through this
   * message once the listening socket has been closed.
   */
  sealed trait Unbound extends Event
  case object Unbound extends Unbound

  /**
   * This is the common interface for all events which indicate that a connection
   * has been closed or half-closed.
   */
  sealed trait ConnectionClosed extends Event with DeadLetterSuppression {
    /**
     * `true` iff the connection has been closed in response to an [[Abort]] command.
     */
    def isAborted: Boolean = false
    /**
     * `true` iff the connection has been fully closed in response to a
     * [[ConfirmedClose]] command.
     */
    def isConfirmed: Boolean = false
    /**
     * `true` iff the connection has been closed by the peer; in case
     * `keepOpenOnPeerClosed` is in effect as per the [[Register]] command,
     * this connection’s reading half is now closed.
     */
    def isPeerClosed: Boolean = false
    /**
     * `true` iff the connection has been closed due to an IO error.
     */
    def isErrorClosed: Boolean = false
    /**
     * If `isErrorClosed` returns true, then the error condition can be
     * retrieved by this method.
     */
    def getErrorCause: String = null
  }
  /**
   * The connection has been closed normally in response to a [[Close]] command.
   */
  case object Closed extends ConnectionClosed
  /**
   * The connection has been aborted in response to an [[Abort]] command.
   */
  case object Aborted extends ConnectionClosed {
    override def isAborted = true
  }
  /**
   * The connection has been half-closed by us and then half-close by the peer
   * in response to a [[ConfirmedClose]] command.
   */
  case object ConfirmedClosed extends ConnectionClosed {
    override def isConfirmed = true
  }
  /**
   * The peer has closed its writing half of the connection.
   */
  case object PeerClosed extends ConnectionClosed {
    override def isPeerClosed = true
  }
  /**
   * The connection has been closed due to an IO error.
   */
  final case class ErrorClosed(cause: String) extends ConnectionClosed {
    override def isErrorClosed = true
    override def getErrorCause = cause
  }

}

class SctpExt(system: ExtendedActorSystem) extends IO.Extension {

  val Settings = new Settings(system.settings.config.getConfig("akka.io.sctp"))
  class Settings private[SctpExt] (_config: Config) extends SelectionHandlerSettings(_config) {
    import akka.util.Helpers.ConfigOps
    import _config._

    val NrOfSelectors: Int = getInt("nr-of-selectors") requiring (_ > 0, "nr-of-selectors must be > 0")

    val BatchAcceptLimit: Int = getInt("batch-accept-limit") requiring (_ > 0, "batch-accept-limit must be > 0")
    val DirectBufferSize: Int = getIntBytes("direct-buffer-size")
    val MaxDirectBufferPoolSize: Int = getInt("direct-buffer-pool-limit")
    val RegisterTimeout: Duration = getString("register-timeout") match {
      case "infinite" ⇒ Duration.Undefined
      case x ⇒ _config.getMillisDuration("register-timeout")
    }
    val ReceivedMessageSizeLimit: Int = getString("max-received-message-size") match {
      case "unlimited" ⇒ Int.MaxValue
      case x ⇒ getIntBytes("max-received-message-size")
    }
    val ManagementDispatcher: String = getString("management-dispatcher")

    val MaxChannelsPerSelector: Int = if (MaxChannels == -1) -1 else math.max(MaxChannels / NrOfSelectors, 1)
    val FinishConnectRetries: Int = getInt("finish-connect-retries") requiring (_ > 0,
      "finish-connect-retries must be > 0")

    val WindowsConnectionAbortWorkaroundEnabled: Boolean = getString("windows-connection-abort-workaround-enabled") match {
      case "auto" ⇒ Helpers.isWindows
      case _ ⇒ getBoolean("windows-connection-abort-workaround-enabled")
    }

    private[this] def getIntBytes(path: String): Int = {
      val size = getBytes(path)
      require(size < Int.MaxValue, s"$path must be < 2 GiB")
      require(size >= 0, s"$path must be non-negative")
      size.toInt
    }

  }

  val manager: ActorRef = {
    system.systemActorOf(
      props = Props(classOf[SctpManager], this).withDispatcher(Settings.ManagementDispatcher).withDeploy(Deploy.local),
      name = "IO-SCTP")
  }

  /**
   * Java API: retrieve a reference to the manager actor.
   */
  def getManager: ActorRef = manager

  val bufferPool: BufferPool = new DirectByteBufferPool(Settings.DirectBufferSize, Settings.MaxDirectBufferPoolSize)

}