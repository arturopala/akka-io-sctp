# akka-io-sctp
[Akka I/O](http://doc.akka.io/docs/akka/snapshot/scala/io.html) driver for [SCTP](http://en.wikipedia.org/wiki/Stream_Control_Transmission_Protocol) protocol based on [Oracle JDK 7/8 support](http://www.oracle.com/technetwork/articles/javase/index-139946.html).

## Installation

Add to ```build.sbt``` file:

    resolvers += Resolver.jcenterRepo
    libraryDependencies ++= Seq("me.arturopala" %% "akka-io-sctp" % "0.1")

## Usage

SCTP driver message flow follows existing TCP/UDP convention (Bind->Bound, Connected->Register, Received, Send)

#### Example echo server:

```

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
