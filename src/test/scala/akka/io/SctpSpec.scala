package akka.io

import org.junit.runner.RunWith
import org.scalatest.{ WordSpecLike, Matchers }
import org.scalatest.prop.PropertyChecks
import akka.actor.ActorSystem
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

@RunWith(classOf[JUnitRunner])
class SctpSpec extends WordSpecLike with Matchers with PropertyChecks with ActorSystemTestKit {

  val timeout = 500.millis.dilated(actorSystem)
  val LOCALHOST = "127.0.0.1"
  val byteArrayGenerator = Gen.nonEmptyContainerOf[Array, Byte](Arbitrary.arbitrary[Byte])
  implicit override val generatorDrivenConfig = PropertyCheckConfig(minSize = 1, maxSize = 100000, minSuccessful = 25, workers = 5)

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
          val id = nextInt()
          map(id) = array
          sendMessage(client, array, id % 10, id)
      }
      handler.receiveN(generatorDrivenConfig.minSuccessful, timeout * 100) foreach {
        case Received(message) =>
          message should not be (null)
          message.info should not be (null)
          message.info.address should not be (null)
          client.localAddresses should contain(message.info.address)
          val bytes = map(message.info.payloadProtocolID)
          message.data.toArray[Byte] should contain theSameElementsInOrderAs bytes
      }
      theend
    }
    /*"receive multiple incoming connections, register handler actors and receive messages" in new SctpServerWithMultipleConnectedClientTest(5) {
      sendAndAssertMessage(clients(0), Array.range(0, 10000).map(_.toByte), 0)
      sendAndAssertMessage(clients(1), Array.range(0, 10000).map(_.toByte), 1)
      sendAndAssertMessage(clients(2), Array.range(0, 10000).map(_.toByte), 0)
      sendAndAssertMessage(clients(3), Array.range(0, 10000).map(_.toByte), 1)
      sendAndAssertMessage(clients(4), Array.range(0, 10000).map(_.toByte), 0)
      theend
    }*/
  }

  ////////////// TEST UTILS //////////////

  case class Client(channel: SctpChannel, handler: TestProbe, localAddresses: Set[InetSocketAddress])

  abstract class ScptTest extends ActorSystemTest

  abstract class SctpServerBoundTest extends ScptTest {
    IO(Sctp) ! Bind(actor.ref, temporaryServerAddress())
    val bound = actor.expectMsgType[Bound](timeout)
    bound.localAddresses should have size 1
    bound.localAddresses.head.getHostString should be(LOCALHOST)
    bound.port should be > 0
  }

  abstract class SctpServerWithSingleConnectedClientTest extends SctpServerBoundTest {
    val clientChannel = SctpChannel.open(bound.localAddresses.head, 0, 0);
    val connected = actor.expectMsgType[Connected](timeout)
    connected.association should not be (null)
    connected.remoteAddresses should not be empty
    connected.localAddresses should have size 1
    connected.localAddresses.head.getHostString should be(LOCALHOST)
    val handler = TestProbe()
    actor.reply(Register(handler.ref))
    import scala.collection.JavaConversions._
    val localAddresses = clientChannel.getAllLocalAddresses.map(_.asInstanceOf[InetSocketAddress]).toSet
    val client = Client(clientChannel, handler, localAddresses)

    override def theend = {
      clientChannel.close()
      stop(handler)
      super.theend
    }
  }

  abstract class SctpServerWithMultipleConnectedClientTest(numberOfClients: Int) extends SctpServerBoundTest {
    val clients = for (i <- 1 to numberOfClients) yield {
      val clientChannel = SctpChannel.open(bound.localAddresses.head, 0, 0);
      val connected = actor.expectMsgType[Connected](timeout)
      connected.association should not be (null)
      connected.remoteAddresses should not be empty
      connected.localAddresses should have size 1
      connected.localAddresses.head.getHostString should be(LOCALHOST)
      val handler = TestProbe()
      actor.reply(Register(handler.ref))
      import scala.collection.JavaConversions._
      val localAddresses = clientChannel.getAllLocalAddresses.map(_.asInstanceOf[InetSocketAddress]).toSet
      Client(clientChannel, handler, localAddresses)
    }

    override def theend = {
      clients foreach {
        case Client(clientChannel, handler, _) =>
          clientChannel.close()
          stop(handler)
      }
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
    received.message.data.toArray[Byte] should contain theSameElementsInOrderAs bytes
    client.localAddresses should contain(received.message.info.address)
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

  val nsn = new AtomicInteger(0)
  def nextInt(): Int = nsn.getAndIncrement
}