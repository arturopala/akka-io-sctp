# akka-io-sctp

[![Build Status](https://semaphoreci.com/api/v1/projects/76e5852b-4c4c-444b-81db-ccc2ac3fc83a/423391/badge.svg)](https://semaphoreci.com/arturopala/akka-io-sctp)

[Akka I/O](http://doc.akka.io/docs/akka/snapshot/scala/io.html) driver for [SCTP](http://en.wikipedia.org/wiki/Stream_Control_Transmission_Protocol) protocol based on [Oracle JDK 7/8 SCTP support](http://www.oracle.com/technetwork/articles/javase/index-139946.html).

This driver has been derived from an original [Akka I/O TCP](https://github.com/akka/akka/tree/master/akka-actor/src/main/scala/akka/io) driver.

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
-   **`Bind`** → *`Bound`*: server socket binding
-   *`Connected`* → **`Register`** : incoming connection acceptance
-   {*`Received`*, **`Send`** [→ *`Ack`*]} : sctp messages exchange
-   [`Shutdown` → *`ConfirmedClosed`* | `Close` → *`Closed`* | `Abort` → *`Aborted`*] : incoming connection closing
-   *`PeerClosed`* : incoming connection closed by peer side
-   *`ErrorClosed`* : incoming connection closed because of error
-   **`Unbind`** → *`Unbound`* : server socket unbinding
-   *`CommadFailed`* : command cannot be processed
-   `BindAddress`, `UnbindAddress` : association local and peer addresses change

##### sctp client commands and *events* flow:
-   **`Connect`** → *`Connected`* → **`Register`** : outgoing connection setup
-   {**`Send`** [→ *`Ack`*], *`Received`*} : sctp messages exchange
-   [`Shutdown` → *`ConfirmedClosed`* | `Close` → *`Closed`* | `Abort` → *`Aborted`*] : outgoing connection closing
-   *`PeerClosed`* : outgoing connection closed by peer side
-   *`ErrorClosed`* : outgoing connection closed because of error
-   *`CommadFailed`* : command cannot be processed

#### Messages

##### Bind
The `Bind` command message is send to the SCTP manager actor in order to bind to a listening socket. The manager replies either with a `CommandFailed` or the actor handling the listen socket replies with a `Bound` event message. If the local port is set to 0 in the `Bind` message, then the `Bound` message should be inspected to find
the actual port which was bound to.
```scala
case class Bind(
      handler: ActorRef, //handler actor which will receive Bound and Connected events
      localAddress: InetSocketAddress, //local socket port
      maxInboundStreams: Int = 0, //max number of incoming streams (later negotiated with client)
      maxOutboundStreams: Int = 0, //max number of outgoing streams (later negotiated with client)
      additionalAddresses: Set[InetAddress] = Set.empty, //additional local home addresses (port stays the same)
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
##### Connected
The connection actor sends this event message either to the sender of a `Connect` command (for outbound) or to the handler for incoming connections designated in `Bind` message.
```scala
case class Connected(
    remoteAddresses: Set[InetSocketAddress], //remote peer addresses
    localAddresses: Set[InetSocketAddress], //local (this side) addresses, same as in Bound event
    association: SctpAssociation) //sctp association
```
##### Register
This message must be sent to a SCTP connection actor after receiving the `Connected` message. The connection will not read any data from the socket until this message is received, because this message defines the actor which will receive all inbound data.
```scala
case class Register(
    handler: ActorRef, //actor which will receive all further messages
    notificationHandlerOpt: Option[ActorRef] = None) //optional actor which will receive association notifications
```
##### Connect
The Connect message is sent to the SCTP manager actor. Either the manager replies with a `CommandFailed` or the actor handling the new connection replies with a `Connected` message.
```scala
case class Connect(
      remoteAddress: InetSocketAddress, //remote socket address
      maxOutboundStreams: Int = 0, //max number of outgoing streams (negotiated with server)
      maxInboundStreams: Int = 0, //max number of incoming streams (negotiated with server)
      localAddress: Option[InetSocketAddress] = None, //optional local socket port
      additionalAddresses: Set[InetAddress] = Set.empty, //additional local home addresses (port stays the same)
      options: immutable.Traversable[SctpSocketOption] = Nil, //sctp connection options
      timeout: Option[FiniteDuration] = None) //connection timeout
```
##### Received
Whenever SCTP message is read from a socket it will be transferred within this class to the handler actor which was designated in the `Register` message.
```scala
case class Received(message: SctpMessage)

case class SctpMessage(info: SctpMessageInfo, payload: ByteString)
case class SctpMessageInfo(streamNumber: Int, payloadProtocolID: Int, timeToLive: Long, unordered: Boolean, bytes: Int, association: SctpAssociation, address: InetSocketAddress)
case class SctpAssociation(id: Int, maxInboundStreams: Int, maxOutboundStreams: Int)
```
##### Send
Sends data to the SCTP connection. If no ack is needed use the special `NoAck` object. The connection actor will reply with a `CommandFailed` message if the write could not be enqueued. The connection actor will reply with the supplied `ack` token once the write has been successfully enqueued to the O/S kernel.
**Note that this does not in any way guarantee that the data will be or have been sent!** 
Unfortunately there is no way to determine whether a particular write has been sent by the O/S.
```scala
case class Send(message: SctpMessage, ack: Event = NoAck)
```
##### Shutdown
Sends a shutdown command to the remote peer, effectively preventing any new data from being written to the socket by either peer. The channel remains open to allow the for any data (and notifications) to be received that may have been sent by the peer before it received the shutdown command. The sender of this command and the registered handler for incoming data will both be notified once the socket is closed using a `ConfirmedClosed` message.
```scala
case object Shutdown
case object ConfirmedClosed extends ConnectionClosed
```
##### Close
A normal close operation will first flush pending writes and then close the socket. The sender of this command and the registered handler for incoming data will both be notified once the socket is closed using a `Closed` message.
```scala
case object Close
case object Closed extends ConnectionClosed
```
##### Abort
An abort operation will not flush pending writes and will issue a SCTP ABORT command to the peer. The sender of this command and the registered handler for incoming data will both be notified once the socket is closed using a `Aborted` message.
```scala
case object Abort
case object Aborted extends ConnectionClosed
```
##### Unbind
In order to close down a listening socket, send this message to that socket’s actor (that is the actor which previously had sent the `Bound` message). The listener socket actor will reply with a `Unbound` message.
```scala
case object Unbind
case object Unbound
```
##### CommandFailed
Whenever a command cannot be completed, the queried actor will reply with this message, wrapping the original command which failed.
```scala
case class CommandFailed(cmd: Command)
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
