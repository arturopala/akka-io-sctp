# akka-io-sctp
[Akka I/O](http://doc.akka.io/docs/akka/snapshot/scala/io.html) driver for [SCTP](http://en.wikipedia.org/wiki/Stream_Control_Transmission_Protocol) protocol based on [Oracle JDK 7/8 support](http://www.oracle.com/technetwork/articles/javase/index-139946.html).

## About SCTP - Stream Control Transmission Protocol
   
   SCTP is designed to transport PSTN signaling messages over IP networks, but is capable of broader applications.

   SCTP is a reliable transport protocol operating on top of a connectionless packet network such as IP.  It offers the following services to its users:

-   acknowledged error-free non-duplicated transfer of user data,
-   data fragmentation to conform to discovered path MTU size,
-   sequenced delivery of user messages within multiple streams, with an option for order-of-arrival delivery of individual user messages,
-   optional bundling of multiple user messages into a single SCTP packet, and
-   network-level fault tolerance through supporting of multihoming at either or both ends of an association.

   The design of SCTP includes appropriate congestion avoidance behavior and resistance to flooding and masquerade attacks.

### Specs

-   [RFC 2960](http://www.ietf.org/rfc/rfc2960.txt) Stream Control Transmission Protocol.
-   [RFC 3257](http://www.ietf.org/rfc/rfc3257.txt) Stream Control Transmission Protocol Applicability Statement.
-   [RFC 3286](http://www.ietf.org/rfc/rfc3286.txt) An Introduction to the Stream Control Transmission Protocol (SCTP).

## Prerequisites

-   Oracle JDK >= 7
-   Scala 2.11.x
-   Akka 2.4-SNAPSHOT

## Installation

Add to ```build.sbt``` file:

    resolvers += Resolver.jcenterRepo
    libraryDependencies ++= Seq("me.arturopala" %% "akka-io-sctp" % "0.1")

## Usage

SCTP driver message flow follows existing TCP/UDP convention (Bind->Bound, Connected->Register, Received, Send)

#### Example echo server:

```scala
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

	object Ack extends Event

	implicit val system = context.system
	IO(Sctp) ! Bind(self, new InetSocketAddress(8008))

	def receive = {
		case Bound(localAddresses, port) => println(s"SCTP server bound to $localAddresses")
		case Connected(remoteAddresses, localAddresses, association) => sender ! Register(self)
		case Received(SctpMessage(payload,SctpMessageInfo(streamNumber, bytes, payloadProtocolID, timeToLive, association, address))) => 
			println(s"received $bytes bytes from $address on stream #$streamNumber with protocolID=$payloadProtocolID and TTL=$timeToLive")
			sender ! Send(SctpMessage(payload, streamNumber, payloadProtocolID, timeToLive), Ack)
	    case Ack => println("response sent.")
		case msg => println(msg)
	}

}
```
