package me.arturopala.sctp.example

import akka.actor._
import akka.io._
import akka.util._
import java.net.InetSocketAddress
import scala.util.Random

object TestSctpClient {
  def main(args: Array[String]): Unit = {
    val initialActor = classOf[TestSctpClientActor].getName
    akka.Main.main(Array(initialActor))
  }
}

class TestSctpClientActor extends Actor {

  import Sctp._

  case class Ack(message: SctpMessage) extends Event

  println(s"trying connect ...")

  implicit val system = context.system
  IO(Sctp) ! Connect(new InetSocketAddress("127.0.0.1", 8008), 1024, 1024)

  def receive = {
    case Connected(remoteAddresses, localAddresses, association) =>
      println(s"new connection to $remoteAddresses assoc=${association.id}")
      sender ! Register(self, Some(self))
      val msg = randomMessage()
      sender ! Send(msg, Ack(msg))
    case Received(SctpMessage(SctpMessageInfo(streamNumber, payloadProtocolID, timeToLive, unordered, bytes, association, address), payload)) =>
      println(s"received $bytes bytes from $address on stream #$streamNumber with protocolID=$payloadProtocolID and TTL=$timeToLive and assoc=${association.id}")
      val msg = randomMessage()
      sender ! Send(msg, Ack(msg))
    case Ack(msg) => println(s"response sent ${msg.info}")
    case n: Notification => println(n)
    case msg => println(msg)
  }

  def randomMessage() = SctpMessage(ByteString(Random.nextString(64 * 1024)), Random.nextInt(1024), 0, 0, false)

}