package dev.lumen.inspector.kv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesProtoTest {

  @Test
  fun roundTripStringBooleanIntLongSet() {
    val original = linkedMapOf<String, Any>(
      "name" to "lumen",
      "enabled" to true,
      "count" to 7,
      "big" to 1_000_000_000_000L,
      "tags" to linkedSetOf("a", "b"),
    )
    val decoded = PreferencesProto.decode(PreferencesProto.encode(original))
    assertEquals("lumen", decoded["name"])
    assertEquals(true, decoded["enabled"])
    assertEquals(7, decoded["count"])
    assertEquals(1_000_000_000_000L, decoded["big"])
    assertEquals(linkedSetOf("a", "b"), decoded["tags"])
  }

  @Test
  fun roundTripFloatAndDouble() {
    val original = linkedMapOf<String, Any>(
      "ratio" to 1.5f,
      "pi" to 3.14159,
    )
    val decoded = PreferencesProto.decode(PreferencesProto.encode(original))
    assertEquals(1.5f, decoded["ratio"] as Float, 0.0001f)
    assertEquals(3.14159, decoded["pi"] as Double, 0.00001)
  }

  @Test
  fun emptyIsEmpty() {
    assertTrue(PreferencesProto.decode(ByteArray(0)).isEmpty())
    assertTrue(PreferencesProto.decode(PreferencesProto.encode(emptyMap())).isEmpty())
  }

  @Test
  fun coerceKeepsType() {
    assertEquals(true, PreferencesProto.coerce("true", false))
    assertEquals(12, PreferencesProto.coerce("12", 0))
    assertEquals(12L, PreferencesProto.coerce("12", 0L))
    assertEquals("hello", PreferencesProto.coerce("hello", "x"))
    assertEquals(setOf("a", "b"), PreferencesProto.coerce("[\"a\",\"b\"]", setOf("z")))
  }

  @Test
  fun displayStringSetAsJsonArray() {
    assertEquals("[\"a\",\"b\"]", PreferencesProto.display(linkedSetOf("a", "b")))
  }

  @Test
  fun roundTripByteArrayField8() {
    val original = linkedMapOf<String, Any>(
      "blob" to byteArrayOf(0x0a, 0xff.toByte()),
    )
    val decoded = PreferencesProto.decode(PreferencesProto.encode(original))
    val blob = decoded["blob"] as ByteArray
    assertEquals(2, blob.size)
    assertEquals(0x0a.toByte(), blob[0])
    assertEquals(0xff.toByte(), blob[1])
  }

  @Test
  fun displayAndCoerceByteArrayAsHex() {
    val bytes = byteArrayOf(0x0a, 0xff.toByte())
    assertEquals("0aff", PreferencesProto.display(bytes))
    val parsed = PreferencesProto.coerce("0aFF", bytes) as ByteArray
    assertEquals(2, parsed.size)
    assertEquals(0x0a.toByte(), parsed[0])
    assertEquals(0xff.toByte(), parsed[1])
  }
}
