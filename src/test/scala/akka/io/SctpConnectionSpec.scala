package akka.io

import java.io.{ File, IOException }
import java.net.{ ServerSocket, URLClassLoader, InetSocketAddress }
import java.nio.ByteBuffer
import java.nio.channels.spi.SelectorProvider
import java.nio.channels.{ SelectableChannel, SelectionKey, Selector }
import java.nio.channels.SelectionKey._

import com.typesafe.config.{ Config, ConfigFactory }
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration._
import scala.util.control.NonFatal
import org.scalatest.matchers._
import akka.io.Sctp._
import akka.io.SelectionHandler._
import akka.io.SctpInet.SctpSocketOption
import akka.actor._
import akka.testkit.{ EventFilter, TestActorRef, TestProbe, TestKit, WatchedByCoroner, AkkaSpec }
import akka.util.{ Helpers, ByteString }
import akka.testkit.SocketUtil._
import java.util.Random
import com.sun.nio.sctp.{ SctpChannel, SctpServerChannel, SctpStandardSocketOptions, MessageInfo }
import scala.collection.JavaConversions._

object SctpConnectionSpec {
  case class Ack(i: Int) extends Event
  object Ack extends Ack(0)
  final case class Registration(channel: SelectableChannel, initialOps: Int) extends NoSerializationVerificationNeeded
}


