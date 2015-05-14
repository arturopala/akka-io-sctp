# akka-io-sctp

[![Build Status](https://semaphoreci.com/api/v1/projects/76e5852b-4c4c-444b-81db-ccc2ac3fc83a/423391/badge.svg)](https://semaphoreci.com/arturopala/akka-io-sctp)

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

-   [RFC 4960](http://www.ietf.org/rfc/rfc4960.txt) Stream Control Transmission Protocol.
-   [RFC 3257](http://www.ietf.org/rfc/rfc3257.txt) Stream Control Transmission Protocol Applicability Statement.
-   [RFC 3286](http://www.ietf.org/rfc/rfc3286.txt) An Introduction to the Stream Control Transmission Protocol (SCTP).

## Prerequisites

-   Oracle JDK >= 7
-   [lksctp-tools](http://lksctp.sourceforge.net/) for Linux
-   Scala 2.11.x
-   Akka 2.4-SNAPSHOT

## Installation

Add to ```build.sbt``` file:

    resolvers += Resolver.jcenterRepo
    libraryDependencies ++= Seq("me.arturopala" %% "akka-io-sctp" % "0.5")

## Usage

SCTP driver messages follows an existing Akka I/O TCP/UDP convention:

##### sctp server commands and *events* flow:
-   **Bind** -> *Bound*: server socket binding
-   *Connected* -> **Register** : incoming connection acceptance
-   {*Received*, **Send** [-> *Ack*]} : sctp messages exchange
-   [Shutdown -> *ConfirmedClosed* | Close -> *Closed* | Abort -> *Aborted*] : incoming connection closing
-   *PeerClosed* : incoming connection closed by peer side
-   *ErrorClosed* : incoming connection closed because of error
-   **Unbind** -> *Unbound* : server socket unbinding
-   *CommadFailed* : command cannot be processed
-   BindAddress, UnbindAddress : association local and peer addresses change

##### sctp client commands and *events* flow:
-   **Connect** -> *Connected* -> **Register** : outgoing connection setup
-   {**Send** [-> *Ack*], *Received*} : sctp messages exchange
-   [Shutdown -> *ConfirmedClosed* | Close -> *Closed* | Abort -> *Aborted*] : outgoing connection closing
-   *PeerClosed* : outgoing connection closed by peer side
-   *ErrorClosed* : outgoing connection closed because of error
-   *CommadFailed* : command cannot be processed

#### Messages

##### Bind
The `Bind` command message is send to the SCTP manager actor in order to bind to a listening socket. The manager replies either with a `CommandFailed` or the actor handling the listen socket replies with a `Bound` event message. If the local port is set to 0 in the `Bind` message, then the `Bound` message should be inspected to find
the actual port which was bound to.
```scala
case class Bind(
      handler: ActorRef, //handler actor will receive Bound and Connected events
      localAddress: InetSocketAddress, //local address and port where to bind server socket
      maxInboundStreams: Int = 0, //max number of incoming streams (later negotiated with client)
      maxOutboundStreams: Int = 0, //max number of outgoing streams (later negotiated with client)
      additionalAddresses: Set[InetAddress] = Set.empty, //additional home addresses (port stays the same)
      backlog: Int = 100, //number of unaccepted connections the O/S kernel will hold for this port before refusing connections
      options: immutable.Traversable[SctpSocketOption] = Nil) //sctp connection options
```
Example: 
```scala 
IO(Sctp) ! Bind(self, new InetSocketAddress(8008), 1024, 1024)
```
##### Bound
The sender of a `Bind` command will—in case of success—receive confirmation in this form. If the bind address indicated a 0 port number, then the contained `port` holds which port was automatically assigned.

```scala
case class Bound(localAddresses: Set[InetSocketAddress], port: Int)
```

### Examples 

##### Echo server:

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
```
