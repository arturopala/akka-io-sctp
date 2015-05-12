# akka-io-sctp
[Akka I/O](http://doc.akka.io/docs/akka/snapshot/scala/io.html) driver for [SCTP](http://en.wikipedia.org/wiki/Stream_Control_Transmission_Protocol) protocol based on [Oracle JDK 7/8 SCTP support](http://www.oracle.com/technetwork/articles/javase/index-139946.html).

## About SCTP - Stream Control Transmission Protocol

The Stream Control Transmission Protocol (SCTP) is a IP transport protocol, existing at an equivalent level with UDP (User Datagram Protocol) and TCP (Transmission Control Protocol), which provide transport layer functions to  Internet applications.

Like TCP, SCTP provides a reliable transport service, ensuring that data is transported across the network without error and in sequence. 

Like TCP, SCTP is a session-oriented mechanism, meaning that a relationship is created between the endpoints of an SCTP association prior to data being transmitted, and this relationship is maintained until all data transmission has been successfully completed.

Unlike TCP, SCTP provides a number of functions that are critical for telephony signaling transport, and at the same time can potentially benefit other applications needing transport with additional performance and reliability.

The name Stream Control Transmission Protocol is derived from the multi-streaming function provided by SCTP.  This feature allows data to be partitioned into multiple streams that have the property of independently sequenced delivery, so that message loss in any one stream will only initially affect delivery within that stream, and not delivery in other streams.

SCTP offers the following services to its users:

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

SCTP driver message flow follows an existing TCP/UDP convention (Bind->Bound, Connected->Register, Received, Send)

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
