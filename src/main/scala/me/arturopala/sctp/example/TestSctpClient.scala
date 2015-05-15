package me.arturopala.sctp.example

import akka.actor._
import akka.io._
import akka.util._
import java.net.InetSocketAddress
import scala.util.Random
import scala.concurrent.duration._

object TestSctpClient {
  def main(args: Array[String]): Unit = {
    val port = if (args.length > 0) args(0).toInt else 8080
    val noOfWorkers = if (args.length > 1) args(1).toInt else 100
    val system = ActorSystem("test-sctp-client")
    (1 to noOfWorkers) foreach { i =>
      system.actorOf(Props(classOf[TestSctpClientWorker], port, i))
    }
  }
}

class TestSctpClientWorker(port: Int, id: Int) extends Actor {

  import Sctp._
  import system.dispatcher

  var lastMessage: SctpMessage = _

  case class Ack(message: SctpMessage) extends Event

  println(s"worker #$id: trying connect ...")

  implicit val system = context.system

  val sctpio = IO(Sctp)
  sctpio ! Connect(new InetSocketAddress("127.0.0.1", port), 1024, 1024)

  def receive: Receive = {
    case Connected(remoteAddresses, localAddresses, association) =>
      println(s"worker #$id: set connection to $remoteAddresses assoc=${association.id}")
      sender ! Register(self, Some(self))
      sendNewMessage()

    case Received(message) =>
      checkIfMatches(message, lastMessage)
      sendNewMessage()

    case Ack(msg) =>
      lastMessage = msg
      println(s"worker #$id: message sent with ${msg.payload.size} bytes on stream #${msg.info.streamNumber}")

    case CommandFailed(cmd: Connect) => system.scheduler.scheduleOnce(5.seconds, sctpio, cmd)

    case n: Notification => println(n)
    case msg => println(msg)
  }

  def sendNewMessage() = {
    val msg = SctpMessage(ByteString(Random.nextString((Random.nextInt(64) + 1) * (Random.nextInt(1024) + 1))), Random.nextInt(10), 0, 0, true)
    sender ! Send(msg, Ack(msg))
  }

  type MatchResult = Either[String, (SctpMessage, SctpMessage)]
  type Matcher = MatchResult => MatchResult
  object Matcher { def apply(error: String, check: (SctpMessage, SctpMessage) => Boolean) = (in: MatchResult) => in match { case l @ Left(_) => l; case r @ Right((received, sent)) => if (check(received, sent)) r else Left(error) } }

  val payloadMatcher = Matcher("payloads doesn't match", (r: SctpMessage, s: SctpMessage) => r.payload == s.payload)
  val streamNumberMatcher = Matcher("streamNumbers doesn't match", (r: SctpMessage, s: SctpMessage) => r.info.streamNumber == s.info.streamNumber)

  def checkIfMatches(received: SctpMessage, sent: SctpMessage): Unit = {
    (Seq(payloadMatcher, streamNumberMatcher).foldLeft[MatchResult](Right((received, sent))) { (r, i) => i(r) }) match {
      case Left(error) =>
        println("worker #$id: " + error)
        self ! PoisonPill
      case r: Right[_, _] =>
    }
  }

}