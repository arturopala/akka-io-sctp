package akka.io

import org.junit.runner.RunWith
import org.scalatest.{ WordSpecLike, Matchers }
import org.scalatest.prop.PropertyChecks
import akka.actor.{ Actor, ActorSystem, Props }
import akka.testkit.{ ImplicitSender, TestActorRef, TestKit }
import akka.testkit._
import com.typesafe.config.ConfigFactory
import akka.testkit.TestProbe
import scala.concurrent.duration._
import akka.actor.{ ActorRef, Terminated }
import org.scalatest.junit.JUnitRunner
import java.net.{ InetSocketAddress }
import java.nio.{ ByteBuffer }
import com.sun.nio.sctp._
import org.scalacheck._
import java.util.concurrent.atomic.AtomicInteger
import akka.util.ByteString

@RunWith(classOf[JUnitRunner])
class SctpSpec extends WordSpecLike with Matchers with PropertyChecks with ActorSystemTestKit {

  val timeout = 500.millis.dilated(actorSystem)
  val LOCALHOST = "127.0.0.1"
  val NO_OF_SIMULTANEOUS_SCTP_CLIENTS = 5
  val SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS = 25
  val SCTP_CLIENT_MAX_NO_OF_OUTBOUND_STREAMS = 36
  val byteArrayGenerator = Gen.nonEmptyContainerOf[Array, Byte](Arbitrary.arbitrary[Byte])
  implicit override val generatorDrivenConfig = PropertyCheckConfig(minSize = 1, maxSize = 100 * 1024, minSuccessful = 25, workers = 5)

  import Sctp._