class SctpConnectionSpec extends AkkaSpec("""
    akka.io.tcp.register-timeout = 500ms
    akka.actor.serialize-creators = on
    """) { thisSpecs ⇒
  import SctpConnectionSpec._

  // Helper to avoid Windows localization specific differences
  def ignoreIfWindows(): Unit =
    if (Helpers.isWindows) {
      info("Detected Windows: ignoring check")
      pending
    }

  lazy val ConnectionResetByPeerMessage: String = {
    val serverSocket = SctpServerChannel.open()
    serverSocket.bind(new InetSocketAddress("127.0.0.1", 0))
    val port:Int = serverSocket.getAllLocalAddresses().head.asInstanceOf[InetSocketAddress].getPort
    try {
      val clientSocket = SctpChannel.open(new InetSocketAddress("127.0.0.1", port),0,0)
      val clientSocketOnServer = acceptServerSideConnection(serverSocket)
      clientSocketOnServer.setOption(SctpStandardSocketOptions.SO_LINGER, Int.box(0))
      clientSocketOnServer.close()
      clientSocket.receive(ByteBuffer.allocate(1),null,null)
      null
    } catch {
      case NonFatal(e) ⇒ e.getMessage
    }
  }

  lazy val ConnectionRefusedMessagePrefix: String = {
    val serverSocket = SctpServerChannel.open()
    serverSocket.bind(new InetSocketAddress("127.0.0.1", 0))
    val port:Int = serverSocket.getAllLocalAddresses().head.asInstanceOf[InetSocketAddress].getPort
    try {
      serverSocket.close()
      val clientSocket = SctpChannel.open(new InetSocketAddress("127.0.0.1", port),0,0)
      clientSocket.finishConnect()
      val messageInfo = MessageInfo.createOutgoing(null,0);
      clientSocket.send(ByteBuffer.allocate(1),messageInfo)
      null
    } catch {
      case NonFatal(e) ⇒ e.getMessage.take(15)
    }
  }

  "An outgoing connection" must {
    info("Connection reset by peer message expected is " + ConnectionResetByPeerMessage)
    info("Connection refused message prefix expected is " + ConnectionRefusedMessagePrefix)
    // common behavior

    "set socket options before connecting" in new LocalServerTest() {
      run {
        /*val connectionActor = createConnectionActor(options = Vector(SctpInet.SO.ReuseAddress(true)))
        val clientChannel = connectionActor.underlyingActor.channel
        clientChannel.socket.getReuseAddress should ===(true)*/
      }
    }
  }

/////////////////////////////////////// TEST SUPPORT ////////////////////////////////////////////////

  def acceptServerSideConnection(localServer: SctpServerChannel): SctpChannel = {
    @volatile var serverSideChannel: SctpChannel = null
    awaitCond {
      serverSideChannel = localServer.accept()
      serverSideChannel != null
    }
    serverSideChannel
  }

  abstract class LocalServerTest extends ChannelRegistry {
    /** Allows overriding the system used */
    implicit def system: ActorSystem = thisSpecs.system

    val serverAddress = temporaryServerAddress()
    val localServerChannel = SctpServerChannel.open()
    val userHandler = TestProbe()
    val selector = TestProbe()

    var registerCallReceiver = TestProbe()
    var interestCallReceiver = TestProbe()

    def ignoreWindowsWorkaroundForTicket15766(): Unit = {
      // Due to the Windows workaround of #15766 we need to set an OP_CONNECT to reliably detect connection resets
      if (Helpers.isWindows) interestCallReceiver.expectMsg(OP_CONNECT)
    }

    def run(body: ⇒ Unit): Unit = {
      try {
        setServerSocketOptions()
        localServerChannel.bind(serverAddress)
        localServerChannel.configureBlocking(false)
        body
      } finally localServerChannel.close()
    }

    def register(channel: SelectableChannel, initialOps: Int)(implicit channelActor: ActorRef): Unit =
      registerCallReceiver.ref.tell(Registration(channel, initialOps), channelActor)

    def setServerSocketOptions() = ()

    def createConnectionActor(serverAddress: InetSocketAddress = serverAddress,
                              options: immutable.Seq[SctpSocketOption] = Nil,
                              timeout: Option[FiniteDuration] = None,
                              pullMode: Boolean = false): TestActorRef[SctpOutgoingConnection] = {
      val ref = createConnectionActorWithoutRegistration(serverAddress, options, timeout, pullMode)
      ref ! newChannelRegistration
      ref
    }

    def newChannelRegistration: ChannelRegistration =
      new ChannelRegistration {
        def enableInterest(op: Int): Unit = interestCallReceiver.ref ! op
        def disableInterest(op: Int): Unit = interestCallReceiver.ref ! -op
      }

    def createConnectionActorWithoutRegistration(serverAddress: InetSocketAddress = serverAddress,
                                                 options: immutable.Seq[SctpSocketOption] = Nil,
                                                 timeout: Option[FiniteDuration] = None,
                                                 pullMode: Boolean = false): TestActorRef[SctpOutgoingConnection] =
      TestActorRef(
        new SctpOutgoingConnection(Sctp(system), this, userHandler.ref,
          Connect(serverAddress, options = options, timeout = timeout, pullMode = pullMode)) {
          override def postRestart(reason: Throwable): Unit = context.stop(self) // ensure we never restart
        })
  }

  trait SmallRcvBuffer { _: LocalServerTest ⇒
    override def setServerSocketOptions(): Unit = localServerChannel.setOption(SctpStandardSocketOptions.SO_RCVBUF, Int.box(1024))
  }

  abstract class UnacceptedConnectionTest(pullMode: Boolean = false) extends LocalServerTest {
    // lazy init since potential exceptions should not be triggered in the constructor but during execution of `run`
    private[io] lazy val connectionActor = createConnectionActor(serverAddress, pullMode = pullMode)
    // calling .underlyingActor ensures that the actor is actually created at this point
    lazy val clientSideChannel = connectionActor.underlyingActor.channel

    override def run(body: ⇒ Unit): Unit = super.run {
      registerCallReceiver.expectMsg(Registration(clientSideChannel, 0))
      registerCallReceiver.sender should ===(connectionActor)
      body
    }
  }

  abstract class EstablishedConnectionTest(
    keepOpenOnPeerClosed: Boolean = false,
    useResumeWriting: Boolean = true,
    pullMode: Boolean = false)
    extends UnacceptedConnectionTest(pullMode) {

    // lazy init since potential exceptions should not be triggered in the constructor but during execution of `run`
    lazy val serverSideChannel = acceptServerSideConnection(localServerChannel)
    lazy val connectionHandler = TestProbe()
    lazy val nioSelector = SelectorProvider.provider().openSelector()
    lazy val clientSelectionKey = registerChannel(clientSideChannel, "client")
    lazy val serverSelectionKey = registerChannel(serverSideChannel, "server")
    lazy val defaultbuffer = ByteBuffer.allocate(TestSize)

    def windowsWorkaroundToDetectAbort(): Unit = {
      // Due to a Windows quirk we need to set an OP_CONNECT to reliably detect connection resets, see #1576
      if (Helpers.isWindows) {
        serverSelectionKey.interestOps(OP_CONNECT)
        nioSelector.select(10)
      }
    }

    override def ignoreWindowsWorkaroundForTicket15766(): Unit = {
      super.ignoreWindowsWorkaroundForTicket15766()
      if (Helpers.isWindows) clientSelectionKey.interestOps(OP_CONNECT)
    }

    override def run(body: ⇒ Unit): Unit = super.run {
      try {
        serverSideChannel.configureBlocking(false)
        serverSideChannel should not be (null)

        interestCallReceiver.expectMsg(OP_CONNECT)
        selector.send(connectionActor, ChannelConnectable)
        userHandler.expectMsg(
          Connected(
            serverSideChannel.getRemoteAddresses.map(_.asInstanceOf[InetSocketAddress]).toSet,
            serverSideChannel.getAllLocalAddresses.map(_.asInstanceOf[InetSocketAddress]).toSet,
            serverSideChannel.association()
        ))

        userHandler.send(connectionActor, Register(connectionHandler.ref, keepOpenOnPeerClosed, useResumeWriting))
        ignoreWindowsWorkaroundForTicket15766()
        if (!pullMode) interestCallReceiver.expectMsg(OP_READ)

        clientSelectionKey // trigger initialization
        serverSelectionKey // trigger initialization
        body
      } finally {
        serverSideChannel.close()
        nioSelector.close()
      }
    }

    final val TestSize = 10000 // compile-time constant

    def writeCmd(ack: Event) =
      Write(ByteString(Array.fill[Byte](TestSize)(0)), ack)

    def closeServerSideAndWaitForClientReadable(fullClose: Boolean = true): Unit = {
      if (fullClose) serverSideChannel.close() else serverSideChannel.shutdown()
      checkFor(clientSelectionKey, OP_READ, 3.seconds.toMillis.toInt) should ===(true)
    }

    def registerChannel(channel: SctpChannel, name: String): SelectionKey = {
      val res = channel.register(nioSelector, 0)
      res.attach(name)
      res
    }

    def checkFor(key: SelectionKey, interest: Int, millis: Int = 100): Boolean =
      if (key.isValid) {
        key.interestOps(interest)
        nioSelector.selectedKeys().clear()
        val ret = nioSelector.select(millis)
        key.interestOps(0)

        ret > 0 && nioSelector.selectedKeys().contains(key) && key.isValid &&
          (key.readyOps() & interest) != 0
      } else false

    def openSelectorFor(channel: SctpChannel, interests: Int): (Selector, SelectionKey) = {
      val sel = SelectorProvider.provider().openSelector()
      val key = channel.register(sel, interests)
      (sel, key)
    }

    /**
     * Tries to simultaneously act on client and server side to read from the server all pending data from the client.
     */
    @tailrec final def pullFromServerSide(remaining: Int, remainingTries: Int = 1000,
                                          into: ByteBuffer = defaultbuffer): Unit =
      if (remainingTries <= 0)
        throw new AssertionError("Pulling took too many loops,  remaining data: " + remaining)
      else if (remaining > 0) {
        if (interestCallReceiver.msgAvailable) {
          interestCallReceiver.expectMsg(OP_WRITE)
          clientSelectionKey.interestOps(OP_WRITE)
        }

        serverSelectionKey.interestOps(OP_READ)
        nioSelector.select(10)
        if (nioSelector.selectedKeys().contains(clientSelectionKey)) {
          clientSelectionKey.interestOps(0)
          selector.send(connectionActor, ChannelWritable)
        }

        val read =
          if (nioSelector.selectedKeys().contains(serverSelectionKey)) {
            if (into eq defaultbuffer) into.clear()
            serverSideChannel.receive(into,null,null).bytes match {
              case -1    ⇒ throw new IllegalStateException("Connection was closed unexpectedly with remaining bytes " + remaining)
              case 0     ⇒ throw new IllegalStateException("Made no progress")
              case other ⇒ other
            }
          } else 0

        nioSelector.selectedKeys().clear()

        pullFromServerSide(remaining - read, remainingTries - 1, into)
      }

    @tailrec final def expectReceivedString(data: String): Unit = {
      data.length should be > 0

      selector.send(connectionActor, ChannelReadable)

      val gotReceived = connectionHandler.expectMsgType[Received]
      val receivedString = gotReceived.data.decodeString("ASCII")
      data.startsWith(receivedString) should ===(true)
      if (receivedString.length < data.length)
        expectReceivedString(data.drop(receivedString.length))
    }

    def assertThisConnectionActorTerminated(): Unit = {
      watch(connectionActor)
      expectTerminated(connectionActor)
      clientSideChannel should not be ('open)
    }

    def selectedAs(interest: Int, duration: Duration): BeMatcher[SelectionKey] =
      new BeMatcher[SelectionKey] {
        def apply(key: SelectionKey) =
          MatchResult(
            checkFor(key, interest, duration.toMillis.toInt),
            "%s key was not selected for %s after %s" format (key.attachment(), interestsDesc(interest), duration),
            "%s key was selected for %s after %s" format (key.attachment(), interestsDesc(interest), duration))
      }

    val interestsNames =
      Seq(OP_ACCEPT -> "accepting", OP_CONNECT -> "connecting", OP_READ -> "reading", OP_WRITE -> "writing")
    def interestsDesc(interests: Int): String =
      interestsNames.filter(i ⇒ (i._1 & interests) != 0).map(_._2).mkString(", ")

    def abortClose(channel: SctpChannel): Unit = {
      try channel.setOption(SctpStandardSocketOptions.SO_LINGER, Int.box(0)) // causes the following close() to send TCP RST
      catch {
        case NonFatal(e) ⇒
          // setSoLinger can fail due to http://bugs.sun.com/view_bug.do?bug_id=6799574
          // (also affected: OS/X Java 1.6.0_37)
          log.debug("setSoLinger(true, 0) failed with {}", e)
      }
      channel.close()
      if (Helpers.isWindows) nioSelector.select(10) // Windows needs this
    }
  }

}