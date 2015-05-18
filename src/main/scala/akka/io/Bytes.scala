package akka.io

import java.nio.ByteBuffer

sealed trait Bytes {

  // abstract members
  def length: Int
  def apply(n: Int): Byte
  def get(n: Int): Option[Byte]
  def toArray: Array[Byte]
  def copyToArray(array: Array[Byte], start: Int): Unit
  def slice(from: Int, to: Int): Bytes

  // concrete implementations
  final def take(n: Int): Bytes = slice(0, n)
  final def drop(n: Int): Bytes = slice(n, length)
  def ++(other: Bytes): Bytes = Bytes.Pair(this, other)

  final def readUnsignedByte(n: Int): Int = Unsigned.toInt(this(n))
  final def readUnsignedInt(n: Int): Int = Unsigned.toInt(this(n), this(n + 1))
  final def readUnsignedLong(n: Int): Long = Unsigned.toLong(this(n), this(n + 1), this(n + 2), this(n + 3))

}

object Bytes {

  def apply(array: Array[Byte]): Bytes = wrap(array.clone)

  def apply[T: Numeric](bytes: T*): Bytes = {
    val num = implicitly[Numeric[T]]
    wrap(bytes.map(num.toInt(_).toByte).toArray)
  }

  def apply(buffer: ByteBuffer): Bytes = {
    if (buffer.remaining < 1) Empty
    else {
      val array = new Array[Byte](buffer.remaining)
      buffer.get(array)
      Bytes(array)
    }
  }

  def wrap(array: Array[Byte]): Bytes = {
    if (array.isEmpty) Empty
    else new ArrayView(array, 0, array.length)
  }

  def decode(bytes: String*): Bytes = wrap(bytes.map(java.lang.Integer.decode).map(_.toByte).toArray)

  case object Empty extends Bytes {
    override val length = 0
    override def apply(n: Int): Byte = throw new ArrayIndexOutOfBoundsException
    override def get(n: Int): Option[Byte] = None
    override def slice(from: Int, to: Int): Bytes = this
    override def toArray: Array[Byte] = Array.empty[Byte]
    override def copyToArray(array: Array[Byte], start: Int) = ()
    override def equals(other: Any): Boolean = other.isInstanceOf[this.type]
    override def toString: String = "Bytes.Empty"
  }

  final class ArrayView private[Bytes] (bytes: Array[Byte], offset: Int, override val length: Int) extends Bytes {
    require(offset >= 0, s"offset=$offset parameter must be >= 0")
    require(offset + length <= bytes.length, s"offset=$offset parameter must be >= 0")

    val limit = offset + length

    private[this] def view(newOffset: Int, newLength: Int) = {
      val off = Math.max(newOffset, 0)
      if (newLength > 0 && newOffset < limit)
        new ArrayView(bytes, off, Math.min(newLength, limit - off))
      else Empty
    }

    override def apply(n: Int): Byte = if (n >= 0 && n < length) bytes(offset + n) else throw new ArrayIndexOutOfBoundsException
    override def get(n: Int): Option[Byte] = if (n >= 0 && n < length) Some(bytes(n)) else None
    override def slice(from: Int, to: Int): Bytes = view(offset + from, to - from)

    override def toArray: Array[Byte] = {
      val array = new Array[Byte](length)
      bytes.copyToArray(array, offset, length)
      array
    }
    override def copyToArray(array: Array[Byte], start: Int): Unit = System.arraycopy(bytes, offset, array, start, length)

    @scala.annotation.tailrec private[this] def compare(other: Bytes, from: Int = 0): Boolean = if (from < other.length) (if (this(from) == other(from)) compare(other, from + 1) else false) else true
    override def equals(other: Any): Boolean = other.isInstanceOf[Bytes] && other.asInstanceOf[Bytes].length == length && compare(other.asInstanceOf[Bytes])

    override def toString: String = {
      val sb = new StringBuilder("Bytes(")
      if (length > 0) {
        sb ++= bytes(offset).toString
        if (length > 1) {
          for (n <- 1 until Math.min(length, 64)) {
            sb += ','
            sb ++= bytes(offset + n).toString
          }
          if (length > 64) sb.append("...")
        }
      }
      sb += ')'
      sb.toString
    }
  }

  object Pair {
    def apply(left: Bytes, right: Bytes): Bytes = if (left == Empty) right else if (right == Empty) Empty else new Pair(left, right)
  }

  final class Pair(left: Bytes, right: Bytes) extends Bytes {

    override val length: Int = left.length + right.length
    override def apply(n: Int): Byte = if (n < left.length) left(n) else right(n - left.length)
    override def get(n: Int): Option[Byte] = if (n < left.length) left.get(n) else right.get(n - left.length)
    override def slice(from: Int, to: Int): Bytes = {
      require(to >= from, s"to=$to parameter MUST be greater or equal to from=$from parameter")
      if (to <= left.length) left.slice(from, to)
      else if (from >= left.length) right.slice(from - left.length, to - left.length)
      else {
        val break = Math.min(to, left.length)
        Pair(left.slice(from, break), right.slice(0, to - break))
      }
    }

    override def toArray: Array[Byte] = {
      val array = new Array[Byte](length)
      left.copyToArray(array, 0)
      right.copyToArray(array, left.length)
      array
    }

    override def copyToArray(array: Array[Byte], start: Int): Unit = {
      left.copyToArray(array, start)
      right.copyToArray(array, start + left.length)
    }
  }

}

object Unsigned {

  def toInt(byte: Byte): Int = java.lang.Byte.toUnsignedInt(byte)
  def toInt(byte1: Byte, byte2: Byte): Int = (java.lang.Byte.toUnsignedInt(byte1) << 8) + java.lang.Byte.toUnsignedInt(byte2)
  def toLong(byte1: Byte, byte2: Byte, byte3: Byte, byte4: Byte): Long = (java.lang.Byte.toUnsignedLong(byte1) << 24) + (java.lang.Byte.toUnsignedLong(byte2) << 16) + (java.lang.Byte.toUnsignedInt(byte3) << 8) + java.lang.Byte.toUnsignedInt(byte4)
  def toBytes(int: Int): Bytes = Bytes((int >> 8).toByte, int.toByte)
  def toBytes(long: Long): Bytes = Bytes((long >> 24).toByte, (long >> 16).toByte, (long >> 8).toByte, long.toByte)
  def parse(s: String): Byte = java.lang.Short.parseShort(s, 2).toByte
}
