package akka.util

import java.nio.{ ByteBuffer }
import java.nio.charset.{ Charset, StandardCharsets }

/**
 * Lazy, immutable byte sequence with light & fast: concat, slice, unsigned values reads.
 */
sealed trait Bytes {

  def length: Int

  def apply(n: Int): Byte
  def get(n: Int): Option[Byte]

  def slice(from: Int, to: Int): Bytes
  def take(n: Int): Bytes
  def drop(n: Int): Bytes
  def dropRight(n: Int): Bytes
  def splitAt(n: Int): (Bytes, Bytes)

  def head: Byte
  def tail: Bytes
  def acquire: (Byte, Bytes)

  def last: Byte
  def init: Bytes

  def ++(other: Bytes): Bytes
  def +:(byte: Byte): Bytes
  def :+(byte: Byte): Bytes

  def foreach[U](f: Byte => U): Unit

  def readUnsignedByte(pos: Int): Int
  def readUnsignedInt(pos: Int): Int
  def readUnsignedLong(pos: Int): Long

  def toIterator: Iterator[Byte]
  def toTraversable: Traversable[Byte]
  def toArray: Array[Byte]
  def toByteString: ByteString

  def copyToArray(array: Array[Byte], start: Int): Unit
  def copyToBuffer(buffer: ByteBuffer): Unit

  def compact: Bytes
  def size: Int

}

object Bytes {

  def apply(array: Array[Byte]): Bytes = wrap(array.clone)
  def apply(byte: Byte): Bytes = new Single(byte)
  def apply(bytes: Byte*): Bytes = wrap(bytes.toArray)
  def fromInts(bytes: Int*): Bytes = wrap(bytes.map(_.toByte).toArray)

  def apply(buffer: ByteBuffer): Bytes = {
    if (buffer.remaining < 1) Empty
    else {
      val array = new Array[Byte](buffer.remaining)
      buffer.get(array)
      Bytes(array)
    }
  }

  def apply(string: String, charset: Charset = StandardCharsets.UTF_8): Bytes = wrap(string.getBytes(charset))
  def wrap(array: Array[Byte]): Bytes = if (array.isEmpty) Empty else new Simple(array)
  def decode(bytes: String*): Bytes = wrap(bytes.map(java.lang.Integer.decode).map(_.toByte).toArray)
  def empty = Empty

  /** common operations impl */
  sealed trait Ops extends Bytes {
    this: Bytes =>

    final override def take(n: Int): Bytes = slice(0, n)
    final override def drop(n: Int): Bytes = slice(n, length)
    final override def dropRight(n: Int): Bytes = slice(0, length - n)
    final override def splitAt(n: Int): (Bytes, Bytes) = if (n < 0) (Empty, this) else if (n >= length) (this, Empty) else (slice(0, n), slice(n, length))

    final override def head: Byte = this(0)
    final override def tail: Bytes = if (length > 1) slice(1, length) else Empty
    final override def acquire: (Byte, Bytes) = (head, tail)

    final override def last: Byte = this(length - 1)
    final override def init: Bytes = if (length > 1) slice(0, length - 1) else Empty

    override def ++(other: Bytes): Bytes = Bytes.Pair(this, other)
    override def +:(byte: Byte): Bytes = Bytes.Pair(new Bytes.Single(byte), this)
    override def :+(byte: Byte): Bytes = Bytes.Pair(this, new Bytes.Single(byte))

    final override def readUnsignedByte(pos: Int): Int = Unsigned.toInt(this(pos))
    final override def readUnsignedInt(pos: Int): Int = Unsigned.toInt(this(pos), this(pos + 1))
    final override def readUnsignedLong(pos: Int): Long = Unsigned.toLong(this(pos), this(pos + 1), this(pos + 2), this(pos + 3))

    override def toString: String = {
      val sb = new StringBuilder("Bytes(")
      if (length > 0) {
        sb ++= this(0).toString
        if (length > 1) {
          for (n <- 1 until Math.min(length, 64)) {
            sb += ','
            sb ++= this(n).toString
          }
          if (length > 64) sb.append("...")
        }
      }
      sb += ')'
      sb.toString
    }

    @scala.annotation.tailrec private[this] def compare(other: Bytes, from: Int = 0): Boolean = if (from < other.length) (if (this(from) == other(from)) compare(other, from + 1) else false) else true
    override def equals(other: Any): Boolean = other.isInstanceOf[Bytes] && other.asInstanceOf[Bytes].length == length && compare(other.asInstanceOf[Bytes])

    override def toByteString: ByteString = ByteString.ByteString1C(this.toArray)
    final override def size: Int = length
  }

  /** empty Bytes representation */
  case object Empty extends Bytes with Ops {
    override val length = 0
    override def apply(n: Int): Byte = throw new ArrayIndexOutOfBoundsException
    override def get(n: Int): Option[Byte] = None
    override def slice(from: Int, to: Int): Bytes = this
    override def foreach[U](f: Byte => U): Unit = ()
    override def toIterator: Iterator[Byte] = Iterator.empty
    override def toTraversable: Traversable[Byte] = Traversable.empty
    override def toArray: Array[Byte] = Array.empty[Byte]
    override def toByteString: ByteString = ByteString.empty
    override def copyToArray(array: Array[Byte], start: Int) = ()
    override def copyToBuffer(buffer: ByteBuffer): Unit = ()
    override def equals(other: Any): Boolean = other.isInstanceOf[this.type]
    override def toString: String = "Bytes.Empty"
    override def compact: Bytes = this
  }

