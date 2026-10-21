package dev.lumen.store

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Turns a spilled response body back into something DevTools can show.
 *
 * Bodies are written exactly as the app received them, so the capture path never
 * decompresses or encodes on the HTTP thread. Decoding happens here, only when someone
 * opens the body.
 */
internal object BodyCodec {
  /** Lower-cased `Content-Encoding`, or null for none / `identity`. */
  fun normalizeEncoding(contentEncoding: String?): String? =
    contentEncoding?.trim()?.lowercase()?.takeUnless { it.isEmpty() || it == "identity" }

  /** `Content-Encoding` values [decode] can reverse; anything else is stored and shown as-is. */
  fun supportedEncoding(contentEncoding: String?): String? {
    val encoding = normalizeEncoding(contentEncoding) ?: return null
    return if (encoding == "gzip" || encoding == "deflate") encoding else null
  }

  /**
   * Reads [input] fully, reversing [contentEncoding] when supported, and stops after
   * [maxBytes] decoded bytes. A body truncated at capture time ends mid-stream; what was
   * decoded before that point is returned.
   */
  fun decode(input: InputStream, contentEncoding: String?, maxBytes: Long): ByteArray {
    val source = try {
      when (supportedEncoding(contentEncoding)) {
        "gzip" -> GZIPInputStream(input)
        "deflate" -> InflaterInputStream(input)
        else -> input
      }
    } catch (_: IOException) {
      return ByteArray(0) // not even a valid gzip header
    }
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    // `use` ends the Inflater now instead of leaving its native memory to the finalizer.
    source.use {
      try {
        while (out.size() < maxBytes) {
          val n = it.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - out.size()).toInt())
          if (n < 0) break
          out.write(buffer, 0, n)
        }
      } catch (_: IOException) {
        // Truncated or corrupt compressed tail: keep what decoded cleanly.
      }
    }
    return out.toByteArray()
  }

  /**
   * True when [bytes] should be shown as text rather than base64. Decided by the media
   * type when it says so; otherwise by whether the bytes are valid UTF-8.
   */
  fun isText(mimeType: String?, bytes: ByteArray): Boolean {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (mime.startsWith("text/")) return true
    if (TEXT_MARKERS.any { mime.contains(it) }) return true
    if (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/") ||
      mime.startsWith("font/") || mime in BINARY_TYPES
    ) {
      return false
    }
    return isUtf8(bytes)
  }

  private fun isUtf8(bytes: ByteArray): Boolean = try {
    Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
    true
  } catch (_: CharacterCodingException) {
    false
  }

  private val TEXT_MARKERS = listOf(
    "json", "xml", "javascript", "ecmascript", "x-www-form-urlencoded", "graphql", "html", "csv",
    "yaml",
  )

  private val BINARY_TYPES = setOf(
    "application/octet-stream", "application/pdf", "application/zip", "application/gzip",
    "application/protobuf", "application/x-protobuf", "application/grpc", "application/wasm",
  )
}
