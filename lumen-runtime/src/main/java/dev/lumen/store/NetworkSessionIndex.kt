package dev.lumen.store

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Where each record's lines start in a session jsonl, so one record can be read back without
 * parsing, or holding, the rest of the file: a whole launch can run to tens of thousands of
 * records. Session files only grow, so [refresh] reads just the bytes added since its last call.
 */
internal class NetworkSessionIndex(private val file: File) {
  private val order = ArrayList<String>()
  private val offsets = HashMap<String, Offsets>()
  private var indexedBytes = 0L
  private var generation = 0

  /**
   * Catches up with lines appended since the last call; a partly written last line waits.
   * A new [generation] ([JsonlAppender.generation]) means the file was deleted and started
   * over, as does a file shorter than what was indexed: the old offsets are dropped.
   */
  @Synchronized
  fun refresh(generation: Int = this.generation) {
    val length = file.length()
    if (length < indexedBytes || generation != this.generation) {
      order.clear()
      offsets.clear()
      indexedBytes = 0
      this.generation = generation
    }
    if (length == indexedBytes) return
    try {
      FileInputStream(file).use { input ->
        skipFully(input, indexedBytes)
        val line = LineBuffer()
        val chunk = ByteArray(SCAN_CHUNK)
        var position = indexedBytes
        var lineStart = indexedBytes
        while (true) {
          val n = input.read(chunk)
          if (n < 0) break
          var from = 0
          for (i in 0 until n) {
            if (chunk[i] != NEWLINE) continue
            line.write(chunk, from, i - from)
            idOf(line)?.let { add(it, lineStart) }
            line.reset()
            from = i + 1
            lineStart = position + from
          }
          line.write(chunk, from, n - from)
          position += n
        }
        indexedBytes = lineStart
      }
    } catch (_: IOException) {
      // Not written yet, or unreadable: nothing to index.
    }
  }

  /** Record ids in the order their first line appears. */
  @Synchronized
  fun ids(): List<String> = ArrayList(order)

  @Synchronized
  fun size(): Int = order.size

  /** Start offsets of [id]'s lines, oldest first (a copy: [refresh] keeps appending). */
  @Synchronized
  fun offsetsOf(id: String): LongArray? = offsets[id]?.let { it.values.copyOf(it.size) }

  private fun add(id: String, offset: Long) {
    val known = offsets[id]
    if (known == null) {
      order.add(id)
      offsets[id] = Offsets(offset)
    } else {
      known.add(offset)
    }
  }

  /** Grows by doubling: a long-lived record (a Socket.IO polling session) can write many lines. */
  private class Offsets(first: Long) {
    var values = LongArray(4).also { it[0] = first }
    var size = 1

    fun add(offset: Long) {
      if (size == values.size) values = values.copyOf(size * 2)
      values[size++] = offset
    }
  }

  /** Reads lines of one session file at offsets from its index. Not thread-safe; close it. */
  class Reader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val chunk = ByteArray(READ_CHUNK)
    private val line = LineBuffer()

    /** The lines at [offsets] folded with [NetworkRecordJson.merge]; null when none parse. */
    fun merged(offsets: LongArray): JSONObject? {
      var merged: JSONObject? = null
      for (offset in offsets) {
        val text = lineAt(offset) ?: continue
        val o = try {
          JSONObject(text)
        } catch (_: JSONException) {
          continue
        }
        if (merged == null) merged = o else NetworkRecordJson.merge(merged, o)
      }
      return merged
    }

    private fun lineAt(offset: Long): String? {
      raf.seek(offset)
      line.reset()
      while (true) {
        val n = raf.read(chunk)
        if (n <= 0) return null
        for (i in 0 until n) {
          if (chunk[i] == NEWLINE) {
            line.write(chunk, 0, i)
            return line.text()
          }
        }
        line.write(chunk, 0, n)
      }
    }

    override fun close() = raf.close()
  }

  private class LineBuffer : ByteArrayOutputStream(512) {
    fun text(): String = String(buf, 0, count, Charsets.UTF_8)

    /** The id as Android's JSONObject (insertion-ordered) writes it: first, as `{"id":"…"`. */
    fun leadingId(): String? {
      if (count <= ID_PREFIX.size) return null
      for (i in ID_PREFIX.indices) {
        if (buf[i] != ID_PREFIX[i]) return null
      }
      for (i in ID_PREFIX.size until count) {
        if (buf[i] == QUOTE) {
          return if (i > ID_PREFIX.size) String(buf, ID_PREFIX.size, i - ID_PREFIX.size, Charsets.US_ASCII) else null
        }
      }
      return null
    }
  }

  private companion object {
    const val NEWLINE = '\n'.code.toByte()
    const val QUOTE = '"'.code.toByte()
    const val SCAN_CHUNK = 64 * 1024
    const val READ_CHUNK = 4 * 1024
    val ID_PREFIX = "{\"id\":\"".toByteArray(Charsets.US_ASCII)

    fun idOf(line: LineBuffer): String? =
      line.leadingId() ?: try {
        JSONObject(line.text()).optString("id").takeIf { it.isNotEmpty() }
      } catch (_: JSONException) {
        null
      }

    fun skipFully(input: InputStream, bytes: Long) {
      var left = bytes
      while (left > 0) {
        val skipped = input.skip(left)
        if (skipped <= 0) throw IOException("file shorter than its index")
        left -= skipped
      }
    }
  }
}
