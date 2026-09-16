package dev.lumen.inspector.kv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmkvValueCodecTest {

  @Test
  fun integerIsNotEmptyStringSet() {
    // MMKV.decodeStringSet() returns [] for a stored int: the protobuf vector
    // decoder consumes the varint as a container length and finds no items.
    val (value, type) = MmkvValueCodec.decode(
      MmkvValueCodec.Probes(
        string = null,
        stringSet = emptySet(),
        rawSize = 1,
        asInt = 5,
        asLong = 5L,
      ),
    )
    assertEquals(5, value)
    assertEquals("int", type)
    assertEquals("5", PreferencesProto.display(value))
  }

  @Test
  fun shownCountStyleValuesStayNumeric() {
    for (n in listOf(0, 1, 2, 5, 127, 128, 1000, Int.MAX_VALUE)) {
      val (value, type) = MmkvValueCodec.decode(
        MmkvValueCodec.Probes(
          stringSet = emptySet(),
          rawSize = MmkvValueCodec.pbInt32Size(n),
          asInt = n,
          asLong = n.toLong(),
        ),
      )
      assertEquals("int for $n", n, value)
      assertEquals("int", type)
    }
  }

  @Test
  fun emptySetIsIgnoredWhenSizeApiMissing() {
    val (value, type) = MmkvValueCodec.decode(
      MmkvValueCodec.Probes(
        stringSet = emptySet(),
        rawSize = 0,
        asInt = 3,
        asLong = 3L,
      ),
    )
    assertEquals(3, value)
    assertEquals("int", type)
  }

  @Test
  fun nonEmptyStringSetWins() {
    val set = linkedSetOf("a", "b")
    val (value, type) = MmkvValueCodec.decode(
      MmkvValueCodec.Probes(
        string = "\u0001a\u0001b",
        stringSet = set,
        rawSize = 6,
      ),
    )
    assertEquals(set, value)
    assertEquals("stringSet", type)
    assertEquals("[\"a\",\"b\"]", PreferencesProto.display(value))
  }

  @Test
  fun plainStringWinsOverEmptySet() {
    val (value, type) = MmkvValueCodec.decode(
      MmkvValueCodec.Probes(
        string = "sample-token",
        stringSet = emptySet(),
        rawSize = 13,
      ),
    )
    assertEquals("sample-token", value)
    assertEquals("string", type)
  }

  @Test
  fun longThatDoesNotFitInt() {
    val n = 3_000_000_000L
    val (value, type) = MmkvValueCodec.decode(
      MmkvValueCodec.Probes(
        stringSet = emptySet(),
        rawSize = MmkvValueCodec.pbInt64Size(n),
        asInt = n.toInt(),
        asLong = n,
      ),
    )
    assertEquals(n, value)
    assertEquals("long", type)
  }

  @Test
  fun negativeIntUsesTenByteVarint() {
    val (value, type) = MmkvValueCodec.decode(
      MmkvValueCodec.Probes(
        stringSet = emptySet(),
        rawSize = 10,
        asInt = -1,
        asLong = -1L,
      ),
    )
    assertEquals(-1, value)
    assertEquals("int", type)
  }

  @Test
  fun fourByteNonVarintIsFloat() {
    val (value, type) = MmkvValueCodec.decode(
      MmkvValueCodec.Probes(
        stringSet = emptySet(),
        rawSize = 4,
        asInt = 0,
        asLong = 0L,
        asFloat = 1.5f,
      ),
    )
    assertEquals(1.5f, value)
    assertEquals("float", type)
  }

  @Test
  fun eightByteNonVarintIsDouble() {
    val (value, type) = MmkvValueCodec.decode(
      MmkvValueCodec.Probes(
        stringSet = emptySet(),
        rawSize = 8,
        asInt = 0,
        asLong = 0L,
        asDouble = 3.14159,
      ),
    )
    assertEquals(3.14159, value)
    assertEquals("double", type)
  }

  @Test
  fun pbInt32SizeMatchesProtobufVarint() {
    assertEquals(1, MmkvValueCodec.pbInt32Size(0))
    assertEquals(1, MmkvValueCodec.pbInt32Size(5))
    assertEquals(1, MmkvValueCodec.pbInt32Size(127))
    assertEquals(2, MmkvValueCodec.pbInt32Size(128))
    assertEquals(10, MmkvValueCodec.pbInt32Size(-1))
  }

  @Test
  fun bytesFallbackWhenSizeDoesNotMatchPrimitive() {
    val blob = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
    val (value, type) = MmkvValueCodec.decode(
      MmkvValueCodec.Probes(
        stringSet = emptySet(),
        rawSize = blob.size,
        bytes = blob,
      ),
    )
    assertTrue(value is ByteArray)
    assertEquals("bytes", type)
  }

  @Test
  fun extraProbesSkipsNativeGettersForVarintInt() {
    assertTrue(MmkvValueCodec.extraProbes(1, 0, 0L).isEmpty())
    assertTrue(MmkvValueCodec.extraProbes(1, 1, 1L).isEmpty())
    assertTrue(MmkvValueCodec.extraProbes(1, 5, 5L).isEmpty())
    assertTrue(MmkvValueCodec.extraProbes(2, 128, 128L).isEmpty())
    assertTrue(MmkvValueCodec.extraProbes(10, -1, -1L).isEmpty())
  }

  @Test
  fun extraProbesUsesBytesForLengthDelimited() {
    assertEquals(setOf(MmkvExtraProbe.BYTES), MmkvValueCodec.extraProbes(6, 5, 5L))
    assertEquals(setOf(MmkvExtraProbe.BYTES), MmkvValueCodec.extraProbes(4, 3, 3L))
  }

  @Test
  fun extraProbesUsesFloatOrDoubleWhenSizeMatches() {
    assertEquals(setOf(MmkvExtraProbe.FLOAT), MmkvValueCodec.extraProbes(4, 0, 0L))
    assertEquals(setOf(MmkvExtraProbe.DOUBLE), MmkvValueCodec.extraProbes(8, 0, 0L))
  }

  @Test
  fun parseStringVectorReadsConcatenatedProtobufStrings() {
    val payload = byteArrayOf(0x01, 'a'.code.toByte(), 0x01, 'b'.code.toByte())
    assertEquals(linkedSetOf("a", "b"), MmkvValueCodec.parseStringVector(payload))
  }

  @Test
  fun parseStringVectorRejectsPlainUtf8() {
    assertEquals(null, MmkvValueCodec.parseStringVector("hello".toByteArray()))
  }

  @Test
  fun lengthDelimitedStringSetDecodedFromBytesWithoutNativeSetGetter() {
    val payload = byteArrayOf(0x05) + "hello".toByteArray()
    val (value, type) = MmkvValueCodec.decode(
      MmkvValueCodec.Probes(
        stringSet = MmkvValueCodec.parseStringVector(payload),
        rawSize = 1 + payload.size,
        asInt = payload.size,
        asLong = payload.size.toLong(),
        bytes = payload,
      ),
    )
    assertEquals(linkedSetOf("hello"), value)
    assertEquals("stringSet", type)
  }

  @Test
  fun lengthDelimitedPlainStringStaysString() {
    val payload = "hello".toByteArray()
    val (value, type) = MmkvValueCodec.decode(
      MmkvValueCodec.Probes(
        stringSet = MmkvValueCodec.parseStringVector(payload),
        rawSize = 1 + payload.size,
        asInt = payload.size,
        asLong = payload.size.toLong(),
        bytes = payload,
      ),
    )
    assertEquals("hello", value)
    assertEquals("string", type)
  }

  @Test
  fun nonAsciiUtf8BytesAreString() {
    for (text in listOf("café", "你好", "😀")) {
      val payload = text.toByteArray(Charsets.UTF_8)
      val (value, type) = MmkvValueCodec.decode(
        MmkvValueCodec.Probes(
          rawSize = 1 + payload.size,
          asInt = payload.size,
          asLong = payload.size.toLong(),
          bytes = payload,
        ),
      )
      assertEquals(text, value)
      assertEquals("string", type)
    }
  }

  @Test
  fun isoControlAndMalformedUtf8StayBytes() {
    val control = byteArrayOf(0x00, 'a'.code.toByte())
    val (controlValue, controlType) = MmkvValueCodec.decode(
      MmkvValueCodec.Probes(rawSize = control.size, bytes = control),
    )
    assertTrue(controlValue is ByteArray)
    assertEquals("bytes", controlType)

    val malformed = byteArrayOf(0x80.toByte(), 0x81.toByte())
    val (badValue, badType) = MmkvValueCodec.decode(
      MmkvValueCodec.Probes(rawSize = malformed.size, bytes = malformed),
    )
    assertTrue(badValue is ByteArray)
    assertEquals("bytes", badType)
  }
}
