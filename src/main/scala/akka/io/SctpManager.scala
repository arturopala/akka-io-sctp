package akka.io

import Sctp._
import akka.actor.{ ActorLogging, Props }

private[io] class SctpManager(sctp: SctpExt)
  extends SelectionHandler.SelectorBasedManager(sctp.Settings, sctp.Settings.NrOfSelectors) with ActorLogging {

  def receive = workerForCommandHandler {
    case c: Connect ⇒
      val commander = sender() // cache because we create a function that will run asyncly
      (registry ⇒ Props(classOf[SctpOutgoingConnection], sctp, registry, commander, c))

    case b: Bind ⇒
      val commander = sender() // cache because we create a function that will run asyncly
      (registry ⇒ Props(classOf[SctpListener], selectorPool, sctp, registry, commander, b))
  }

}