  /** simple array wrapper */
  final class Simple private[Bytes] (bytes: Array[Byte]) extends Bytes with Ops {
    override val length = bytes.length
    override def apply(n: Int): Byte = bytes(n)
    override def get(n: Int): Option[Byte] = if (n >= 0 && n < length) Some(bytes(n)) else None
    override def slice(from: Int, to: Int): Bytes = View(bytes, from, to - from)
    override def foreach[U](f: Byte => U): Unit = bytes.foreach(f)
    override def toIterator: Iterator[Byte] = bytes.iterator
    override def toTraversable: Traversable[Byte] = bytes.toTraversable
    override def toArray: Array[Byte] = bytes.clone
    override def toByteString: ByteString = ByteString.ByteString1C(bytes)
    override def copyToArray(array: Array[Byte], start: Int) = System.arraycopy(bytes, 0, array, start, length)
    override def copyToBuffer(buffer: ByteBuffer): Unit = buffer.put(bytes)
    override def compact: Bytes = this
  }

  object View {
    def apply(bytes: Array[Byte], offset: Int, length: Int): Bytes =
      if (bytes != null && bytes.length > 0 && offset < bytes.length && length > 0)
        new View(bytes, offset, Math.min(length, bytes.length - offset))
      else Empty
  }

  /** sliced array wrapper */
  final class View private[Bytes] (bytes: Array[Byte], offset: Int, override val length: Int) extends Bytes with Ops {
    require(offset >= 0, s"offset=$offset parameter must be >= 0")
    require(offset + length <= bytes.length, s"offset=$offset parameter must be >= 0")

    val limit = offset + length

    private[this] def crop(newOffset: Int, newLength: Int) = {
      val off = Math.max(newOffset, 0)
      val len = Math.min(newLength, limit - off)
      if (off == offset && len == length) this else View(bytes, off, len)
    }

    override def apply(n: Int): Byte = if (n >= 0 && n < length) bytes(offset + n) else throw new ArrayIndexOutOfBoundsException
    override def get(n: Int): Option[Byte] = if (n >= 0 && n < length) Some(bytes(offset + n)) else None
    override def slice(from: Int, to: Int): Bytes = crop(offset + from, to - from)
    override def foreach[U](f: Byte => U): Unit = for (i <- offset until limit) f(bytes(i))

    override def toIterator: Iterator[Byte] = new Iterator[Byte] {
      val lim = limit
      var pos = offset
      override def hasNext = pos < lim
      override def next: Byte = { val byte = bytes(pos); pos = pos + 1; byte }
    }

    override def toTraversable: Traversable[Byte] = new Traversable[Byte] {
      override def foreach[U](f: Byte => U): Unit = View.this.foreach(f)
    }

    override def toArray: Array[Byte] = {
      val array = new Array[Byte](length)
      bytes.copyToArray(array, offset, length)
      array
    }
    override def copyToBuffer(buffer: ByteBuffer): Unit = buffer.put(bytes, offset, length)
    override def copyToArray(array: Array[Byte], start: Int): Unit = System.arraycopy(bytes, offset, array, start, length)
    override def compact: Bytes = new Simple(toArray)
  }

  object Pair {
    def apply(left: Bytes, right: Bytes): Bytes = if (left == Empty) right else if (right == Empty) left else new Pair(left, right)
  }

  /** two Bytes concatenation */
  final class Pair private[Bytes] (left: Bytes, right: Bytes) extends Bytes with Ops {

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

    override def foreach[U](f: Byte => U): Unit = {
      left.foreach(f)
      right.foreach(f)
    }

    override def toIterator: Iterator[Byte] = new Iterator[Byte] {
      var iterator = left.toIterator
      var isLeft = true
      override def hasNext = {
        val has = iterator.hasNext
        if (has) has else if (isLeft) { iterator = right.toIterator; isLeft = false; hasNext } else false
      }
      override def next: Byte = iterator.next
    }

    override def toTraversable: Traversable[Byte] = new Traversable[Byte] {
      override def foreach[U](f: Byte => U): Unit = Pair.this.foreach(f)
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

    override def copyToBuffer(buffer: ByteBuffer): Unit = {
      left.copyToBuffer(buffer)
      right.copyToBuffer(buffer)
    }

    override def compact: Bytes = new Simple(toArray)
  }

  /** single byte wrapper */
  final class Single private[Bytes] (byte: Byte) extends Bytes with Ops {
    override val length = 1
    override def apply(n: Int): Byte = if (n == 0) byte else throw new ArrayIndexOutOfBoundsException
    override def get(n: Int): Option[Byte] = if (n == 0) Some(byte) else None
    override def slice(from: Int, to: Int): Bytes = if (from > 0 || to < 1) Empty else this
    override def foreach[U](f: Byte => U): Unit = f(byte)
    override def toIterator: Iterator[Byte] = Iterator.single(byte)
    override def toTraversable: Traversable[Byte] = new Traversable[Byte] {
      override def foreach[U](f: Byte => U): Unit = f(byte)
    }
    override def toArray: Array[Byte] = Array(byte)
    override def copyToArray(array: Array[Byte], start: Int) = array(start) = byte
    override def copyToBuffer(buffer: ByteBuffer): Unit = buffer.put(byte)
    override def compact: Bytes = this
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
