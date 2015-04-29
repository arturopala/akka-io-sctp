package akka.io

import com.typesafe.config.ConfigFactory
import akka.testkit.TestKit
import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import akka.testkit.TestProbe
import org.scalatest.BeforeAndAfter
import org.scalatest.Suite
import akka.testkit.TestKitBase
import org.scalatest.BeforeAndAfterAll

trait ActorSystemTestKit extends BeforeAndAfterAll { this: Suite =>

  private lazy val config = ConfigFactory.parseString("""
  akka {
        loggers = ["akka.testkit.TestEventListener"]
        loglevel = "DEBUG"
        stdout-loglevel = "WARNING"
        actor {
          default-dispatcher {
            executor = "fork-join-executor"
            fork-join-executor {
              parallelism-min = 8
              parallelism-factor = 2.0
              parallelism-max = 8
            }
          }
          serialize-creators = on
        }
        log-dead-letters = 0
        log-dead-letters-during-shutdown = off
        io {
          sctp {
            register-timeout = 500ms
          }
        }
      }
  """)
  private lazy val actorSystemConfig = config.withFallback(ConfigFactory.load)
  lazy val actorSystem = ActorSystem("sctpspec", actorSystemConfig)

  class ActorSystemTest extends TestKit(actorSystem) {
    val actor = TestProbe()
    implicit val sender = actor.ref
    def stop(probe:TestProbe) = {
       actorSystem stop probe.ref
    }
    def theend = {
      stop(actor)
    }
  }

  override def afterAll() {
    Thread.sleep(100)
    TestKit.shutdownActorSystem(actorSystem)
  }
}