package akka.io

import akka.util.ByteString

/**
 * Generic interface of messages carrying ByteString payload
 */
trait ByteStringMessage {

  def payload: ByteString
}