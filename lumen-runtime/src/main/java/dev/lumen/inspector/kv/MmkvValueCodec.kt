package dev.lumen.inspector.kv

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Infer an MMKV value's type from what the typed getters return.
 *
 * MMKV does not store a type tag. Native `decodeFloat` / `decodeString` /
 * `decodeStringSet` / `decodeBytes` log ERROR on a type mismatch
 * (`reach end`, `InvalidProtocolBuffer truncatedMessage`), so [extraProbes]
 * tells [MmkvAccess] which getters are size-safe for a key.
 *
 * MiniPBCoder also treats a lone varint (an int/bool) as an empty string
 * vector, so `shown_count = 5` comes back as `[]`. Empty sets are ignored,
 * and numeric values are recovered from protobuf size.
 */
internal object MmkvValueCodec {

  data class Probes(
    val string: String? = null,
    val stringSet: Set<String>? = null,
    val rawSize: Int = 0,
    val asInt: Int = 0,
    val asLong: Long = 0L,
    val asFloat: Float = 0f,
    val asDouble: Double = 0.0,
    val bytes: ByteArray? = null,
  )

  fun decode(p: Probes): Pair<Any?, String> {
    // Non-empty set only. Empty is the int/bool false-positive.
    if (!p.stringSet.isNullOrEmpty()) return p.stringSet to "stringSet"
    if (!p.string.isNullOrEmpty()) return p.string to "string"

    inferPrimitive(p)?.let { return it }

    if (p.bytes != null) {
      val text = utf8Text(p.bytes)
      return if (text != null) text to "string" else p.bytes to "bytes"
    }
    if (p.string != null) return p.string to "string"
    return null to "null"
  }

  /**
   * Native getters that will not throw / log on this (size, int, long) triple.
   *
   * Int/long are always decoded first by the caller. A complete varint needs
   * nothing else. Length-delimited values (string / bytes / stringSet) share
   * the same protobuf wrapping, so only [MmkvExtraProbe.BYTES] is required —
   * [parseStringVector] recovers a stringSet from that payload without
   * calling `decodeStringSet`.
   */
  fun extraProbes(rawSize: Int, asInt: Int, asLong: Long): Set<MmkvExtraProbe> {
    if (rawSize <= 0) return emptySet()
    if (isCompleteVarint(rawSize, asInt, asLong)) return emptySet()
    if (isLengthDelimited(rawSize, asInt)) return setOf(MmkvExtraProbe.BYTES)
    return when (rawSize) {
      4 -> setOf(MmkvExtraProbe.FLOAT)
      8 -> setOf(MmkvExtraProbe.DOUBLE)
      else -> emptySet()
    }
  }

  fun isCompleteVarint(rawSize: Int, asInt: Int, asLong: Long): Boolean {
    return pbInt32Size(asInt) == rawSize || pbInt64Size(asLong) == rawSize
  }

  fun isLengthDelimited(rawSize: Int, asInt: Int): Boolean {
    if (asInt < 0) return false
    return pbInt32Size(asInt) + asInt == rawSize
  }

  /**
   * Parse MiniPBCoder's inner string-vector payload: concatenated
   * protobuf strings with no field tags. Returns null if [payload] is not
   * exactly that (plain UTF-8, truncated, leftover bytes).
   */
  fun parseStringVector(payload: ByteArray): Set<String>? {
    if (payload.isEmpty()) return null
    val out = LinkedHashSet<String>()
    var pos = 0
    while (pos < payload.size) {
      val (len, consumed) = readVarint32(payload, pos) ?: return null
      pos += consumed
      if (len < 0 || pos + len > payload.size) return null
      out.add(String(payload, pos, len, Charsets.UTF_8))
      pos += len
    }
    if (pos != payload.size || out.isEmpty()) return null
    return out
  }

  fun pbInt32Size(value: Int): Int = if (value < 0) 10 else pbRawVarint64Size(value.toLong())

  fun pbInt64Size(value: Long): Int = if (value < 0) 10 else pbRawVarint64Size(value)

  private fun readVarint32(data: ByteArray, start: Int): Pair<Int, Int>? {
    var result = 0
    var shift = 0
    var pos = start
    while (pos < data.size && shift <= 28) {
      val b = data[pos++].toInt() and 0xff
      result = result or ((b and 0x7f) shl shift)
      if (b and 0x80 == 0) return result to (pos - start)
      shift += 7
    }
    return null
  }

  internal fun inferPrimitive(p: Probes): Pair<Any, String>? {
    val size = p.rawSize
    if (size <= 0) return inferPrimitiveWithoutSize(p)

    if (p.asInt.toLong() != p.asLong && pbInt64Size(p.asLong) == size) {
      return p.asLong to "long"
    }
    return when (size) {
      1, 2, 3, 5 -> when {
        pbInt32Size(p.asInt) == size -> p.asInt to "int"
        pbInt64Size(p.asLong) == size -> p.asLong to "long"
        else -> null
      }
      4 -> when {
        pbInt32Size(p.asInt) == 4 -> p.asInt to "int"
        pbInt64Size(p.asLong) == 4 -> p.asLong to "long"
        else -> p.asFloat to "float"
      }
      8 -> if (pbInt64Size(p.asLong) == 8) p.asLong to "long" else p.asDouble to "double"
      10 -> when {
        pbInt32Size(p.asInt) == 10 -> p.asInt to "int"
        pbInt64Size(p.asLong) == 10 -> p.asLong to "long"
        else -> null
      }
      else -> null
    }
  }

  /**
   * When [Probes.rawSize] is unavailable: same fallback as typical MMKV
   * inspectors — long if it doesn't fit in int, else the first non-zero probe.
   */
  private fun inferPrimitiveWithoutSize(p: Probes): Pair<Any, String>? {
    if (p.asInt.toLong() != p.asLong) return p.asLong to "long"
    if (p.asInt != 0) return p.asInt to "int"
    if (p.asFloat != 0f && p.asFloat.isFinite()) return p.asFloat to "float"
    if (p.asDouble != 0.0 && p.asDouble.isFinite()) return p.asDouble to "double"
    return p.asInt to "int"
  }

  private fun utf8Text(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return null
    val text = try {
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    } catch (_: CharacterCodingException) {
      return null
    }
    val textChars = text.all { ch ->
      ch == '\n' || ch == '\r' || ch == '\t' || !ch.isISOControl()
    }
    return if (textChars) text else null
  }

  private fun pbRawVarint64Size(value: Long): Int {
    var n = 1
    while (n < 10) {
      if (value ushr (7 * n) == 0L) return n
      n++
    }
    return 10
  }
}

internal enum class MmkvExtraProbe {
  FLOAT,
  DOUBLE,
  BYTES,
}
