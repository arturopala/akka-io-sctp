package akka.util

import org.junit.runner.RunWith
import org.scalatest.{ WordSpecLike, Matchers }
import org.scalatest.prop.PropertyChecks
import org.scalatest.junit.JUnitRunner
import org.scalacheck._
import scala.annotation.tailrec
import java.nio.ByteBuffer

@RunWith(classOf[JUnitRunner])
class BytesSpec extends WordSpecLike with Matchers with PropertyChecks {

  val byteArrayGenerator = Gen.nonEmptyContainerOf[Array, Byte](Arbitrary.arbitrary[Byte])
  implicit override val generatorDrivenConfig = PropertyCheckConfig(minSize = 0, maxSize = 1024, minSuccessful = 50, workers = 5)

  "Bytes" should {

    "wrap an array of bytes and stay immutable" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val bytes = Bytes(array)
          bytes.length should be(array.length)
          bytes.toArray should contain theSameElementsInOrderAs array
          for (i <- 0 until array.length) {
            array(i) = (array(i) + 1).toByte
            bytes(i) should not be (array(i))
          }
      }
    }

    "wrap an mutable array of bytes" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val bytes = Bytes.wrap(array)
          bytes.length should be(array.length)
          bytes.toArray should contain theSameElementsInOrderAs array
          for (i <- 0 until array.length) {
            array(i) = (array(i) + 1).toByte
            bytes(i) should be(array(i))
          }
      }
    }

    "wrap sequence of bytes" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val bytes = Bytes.fromInts(array.map(_.toInt): _*)
          bytes.length should be(array.length)
          bytes.toArray should contain theSameElementsInOrderAs array
      }
    }

    "wrap sequence of ints" in {
      forAll {
        (array: Array[Int]) =>
          val bytes = Bytes.fromInts(array: _*)
          bytes.length should be(array.length)
          bytes.toArray should contain theSameElementsInOrderAs (array.map(_.toByte))
      }
      val b = Bytes.fromInts(0x00, 0xFF, 0x01, 0x10, 0x0A)
      b.toArray should contain theSameElementsInOrderAs (Array(0, -1, 1, 16, 10).map(_.toByte))
    }

    "wrap a single byte" in {
      forAll {
        (byte: Byte) =>
          val bytes = Bytes(byte)
          bytes.length should be(1)
          bytes.head should be(byte)
          bytes.tail should be(Bytes.Empty)
          bytes(0) should be(byte)
      }
    }

    "get byte by index or throw ArrayIndexOutOfBoundsException" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val bytes = Bytes(array)
          for (i <- 0 until array.length / 2) {
            bytes(i) should be(array(i))
          }
          intercept[ArrayIndexOutOfBoundsException] {
            bytes(-1)
          }
          intercept[ArrayIndexOutOfBoundsException] {
            bytes(array.length)
          }
          intercept[ArrayIndexOutOfBoundsException] {
            bytes(array.length + 1)
          }
          if (bytes.length > 2) {
            val bytes2 = bytes.slice(0, bytes.length / 2)
            intercept[ArrayIndexOutOfBoundsException] {
              bytes2(-1)
            }
            intercept[ArrayIndexOutOfBoundsException] {
              bytes2(bytes2.length)
            }
            intercept[ArrayIndexOutOfBoundsException] {
              bytes2(bytes2.length + 1)
            }
          }
      }
    }

    "get Some byte by index or return None" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val bytes = Bytes(array)
          for (i <- 0 until array.length) {
            bytes.get(i) should be(Some(array(i)))
          }
          bytes.get(-1) should be(None)
          bytes.get(array.length) should be(None)
          bytes.get(array.length + 1) should be(None)
      }
    }

    "slice an empty part" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val bytes = Bytes(array)
          for (n <- 0 until array.length) {
            val sliced = bytes.slice(n, n)
            sliced should be(Bytes.Empty)
          }
      }
    }

    "slice a part" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val bytes = Bytes(array)
          if (array.length % 2 == 0) {
            val len = array.length / 2
            for (n <- 0 until len) {
              val sliced = bytes.slice(n, n + len)
              sliced.length should be(len)
              for (i <- 0 until len) {
                sliced(i) should be(bytes(n + i))
              }
              if (n > 1 && n % 2 == 0) {
                val len2 = sliced.length / 2
                for (m <- 0 until len2) {
                  val sliced2 = sliced.slice(m, m + len2)
                  sliced2.length should be(len2)
                  for (j <- 0 until len2) {
                    sliced2(j) should be(sliced(m + j))
                  }
                }
              }
            }
          }
      }
    }

    "split and concatenate" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val (a1, a2) = array.splitAt(scala.util.Random.nextInt(array.length))
          val (a3, a4) = a2.splitAt(scala.util.Random.nextInt(a2.length))
          val bytes1 = Bytes.empty ++ Bytes(a1)
          val bytes3 = Bytes(a3) ++ Bytes.empty
          val bytes4 = Bytes(a4)
          val (bytes5, bytes6) = bytes4.splitAt(scala.util.Random.nextInt(bytes4.length))
          val bytes7 = if (bytes5.length > 1) {
            val (head, tail) = bytes5.acquire
            head +: tail
          } else bytes5
          val bytes2 = bytes3 ++ Bytes.empty ++ Bytes.empty ++ bytes7 ++ bytes6
          val bytes = bytes1 ++ Bytes.empty ++ bytes2 ++ Bytes.empty
          bytes.length should be(array.length)
          bytes.toArray should contain theSameElementsInOrderAs array
          for (i <- 0 until array.length) {
            bytes(i) should be(array(i))
          }
          if (array.length % 2 == 0) {
            val len = array.length / 2
            for (n <- 0 until len) {
              val sliced = bytes.slice(n, n + len)
              sliced.length should be(len)
              for (i <- 0 until len) {
                sliced(i) should be(bytes(n + i))
              }
              if (n > 1 && n % 2 == 0) {
                val len2 = sliced.length / 2
                for (m <- 0 until len2) {
                  val sliced2 = sliced.slice(m, m + len2)
                  sliced2.length should be(len2)
                  for (j <- 0 until len2) {
                    sliced2(j) should be(sliced(m + j))
                  }
                }
              }
            }
          }
      }
    }

    "append single byte" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          whenever(array.length > 0) {
            val bytes = Bytes(array) :+ array(0)
            bytes.length should be(array.length + 1)
            for (i <- 0 until array.length) {
              bytes(i) should be(array(i))
            }
            bytes.last should be(array(0))
          }
      }
    }

    "append bytes" in {
      val bytes = Bytes.empty :+ 0xFF.toByte :+ 0x01.toByte :+ 0xA0.toByte
      bytes.length should be(3)
      bytes(0) should be(0xFF.toByte)
      bytes(1) should be(0x01.toByte)
      bytes(2) should be(0xA0.toByte)
    }

    "prepend single byte" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          whenever(array.length > 0) {
            val bytes = array(array.length - 1) +: Bytes(array)
            bytes.length should be(array.length + 1)
            for (i <- 0 until array.length) {
              bytes(i + 1) should be(array(i))
            }
            bytes.head should be(array(array.length - 1))
          }
      }
    }

    "iterate over content using foreach" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val (a1, a2) = array.splitAt(scala.util.Random.nextInt(array.length))
          val (a3, a4) = a2.splitAt(scala.util.Random.nextInt(a2.length))
          val bytes1 = Bytes.empty ++ Bytes(a1)
          val bytes3 = Bytes(a3) ++ Bytes.empty
          val bytes4 = Bytes(a4)
          val (bytes5, bytes6) = bytes4.splitAt(scala.util.Random.nextInt(bytes4.length))
          val bytes2 = bytes3 ++ Bytes.empty ++ Bytes.empty ++ bytes5 ++ bytes6
          val bytes = bytes1 ++ Bytes.empty ++ bytes2 ++ Bytes.empty
          bytes.length should be(array.length)
          for (b <- bytes) {
            array should contain(b)
          }
      }
    }

    "iterate over content using toIterator" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val (a1, a2) = array.splitAt(scala.util.Random.nextInt(array.length))
          val (a3, a4) = a2.splitAt(scala.util.Random.nextInt(a2.length))
          val bytes1 = Bytes.empty ++ Bytes(a1)
          val bytes3 = Bytes(a3) ++ Bytes.empty
          val bytes4 = Bytes(a4)
          val (bytes5, bytes6) = bytes4.splitAt(scala.util.Random.nextInt(bytes4.length))
          val bytes2 = bytes3 ++ Bytes.empty ++ Bytes.empty ++ bytes5 ++ bytes6
          val bytes = bytes1 ++ Bytes.empty ++ bytes2 ++ Bytes.empty
          bytes.length should be(array.length)
          val iterator = bytes.toIterator
          iterator.sameElements(array.iterator) should be(true)
      }
    }

    "iterate over content using toTraversable" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val (a1, a2) = array.splitAt(scala.util.Random.nextInt(array.length))
          val (a3, a4) = a2.splitAt(scala.util.Random.nextInt(a2.length))
          val bytes1 = Bytes.empty ++ Bytes(a1)
          val bytes3 = Bytes(a3) ++ Bytes.empty
          val bytes4 = Bytes(a4)
          val (bytes5, bytes6) = bytes4.splitAt(scala.util.Random.nextInt(bytes4.length))
          val bytes2 = bytes3 ++ Bytes.empty ++ Bytes.empty ++ bytes5 ++ bytes6
          val bytes = bytes1 ++ Bytes.empty ++ bytes2 ++ Bytes.empty
          bytes.length should be(array.length)
          val traversable = bytes.toTraversable
          traversable.toSeq should contain theSameElementsInOrderAs array
      }
    }

    "copy content to an existing array" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val (a1, a2) = array.splitAt(scala.util.Random.nextInt(array.length))
          val (a3, a4) = a2.splitAt(scala.util.Random.nextInt(a2.length))
          val bytes1 = Bytes.empty ++ Bytes(a1)
          val bytes3 = Bytes(a3) ++ Bytes.empty
          val bytes4 = Bytes(a4)
          val (bytes5, bytes6) = bytes4.splitAt(scala.util.Random.nextInt(bytes4.length))
          val bytes2 = bytes3 ++ Bytes.empty ++ Bytes.empty ++ bytes5 ++ bytes6
          val bytes = bytes1 ++ Bytes.empty ++ bytes2 ++ Bytes.empty
          bytes.length should be(array.length)
          val destination = new Array[Byte](array.length)
          bytes.copyToArray(destination, 0)
          destination should contain theSameElementsInOrderAs array
          for (i <- 0 until array.length) {
            destination(i) should be(array(i))
          }
      }
    }

    "copy content to an existing byte buffer" in {
      forAll(byteArrayGenerator) {
        (array: Array[Byte]) =>
          val (a1, a2) = array.splitAt(scala.util.Random.nextInt(array.length))
          val (a3, a4) = a2.splitAt(scala.util.Random.nextInt(a2.length))
          val bytes1 = Bytes.empty ++ Bytes(a1)
          val bytes3 = Bytes(a3) ++ Bytes.empty
          val bytes4 = Bytes(a4)
          val (bytes5, bytes6) = bytes4.splitAt(scala.util.Random.nextInt(bytes4.length))
          val bytes2 = bytes3 ++ Bytes.empty ++ Bytes.empty ++ bytes4
          val bytes = bytes1 ++ Bytes.empty ++ bytes2 ++ Bytes.empty
          bytes.length should be(array.length)
          val buffer = ByteBuffer.allocateDirect(array.length)
          bytes.copyToBuffer(buffer)
          buffer.flip()
          buffer.limit should be(array.length)
          for (i <- 0 until array.length) {
            buffer.get should be(array(i))
          }
      }
    }

    "read unsigned numbers" in {
      val bytes = Bytes.decode("0xFF", "0x00", "0x01", "0xF0", "0x0A")
      bytes.readUnsignedByte(0) should be(255)
      bytes.readUnsignedByte(1) should be(0)
      bytes.readUnsignedByte(2) should be(1)
      bytes.readUnsignedByte(3) should be(240)
      bytes.readUnsignedByte(4) should be(10)
      bytes.readUnsignedInt(0) should be(65280)
      bytes.readUnsignedLong(0) should be((255L << 24) + (0L << 16) + (1L << 8) + (240L))
      bytes.readUnsignedInt(1) should be(1)
      bytes.readUnsignedLong(1) should be((0L << 24) + (1L << 16) + (240L << 8) + (10L))
      bytes.readUnsignedInt(2) should be((1 << 8) + 240)
      bytes.readUnsignedInt(2) should be((1 << 8) + 240)
    }
  }

  "Unsigned" should {

    "read unsigned Int value given 2 bytes" in {
      def check(bytes: String, expected: Int) = {
        val (s1, s2) = bytes.splitAt(8)
        Unsigned.toInt(Unsigned.parse(s1), Unsigned.parse(s2)) should be(expected)
      }
      check("0000000000000000", 0)
      check("0000000000000001", 1)
      check("0000000010000000", 128)
      check("0000000011111111", 255)
      check("0000000100000000", 256)
      check("1000000000000000", 32768)
      check("1000000000000001", 32769)
      check("1111111100000000", 65280)
      check("1111111111111111", 65535)
    }

    "read unsigned Long value given 4 bytes" in {
      def check(bytes: String, expected: Long) = {
        val seq = bytes.grouped(8).toSeq
        Unsigned.toLong(Unsigned.parse(seq(0)), Unsigned.parse(seq(1)), Unsigned.parse(seq(2)), Unsigned.parse(seq(3))) should be(expected)
      }
      check("00000000000000000000000000000000", 0L)
      check("00000000000000000000000000000001", 1L)
      check("00000000000000000000000010000000", 128L)
      check("00000000000000000000000011111111", 255L)
      check("00000000000000000000000100000000", 256L)
      check("00000000000000001000000000000000", 32768L)
      check("00000000000000001000000000000001", 32769L)
      check("00000000000000001111111111111111", 65535L)
      check("10000000000000000000000000000000", 2147483648L)
      check("10000000000000000000000000000001", 2147483649L)
      check("10101010101010101010101010101010", 2863311530L)
      check("11111111111111110000000000000000", 4294901760L)
      check("11111111111111111111111111111111", 4294967295L)
    }

    "convert unsigned Int to 2 bytes" in {
      def check(expected: String, int: Int) = {
        val (s1, s2) = expected.splitAt(8)
        val bytes = Bytes(Unsigned.parse(s1), Unsigned.parse(s2))
        bytes.length should be(2)
        Unsigned.toBytes(int) should be(bytes)
      }
      check("0000000000000000", 0)
      check("0000000000000001", 1)
      check("0000000010000000", 128)
      check("0000000011111111", 255)
      check("0000000100000000", 256)
      check("1000000000000000", 32768)
      check("1000000000000001", 32769)
      check("1111111100000000", 65280)
      check("1111111111111111", 65535)
    }

    "convert unsigned int to 4 bytes" in {
      def check(expected: String, long: Long) = {
        val array = expected.grouped(8).map(Unsigned.parse).toArray
        val bytes = Bytes.wrap(array)
        bytes.length should be(4)
        Unsigned.toBytes(long) should be(bytes)
      }
      check("00000000000000000000000000000000", 0L)
      check("00000000000000000000000000000001", 1L)
      check("00000000000000000000000010000000", 128L)
      check("00000000000000000000000011111111", 255L)
      check("00000000000000000000000100000000", 256L)
      check("00000000000000001000000000000000", 32768L)
      check("00000000000000001000000000000001", 32769L)
      check("00000000000000001111111111111111", 65535L)
      check("10000000000000000000000000000000", 2147483648L)
      check("10000000000000000000000000000001", 2147483649L)
      check("10101010101010101010101010101010", 2863311530L)
      check("11111111111111110000000000000000", 4294901760L)
      check("11111111111111111111111111111111", 4294967295L)
    }
  }

}