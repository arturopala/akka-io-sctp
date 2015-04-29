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

@RunWith(classOf[JUnitRunner])
class SctpSpec extends WordSpecLike with Matchers with ActorSystemTestKit {

    class ScptTest extends ActorSystemTest
    val timeout = 500.millis.dilated(actorSystem)

    import Sctp._

    "An Sctp listener actor" should {
        "bind to free socket and unbind" in new ScptTest {
            val handler = TestProbe()
            IO(Sctp) ! Bind(handler.ref, new InetSocketAddress("127.0.0.1",0))
            val bound = actor.expectMsgType[Bound](timeout)
            bound.localAddresses should have size 1
            bound.localAddresses.head.getHostString should be("127.0.0.1")
            bound.localAddresses.head.getPort should be > 0
            actor.reply(Unbind)
            actor.expectMsgType[Unbound](timeout)
        }
    }
}