  "An Sctp listener actor" should {
    "bind to a free socket and close it when handler actor stopped" in new SctpServerBoundTest {
      stop(actor)
      //try to rebind to the same port
      val actor2 = TestProbe()
      IO(Sctp).tell(Bind(actor2.ref, new InetSocketAddress(LOCALHOST, bound.port)), actor2.ref)
      actor2.expectMsgType[Bound](timeout)
      stop(actor2)
    }
    "bind/unbind to a free socket mutliple times" in new SctpServerBoundTest {
      actor.reply(Unbind)
      actor.expectMsgType[Unbound](timeout)
      IO(Sctp) ! Bind(actor.ref, new InetSocketAddress(LOCALHOST, bound.port))
      val bound2 = actor.expectMsgType[Bound](timeout)
      bound2.localAddresses should have size 1
      bound2.localAddresses.head.getHostString should be(LOCALHOST)
      bound2.port should be > 0
      actor.reply(Unbind)
      actor.expectMsgType[Unbound](timeout)
      theend
    }
    "receive CommandFailed when trying to bind to the already connected port" in new SctpServerBoundTest {
      val actor2 = TestProbe()
      val nextCommand = Bind(actor2.ref, new InetSocketAddress(LOCALHOST, bound.port))
      IO(Sctp).tell(nextCommand, actor2.ref)
      val msg = actor2.expectMsgType[CommandFailed](timeout)
      msg.cmd should be(nextCommand)
      stop(actor2)
      theend
    }
    "receive incoming connection, register handler actor and receive messages" in new SctpServerWithSingleConnectedClientTest {
      val map = scala.collection.mutable.Map[Int, Array[Byte]]()
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val id = nsn.getAndIncrement
          map(id) = array
          sendMessage(client, array, id % SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS, id)
      }
      handler.receiveN(generatorDrivenConfig.minSuccessful, timeout * 100) foreach {
        case Received(message) =>
          message should not be (null)
          message.info should not be (null)
          message.info.address should not be (null)
          client.localAddresses should contain(message.info.address)
          val bytes = map(message.info.payloadProtocolID)
          message.payload.toArray[Byte] should contain theSameElementsInOrderAs bytes
      }
      theend
    }
    "receive multiple incoming connections, register handler actors and receive messages" in new SctpServerWithMultipleConnectedClientTest(NO_OF_SIMULTANEOUS_SCTP_CLIENTS) {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val id = nsn.getAndIncrement
          sendAndAssertMessage(clients(id % NO_OF_SIMULTANEOUS_SCTP_CLIENTS), array, id % SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS, id)
      }
      theend
    }
    "send messages to the single incoming connection" in new SctpServerWithSingleConnectedClientTest {
      case object Ack extends Event
      val map = scala.collection.mutable.Map[Int, Array[Byte]]()
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val id = nsn.getAndIncrement
          map(id) = array
          sctpIncomingConnectionActor ! Send(SctpMessage(ByteString(array), id % SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS, id), Ack)
      }
      def receiveAndAssert: Unit = {
        val (bytes, messageInfo) = receiveMessage(client)
        if (messageInfo != null && messageInfo.bytes >= 0) {
          val expected = map(messageInfo.payloadProtocolID)
          bytes should have size expected.length
          bytes should contain theSameElementsInOrderAs expected
        }
        actor.expectMsg(Ack)
      }
      for (i <- 1 to map.size) receiveAndAssert
      theend
    }
    "send messages to the multiple incoming connection" in new SctpServerWithMultipleConnectedClientTest(NO_OF_SIMULTANEOUS_SCTP_CLIENTS) {
      case object Ack extends Event
      val map = scala.collection.mutable.Map[Int, Array[Byte]]()
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val id = nsn.getAndIncrement
          map(id) = array
          val client = clients(id % NO_OF_SIMULTANEOUS_SCTP_CLIENTS)
          client.sctpIncomingConnectionActor ! Send(SctpMessage(ByteString(array), id % SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS, id), Ack)
      }
      def receiveAndAssert(id: Int): Unit = {
        val client = clients(id % NO_OF_SIMULTANEOUS_SCTP_CLIENTS)
        val (bytes, messageInfo) = receiveMessage(client)
        if (messageInfo != null && messageInfo.bytes >= 0) {
          val expected = map(messageInfo.payloadProtocolID)
          bytes should have size expected.length
          bytes should contain theSameElementsInOrderAs expected
          messageInfo.streamNumber should be(id % SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS)
          messageInfo.payloadProtocolID should be(id)
        }
        actor.expectMsg(Ack)
      }
      for (id <- 0 until map.size) receiveAndAssert(id)
      theend
    }
    "receive and send messages as a server" in new SctpServerWithConnectedHandlerActorTest(Props(classOf[TestReverseEchoActor])) {
      implicit val generatorDrivenConfig = PropertyCheckConfig(minSize = 1, maxSize = 100 * 1024, minSuccessful = 100, workers = 1)
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val id = nsn.getAndIncrement
          sendMessage(client, array, id % SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS, id)
          val (bytes, messageInfo) = receiveMessage(client)
          bytes should contain theSameElementsInOrderAs array
          messageInfo.streamNumber should be(id % SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS)
          messageInfo.payloadProtocolID should be(id)
          messageInfo.association should not be (null)
          messageInfo.association.associationID should be > -1
          messageInfo.association.maxInboundStreams should be(SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS)
          messageInfo.association.maxOutboundStreams should be(SCTP_CLIENT_MAX_NO_OF_OUTBOUND_STREAMS)
      }
      theend
    }
    "send and receive messages as a client" in new SctpClientTest {
      case object Ack extends Event
      implicit val generatorDrivenConfig = PropertyCheckConfig(minSize = 1, maxSize = 100 * 1024, minSuccessful = 100, workers = 1)
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val id = nsn.getAndIncrement
          sctpOutgoingConnectionActor.tell(Send(SctpMessage(ByteString(array), id % SCTP_CLIENT_MAX_NO_OF_OUTBOUND_STREAMS, id), Ack), clientProbe.ref)
          val received = serverHandler.expectMsgType[Received](timeout)
          received.message.payload should contain theSameElementsInOrderAs array
          received.message.info.streamNumber should be(id % SCTP_CLIENT_MAX_NO_OF_OUTBOUND_STREAMS)
          received.message.info.payloadProtocolID should be(id)
          received.message.info.bytes should be(array.length)
          received.message.info.association.id should be > -1
          received.message.info.association.maxInboundStreams should be(SCTP_CLIENT_MAX_NO_OF_OUTBOUND_STREAMS)
          received.message.info.association.maxOutboundStreams should be(SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS)
          localAddresses should contain(received.message.info.address)
          clientProbe.expectMsg(Ack)
      }
      theend
    }
  }

  ////////////// TEST UTILS //////////////

  case class Client(channel: SctpChannel, handler: TestProbe, localAddresses: Set[InetSocketAddress], sctpIncomingConnectionActor: ActorRef) {
    def close(implicit system: ActorSystem): Unit = {
      channel.close()
      system.stop(handler.ref)
    }
  }

  abstract class ScptTest extends ActorSystemTest

  abstract class SctpServerBoundTest extends ScptTest {
    IO(Sctp) ! Bind(actor.ref, temporaryServerAddress(), SCTP_CLIENT_MAX_NO_OF_OUTBOUND_STREAMS, SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS)
    val bound = actor.expectMsgType[Bound](timeout)
    bound.localAddresses should have size 1
    bound.localAddresses.head.getHostString should be(LOCALHOST)
    bound.port should be > 0
    val nsn = new AtomicInteger(0)
  }

  abstract class SctpServerWithSingleConnectedClientTest extends SctpServerBoundTest {
    val clientChannel = SctpChannel.open(bound.localAddresses.head, SCTP_CLIENT_MAX_NO_OF_OUTBOUND_STREAMS, SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS)
    val connected = actor.expectMsgType[Connected](timeout)
    val sctpIncomingConnectionActor = actor.lastSender
    connected.association should not be (null)
    connected.remoteAddresses should not be empty
    connected.localAddresses should have size 1
    connected.localAddresses.head.getHostString should be(LOCALHOST)
    connected.association.id should be > -1
    connected.association.maxInboundStreams should be(SCTP_CLIENT_MAX_NO_OF_OUTBOUND_STREAMS)
    connected.association.maxOutboundStreams should be(SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS)
    val handler = TestProbe()
    actor.reply(Register(handler.ref))
    import scala.collection.JavaConversions._
    val localAddresses = clientChannel.getAllLocalAddresses.map(_.asInstanceOf[InetSocketAddress]).toSet
    connected.remoteAddresses should contain theSameElementsAs localAddresses
    val client = Client(clientChannel, handler, localAddresses, sctpIncomingConnectionActor)

    override def theend = {
      client.close(system)
      super.theend
    }
  }

  abstract class SctpServerWithMultipleConnectedClientTest(numberOfClients: Int) extends SctpServerBoundTest {
    val clients = for (i <- 1 to numberOfClients) yield {
      val clientChannel = SctpChannel.open(bound.localAddresses.head, SCTP_CLIENT_MAX_NO_OF_OUTBOUND_STREAMS, SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS)
      val connected = actor.expectMsgType[Connected](timeout)
      val sctpIncomingConnectionActor = actor.lastSender
      connected.association should not be (null)
      connected.remoteAddresses should not be empty
      connected.localAddresses should have size 1
      connected.localAddresses.head.getHostString should be(LOCALHOST)
      connected.association.id should be > -1
      connected.association.maxInboundStreams should be(SCTP_CLIENT_MAX_NO_OF_OUTBOUND_STREAMS)
      connected.association.maxOutboundStreams should be(SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS)
      val handler = TestProbe()
      actor.reply(Register(handler.ref))
      import scala.collection.JavaConversions._
      val localAddresses = clientChannel.getAllLocalAddresses.map(_.asInstanceOf[InetSocketAddress]).toSet
      connected.remoteAddresses should contain theSameElementsAs localAddresses
      Client(clientChannel, handler, localAddresses, sctpIncomingConnectionActor)
    }

    override def theend = {
      clients.foreach(_.close(system))
      super.theend
    }
  }

  abstract class SctpServerWithConnectedHandlerActorTest(props: Props) extends SctpServerBoundTest {
    val clientChannel = SctpChannel.open(bound.localAddresses.head, SCTP_CLIENT_MAX_NO_OF_OUTBOUND_STREAMS, SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS)
    val connected = actor.expectMsgType[Connected](timeout)
    val sctpIncomingConnectionActor = actor.lastSender
    connected.association should not be (null)
    connected.remoteAddresses should not be empty
    connected.localAddresses should have size 1
    connected.localAddresses.head.getHostString should be(LOCALHOST)
    val handler = system.actorOf(props)
    actor.reply(Register(handler))
    import scala.collection.JavaConversions._
    val localAddresses = clientChannel.getAllLocalAddresses.map(_.asInstanceOf[InetSocketAddress]).toSet
    connected.remoteAddresses should contain theSameElementsAs localAddresses
    val client = Client(clientChannel, null, localAddresses, sctpIncomingConnectionActor)

    override def theend = {
      system stop handler
      super.theend
    }
  }

  abstract class SctpClientTest extends SctpServerBoundTest {
    val clientProbe = TestProbe()
    val serverHandler = TestProbe()
    IO(Sctp).tell(Connect(bound.localAddresses.head, SCTP_CLIENT_MAX_NO_OF_OUTBOUND_STREAMS, SCTP_CLIENT_MAX_NO_OF_INBOUND_STREAMS), clientProbe.ref)
    actor.expectMsgType[Connected](timeout)
    actor.reply(Register(serverHandler.ref))
    val connected = clientProbe.expectMsgType[Connected](timeout)
    val sctpOutgoingConnectionActor = clientProbe.lastSender
    connected.association should not be (null)
    connected.remoteAddresses should not be empty
    connected.localAddresses should not be empty
    val clientHandler = TestProbe()
    clientProbe.reply(Register(clientHandler.ref))
    import scala.collection.JavaConversions._
    val localAddresses = connected.localAddresses

    override def theend = {
      system stop clientProbe.ref
      system stop serverHandler.ref
      system stop clientHandler.ref
      super.theend
    }
  }

  def sendMessage(client: Client, bytes: Array[Byte], streamNumber: Int, payloadProtocolID: Int = 0) = {
    val messageInfo = MessageInfo.createOutgoing(null, streamNumber)
    messageInfo.payloadProtocolID(payloadProtocolID)
    val buf = ByteBuffer.allocateDirect(bytes.length)
    buf.put(bytes)
    buf.flip()
    client.channel.send(buf, messageInfo)
  }

  def sendAndAssertMessage(client: Client, bytes: Array[Byte], streamNumber: Int, payloadProtocolID: Int = 0) = {
    sendMessage(client, bytes, streamNumber, payloadProtocolID)
    val received = client.handler.expectMsgType[Received](timeout)
    received.message should not be (null)
    received.message.info should have(
      'streamNumber(streamNumber),
      'bytes(bytes.length),
      'payloadProtocolID(payloadProtocolID)
    )
    received.message.payload.toArray[Byte] should contain theSameElementsInOrderAs bytes
    client.localAddresses should contain(received.message.info.address)
  }

  def receiveMessage(client: Client): (Array[Byte], MessageInfo) = {
    def receive(prev: ByteString = ByteString.empty): (ByteString, MessageInfo) = {
      val buf = ByteBuffer.allocateDirect(1024)
      val messageInfo = client.channel.receive(buf, null, null)
      buf.flip()
      val byteString = prev ++ ByteString(buf)
      if (messageInfo == null || messageInfo.isComplete) (byteString, messageInfo)
      else receive(byteString)
    }
    val (byteString, messageInfo) = receive()
    (byteString.toArray, messageInfo)
  }

  def temporaryServerAddress(hostname: String = LOCALHOST): InetSocketAddress = {
    import java.nio.channels.ServerSocketChannel
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress(hostname, 0))
    val address = new InetSocketAddress(hostname, serverSocket.getLocalPort)
    serverSocket.close()
    address
  }

  def temporaryServerAddresses(numberOfAddresses: Int, hostname: String = LOCALHOST): scala.collection.immutable.IndexedSeq[InetSocketAddress] = {
    import java.nio.channels.ServerSocketChannel
    Vector.fill(numberOfAddresses) {
      val serverSocket = ServerSocketChannel.open().socket()
      serverSocket.bind(new InetSocketAddress(hostname, 0))
      (serverSocket, new InetSocketAddress(hostname, serverSocket.getLocalPort))
    } collect { case (socket, address) ⇒ socket.close(); address }
  }

}

class TestReverseEchoActor() extends Actor {
  import Sctp._
  object Ack extends Event
  def receive = {
    case Received(msg) =>
      sender ! Send(msg, Ack)
    case Ack =>
  }
}