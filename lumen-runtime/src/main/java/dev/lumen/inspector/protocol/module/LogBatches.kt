package dev.lumen.inspector.protocol.module

import dev.lumen.store.LogCatLine
import dev.lumen.store.LogEntry

/**
 * Packs logcat lines into fewer CDP [Log.entryAdded] payloads.
 *
 * Consecutive lines that share a Chrome Console level are joined with
 * newlines so DevTools paints one row per chunk instead of one row per
 * logcat line. Order is preserved. Every input entry appears in some chunk,
 * including empty text; a single oversized line is its own chunk.
 */
internal object LogBatches {
  const val FLUSH_WINDOW_MS = 500L
  const val MAX_CHARS_PER_EVENT = 64 * 1024

  data class Packed(
    val level: String,
    val text: String,
    val timestampMs: Double,
  )

  fun pack(entries: List<LogEntry>): List<Packed> {
    if (entries.isEmpty()) return emptyList()
    val out = ArrayList<Packed>()
    val lines = ArrayList<String>()
    var chars = 0
    var level = LogCatLine.chromeLevel(entries[0].level)
    var timestampMs = entries[0].timestampMs

    fun emit() {
      if (lines.isEmpty()) return
      out.add(Packed(level, lines.joinToString("\n"), timestampMs))
      lines.clear()
      chars = 0
    }

    for (entry in entries) {
      val nextLevel = LogCatLine.chromeLevel(entry.level)
      val extra = if (lines.isEmpty()) entry.text.length else entry.text.length + 1
      if (lines.isNotEmpty() &&
        (nextLevel != level || chars + extra > MAX_CHARS_PER_EVENT)
      ) {
        emit()
      }
      if (lines.isEmpty()) timestampMs = entry.timestampMs
      if (lines.isNotEmpty()) chars += 1
      chars += entry.text.length
      lines.add(entry.text)
      level = nextLevel
    }
    emit()
    return out
  }
}
