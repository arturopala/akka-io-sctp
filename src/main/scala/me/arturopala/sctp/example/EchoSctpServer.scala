package me.arturopala.sctp.example

import akka.actor._
import akka.io._
import java.net.InetSocketAddress

object EchoSctpServer {
  def main(args: Array[String]): Unit = {
    val initialActor = classOf[EchoSctpServerActor].getName
    akka.Main.main(Array(initialActor))
  }
}

class EchoSctpServerActor extends Actor {

  import Sctp._

  case class Ack(message: SctpMessage) extends Event

  implicit val system = context.system
  IO(Sctp) ! Bind(self, new InetSocketAddress(8008), 1024, 1024)

  def receive = {
    case Bound(localAddresses, port) => println(s"SCTP server bound to $localAddresses")
    case Connected(remoteAddresses, localAddresses, association) =>
      println(s"new connection accepted from $remoteAddresses assoc=${association.id}")
      sender ! Register(self, Some(self))
    case Received(SctpMessage(SctpMessageInfo(streamNumber, payloadProtocolID, timeToLive, unordered, bytes, association, address), payload)) =>
      println(s"received $bytes bytes from $address on stream #$streamNumber with protocolID=$payloadProtocolID and TTL=$timeToLive and assoc=${association.id}")
      val msg = SctpMessage(payload, streamNumber, payloadProtocolID, timeToLive, unordered)
      sender ! Send(msg, Ack(msg))
    case Ack(msg) =>
      println(s"message sent back")
    case n: Notification => println(n)
    case msg => println(msg)
  }

}