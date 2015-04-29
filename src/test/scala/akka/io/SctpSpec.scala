package akka.io

import org.junit.runner.RunWith
import org.scalatest.{ WordSpecLike, Matchers }
import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestActorRef, TestKit }
import akka.testkit._
import com.typesafe.config.ConfigFactory
import akka.testkit.TestProbe
import scala.concurrent.duration._
import akka.actor.ActorRef
import org.scalatest.junit.JUnitRunner
import java.net.{ InetSocketAddress }
import java.nio.{ ByteBuffer }
import com.sun.nio.sctp._

@RunWith(classOf[JUnitRunner])
class SctpSpec extends WordSpecLike with Matchers with ActorSystemTestKit {

    class ScptTest extends ActorSystemTest
    val timeout = 500.millis.dilated(actorSystem)

    import Sctp._

    "An Sctp listener actor" should {
        "bind to a free socket and close it when handler actor stopped" in new ScptTest {
            IO(Sctp) ! Bind(actor.ref, new InetSocketAddress("127.0.0.1",0))
            val bound = actor.expectMsgType[Bound](timeout)
            bound.localAddresses should have size 1
            bound.localAddresses.head.getHostString should be("127.0.0.1")
            bound.port should be > 0
            stop(actor)
            //try to rebind to the same port
            val actor2 = TestProbe()
            IO(Sctp).tell(Bind(actor2.ref, new InetSocketAddress("127.0.0.1", bound.port)),actor2.ref)
            actor2.expectMsgType[Bound](timeout)
            stop(actor2)
        }
        "bind/unbind to a free socket mutliple times" in new ScptTest {
            IO(Sctp) ! Bind(actor.ref, new InetSocketAddress("127.0.0.1",0))
            val bound = actor.expectMsgType[Bound](timeout)
            bound.localAddresses should have size 1
            bound.localAddresses.head.getHostString should be("127.0.0.1")
            bound.port should be > 0
            actor.reply(Unbind)
            actor.expectMsgType[Unbound](timeout)
            IO(Sctp) ! Bind(actor.ref, new InetSocketAddress("127.0.0.1",bound.port))
            val bound2 = actor.expectMsgType[Bound](timeout)
            bound2.localAddresses should have size 1
            bound2.localAddresses.head.getHostString should be("127.0.0.1")
            bound2.port should be > 0
            actor.reply(Unbind)
            actor.expectMsgType[Unbound](timeout)
            theend
        }
        "receive CommandFailed when trying to bind to the busy port" in new ScptTest {
            IO(Sctp) ! Bind(actor.ref, new InetSocketAddress("127.0.0.1",0))
            val bound = actor.expectMsgType[Bound](timeout)
            bound.localAddresses should have size 1
            bound.localAddresses.head.getHostString should be("127.0.0.1")
            bound.port should be > 0
            val actor2 = TestProbe()
            val nextCommand = Bind(actor2.ref, new InetSocketAddress("127.0.0.1", bound.port))
            IO(Sctp).tell(nextCommand,actor2.ref)
            val msg = actor2.expectMsgType[CommandFailed](timeout)
            msg.cmd should be(nextCommand)
            stop(actor2)
            theend
        }
        "receive incoming connection and register handler actor" in new ScptTest {
            IO(Sctp) ! Bind(actor.ref, new InetSocketAddress("127.0.0.1",0))
            val bound = actor.expectMsgType[Bound](timeout)
            val clientChannel = SctpChannel.open(bound.localAddresses.head, 0, 0);
            val connected = actor.expectMsgType[Connected](timeout)
            connected.association should not be(null)
            connected.remoteAddresses should not be empty
            connected.localAddresses should have size 1
            connected.localAddresses.head.getHostString should be("127.0.0.1")
            val handler = TestProbe()
            actor.reply(Register(handler.ref))
            val messageInfo = MessageInfo.createOutgoing(null, 0)
            val buf = ByteBuffer.allocateDirect(1024)
            buf.put(Array.range(0,1023).map(_.toByte))
            buf.flip()
            clientChannel.send(buf, messageInfo)
            val received = handler.expectMsgType[Received](timeout)
            println(received)
            stop(handler)
            clientChannel.close()
            theend
        }
    }
}