package dev.lumen.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

class BodyCodecTest {
  private val json = """{"name":"pikachu","id":25}""".toByteArray()

  private fun gzip(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

  private fun deflate(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(bytes) } }.toByteArray()

  @Test
  fun onlyGzipAndDeflateAreDecodable() {
    assertEquals("gzip", BodyCodec.supportedEncoding(" GZIP "))
    assertEquals("deflate", BodyCodec.supportedEncoding("deflate"))
    assertNull(BodyCodec.supportedEncoding("br"))
    assertNull(BodyCodec.supportedEncoding(null))
  }

  @Test
  fun passesPlainBodiesThrough() {
    assertArrayEquals(json, BodyCodec.decode(ByteArrayInputStream(json), null, 1_000))
  }

  @Test
  fun reversesGzipAndDeflate() {
    assertArrayEquals(json, BodyCodec.decode(ByteArrayInputStream(gzip(json)), "gzip", 1_000))
    assertArrayEquals(json, BodyCodec.decode(ByteArrayInputStream(deflate(json)), "deflate", 1_000))
  }

  @Test
  fun leavesUnsupportedEncodingsUntouched() {
    val raw = byteArrayOf(1, 2, 3)
    assertArrayEquals(raw, BodyCodec.decode(ByteArrayInputStream(raw), "br", 1_000))
  }

  @Test
  fun stopsAtTheDecodedCap() {
    val big = ByteArray(10_000) { 'a'.code.toByte() }
    assertEquals(4_096, BodyCodec.decode(ByteArrayInputStream(gzip(big)), "gzip", 4_096).size)
  }

  @Test
  fun keepsWhatDecodedBeforeATruncatedTail() {
    val text = ByteArray(50_000) { ('a' + it % 26).code.toByte() }
    val clipped = gzip(text).let { it.copyOf(it.size / 2) }
    val decoded = BodyCodec.decode(ByteArrayInputStream(clipped), "gzip", 1_000_000)
    assertTrue(decoded.isNotEmpty())
    assertArrayEquals(text.copyOf(decoded.size), decoded)
  }

  @Test
  fun mediaTypeDecidesTextWhenItCan() {
    val binary = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    assertTrue(BodyCodec.isText("application/json; charset=utf-8", json))
    assertTrue(BodyCodec.isText("application/problem+json", json))
    assertTrue(BodyCodec.isText("text/html", json))
    assertFalse(BodyCodec.isText("image/png", binary))
    assertFalse(BodyCodec.isText("application/octet-stream", json))
  }

  @Test
  fun fallsBackToUtf8ValidityWithoutAMediaType() {
    assertTrue(BodyCodec.isText(null, json))
    assertFalse(BodyCodec.isText(null, byteArrayOf(0xC3.toByte(), 0x28)))
  }
}
