package dev.lumen.inspector.kv

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Preferences DataStore protobuf codec; no DataStore / protobuf dependency. */
internal object PreferencesProto {

  fun decode(bytes: ByteArray): Map<String, Any> {
    if (bytes.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, Any>()
    val reader = ProtoReader(bytes)
    while (!reader.eof) {
      val (field, wire) = reader.readTag()
      if (field == 1 && wire == ProtoReader.LEN) {
        val entry = decodeMapEntry(reader.readBytes())
        if (entry != null) out[entry.first] = entry.second
      } else {
        reader.skip(wire)
      }
    }
    return out
  }

  fun encode(values: Map<String, Any?>): ByteArray {
    val out = ByteArrayOutputStream()
    for ((key, raw) in values) {
      if (raw == null) continue
      val entry = ByteArrayOutputStream()
      writeLen(entry, 1, key.toByteArray(Charsets.UTF_8))
      writeLen(entry, 2, encodeValue(raw))
      writeLen(out, 1, entry.toByteArray())
    }
    return out.toByteArray()
  }

  /**
   * Coerce a DevTools string into the type of [existing], matching
   * SharedPreferences editing rules.
   */
  fun coerce(newValue: String, existing: Any?): Any {
    return when (existing) {
      is Boolean -> parseBoolean(newValue)
      is Int -> newValue.toInt()
      is Long -> newValue.toLong()
      is Float -> newValue.toFloat()
      is Double -> newValue.toDouble()
      is Set<*> -> parseStringSet(newValue)
      is ByteArray -> parseHexBytes(newValue)
        ?: throw IllegalArgumentException("Expected hex bytes")
      is String, null -> newValue
      else -> newValue
    }
  }

  fun display(value: Any?): String {
    return when (value) {
      null -> ""
      is ByteArray -> hex(value)
      is Set<*> -> {
        val arr = JSONArray()
        for (item in value) arr.put(item.toString())
        arr.toString()
      }
      else -> value.toString()
    }
  }

  fun hex(bytes: ByteArray): String =
    bytes.joinToString("") { b -> "%02x".format(b) }

  fun parseHexBytes(text: String): ByteArray? {
    var s = text.trim()
    if (s.startsWith("bytes:")) s = s.substring(6)
    if (s.isEmpty() || s.length % 2 != 0) return null
    if (s.any { ch ->
        ch !in '0'..'9' && ch !in 'a'..'f' && ch !in 'A'..'F'
      }
    ) {
      return null
    }
    return ByteArray(s.length / 2) { i ->
      s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
  }

  private fun decodeMapEntry(bytes: ByteArray): Pair<String, Any>? {
    val reader = ProtoReader(bytes)
    var key: String? = null
    var value: Any? = null
    while (!reader.eof) {
      val (field, wire) = reader.readTag()
      when {
        field == 1 && wire == ProtoReader.LEN -> key = String(reader.readBytes(), Charsets.UTF_8)
        field == 2 && wire == ProtoReader.LEN -> value = decodeValue(reader.readBytes())
        else -> reader.skip(wire)
      }
    }
    return if (key != null && value != null) key to value else null
  }

  private fun decodeValue(bytes: ByteArray): Any? {
    val reader = ProtoReader(bytes)
    var result: Any? = null
    while (!reader.eof) {
      val (field, wire) = reader.readTag()
      result = when {
        field == 1 && wire == ProtoReader.VARINT -> reader.readVarint() != 0L
        field == 2 && wire == ProtoReader.I32 -> java.lang.Float.intBitsToFloat(reader.readFixed32())
        field == 3 && wire == ProtoReader.VARINT -> reader.readVarint().toInt()
        field == 4 && wire == ProtoReader.VARINT -> reader.readVarint()
        field == 5 && wire == ProtoReader.LEN -> String(reader.readBytes(), Charsets.UTF_8)
        field == 6 && wire == ProtoReader.LEN -> decodeStringSet(reader.readBytes())
        field == 7 && wire == ProtoReader.I64 -> java.lang.Double.longBitsToDouble(reader.readFixed64())
        field == 8 && wire == ProtoReader.LEN -> reader.readBytes()
        else -> {
          reader.skip(wire)
          result
        }
      }
    }
    return result
  }

  private fun decodeStringSet(bytes: ByteArray): Set<String> {
    val set = LinkedHashSet<String>()
    val reader = ProtoReader(bytes)
    while (!reader.eof) {
      val (field, wire) = reader.readTag()
      if (field == 1 && wire == ProtoReader.LEN) {
        set.add(String(reader.readBytes(), Charsets.UTF_8))
      } else {
        reader.skip(wire)
      }
    }
    return set
  }

  private fun encodeValue(value: Any): ByteArray {
    val out = ByteArrayOutputStream()
    when (value) {
      is Boolean -> writeVarint(out, 1, if (value) 1L else 0L)
      is Float -> writeFixed32(out, 2, java.lang.Float.floatToIntBits(value))
      is Int -> writeVarint(out, 3, value.toLong())
      is Long -> writeVarint(out, 4, value)
      is String -> writeLen(out, 5, value.toByteArray(Charsets.UTF_8))
      is Set<*> -> writeLen(out, 6, encodeStringSet(value))
      is Double -> writeFixed64(out, 7, java.lang.Double.doubleToLongBits(value))
      is ByteArray -> writeLen(out, 8, value)
      else -> writeLen(out, 5, value.toString().toByteArray(Charsets.UTF_8))
    }
    return out.toByteArray()
  }

  private fun encodeStringSet(value: Set<*>): ByteArray {
    val out = ByteArrayOutputStream()
    for (item in value) {
      writeLen(out, 1, item.toString().toByteArray(Charsets.UTF_8))
    }
    return out.toByteArray()
  }

  private fun parseBoolean(s: String): Boolean {
    return when {
      s == "1" || s.equals("true", ignoreCase = true) -> true
      s == "0" || s.equals("false", ignoreCase = true) -> false
      else -> throw IllegalArgumentException("Expected boolean, got $s")
    }
  }

  private fun parseStringSet(newValue: String): Set<String> {
    try {
      val obj = JSONArray(newValue)
      val set = LinkedHashSet<String>(obj.length())
      for (i in 0 until obj.length()) set.add(obj.getString(i))
      return set
    } catch (e: org.json.JSONException) {
      throw IllegalArgumentException(e)
    }
  }

  /** Best-effort JSON tree of an unknown proto (Proto DataStore). */
  fun dumpGeneric(bytes: ByteArray): JSONObject {
    return dumpMessage(bytes)
  }

  private fun dumpMessage(bytes: ByteArray): JSONObject {
    val obj = JSONObject()
    if (bytes.isEmpty()) return obj
    val reader = ProtoReader(bytes)
    while (!reader.eof) {
      val (field, wire) = reader.readTag()
      val key = field.toString()
      val decoded = when (wire) {
        ProtoReader.VARINT -> reader.readVarint()
        ProtoReader.I64 -> reader.readFixed64()
        ProtoReader.I32 -> reader.readFixed32()
        ProtoReader.LEN -> {
          val inner = reader.readBytes()
          decodeLenValue(inner)
        }
        else -> {
          reader.skip(wire)
          JSONObject.NULL
        }
      }
      if (obj.has(key)) {
        val existing = obj.get(key)
        val arr = existing as? JSONArray ?: JSONArray().put(existing)
        arr.put(decoded)
        obj.put(key, arr)
      } else {
        obj.put(key, decoded)
      }
    }
    return obj
  }

  private fun decodeLenValue(inner: ByteArray): Any {
    val asString = inner.toString(Charsets.UTF_8)
    val printable = asString.all { ch ->
      ch == '\n' || ch == '\r' || ch == '\t' || ch.code in 32..126
    }
    if (printable && inner.isNotEmpty()) return asString
    return try {
      dumpMessage(inner)
    } catch (_: Throwable) {
      inner.joinToString("") { b -> "%02x".format(b) }
    }
  }
}

internal class ProtoReader(private val data: ByteArray) {
  var pos = 0
  val eof: Boolean get() = pos >= data.size

  fun readTag(): Pair<Int, Int> {
    val tag = readVarint()
    return (tag ushr 3).toInt() to (tag and 7L).toInt()
  }

  fun readVarint(): Long {
    var result = 0L
    var shift = 0
    while (pos < data.size) {
      val b = data[pos++].toInt() and 0xff
      result = result or ((b and 0x7f).toLong() shl shift)
      if (b and 0x80 == 0) return result
      shift += 7
      if (shift > 63) throw IllegalArgumentException("varint too long")
    }
    throw IllegalArgumentException("truncated varint")
  }

  fun readBytes(): ByteArray {
    val len = readVarint().toInt()
    if (len < 0 || pos + len > data.size) throw IllegalArgumentException("truncated bytes")
    val slice = data.copyOfRange(pos, pos + len)
    pos += len
    return slice
  }

  fun readFixed32(): Int {
    if (pos + 4 > data.size) throw IllegalArgumentException("truncated i32")
    val v = (data[pos].toInt() and 0xff) or
      ((data[pos + 1].toInt() and 0xff) shl 8) or
      ((data[pos + 2].toInt() and 0xff) shl 16) or
      ((data[pos + 3].toInt() and 0xff) shl 24)
    pos += 4
    return v
  }

  fun readFixed64(): Long {
    val lo = readFixed32().toLong() and 0xffffffffL
    val hi = readFixed32().toLong() and 0xffffffffL
    return lo or (hi shl 32)
  }

  fun skip(wire: Int) {
    when (wire) {
      VARINT -> readVarint()
      I64 -> {
        if (pos + 8 > data.size) throw IllegalArgumentException("truncated i64")
        pos += 8
      }
      LEN -> readBytes()
      I32 -> {
        if (pos + 4 > data.size) throw IllegalArgumentException("truncated i32")
        pos += 4
      }
      else -> throw IllegalArgumentException("unknown wire type $wire")
    }
  }

  companion object {
    const val VARINT = 0
    const val I64 = 1
    const val LEN = 2
    const val I32 = 5
  }
}

internal fun writeVarint(out: ByteArrayOutputStream, field: Int, value: Long) {
  writeRawVarint(out, (field shl 3).toLong() or ProtoReader.VARINT.toLong())
  writeRawVarint(out, value)
}

internal fun writeLen(out: ByteArrayOutputStream, field: Int, bytes: ByteArray) {
  writeRawVarint(out, (field shl 3).toLong() or ProtoReader.LEN.toLong())
  writeRawVarint(out, bytes.size.toLong())
  out.write(bytes)
}

internal fun writeFixed32(out: ByteArrayOutputStream, field: Int, value: Int) {
  writeRawVarint(out, (field shl 3).toLong() or ProtoReader.I32.toLong())
  out.write(value and 0xff)
  out.write((value ushr 8) and 0xff)
  out.write((value ushr 16) and 0xff)
  out.write((value ushr 24) and 0xff)
}

internal fun writeFixed64(out: ByteArrayOutputStream, field: Int, value: Long) {
  writeRawVarint(out, (field shl 3).toLong() or ProtoReader.I64.toLong())
  writeFixed32Bits(out, (value and 0xffffffffL).toInt())
  writeFixed32Bits(out, ((value ushr 32) and 0xffffffffL).toInt())
}

private fun writeFixed32Bits(out: ByteArrayOutputStream, value: Int) {
  out.write(value and 0xff)
  out.write((value ushr 8) and 0xff)
  out.write((value ushr 16) and 0xff)
  out.write((value ushr 24) and 0xff)
}

private fun writeRawVarint(out: ByteArrayOutputStream, value: Long) {
  var v = value
  while (v and 0x7fL.inv() != 0L) {
    out.write(((v and 0x7fL) or 0x80L).toInt())
    v = v ushr 7
  }
  out.write((v and 0x7fL).toInt())
}
