package dev.lumen.inspector.protocol.module

import dev.lumen.store.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBatchesTest {
  @Test
  fun sameLevelLinesBecomeOneEvent() {
    val packed = LogBatches.pack(
      listOf(
        entry(1.0, "info", "a"),
        entry(2.0, "info", "b"),
        entry(3.0, "verbose", "c"),
      ),
    )
    assertEquals(1, packed.size)
    assertEquals("info", packed[0].level)
    assertEquals("a\nb\nc", packed[0].text)
    assertEquals(1.0, packed[0].timestampMs, 0.0)
  }

  @Test
  fun levelChangeStartsNewEventAndKeepsOrder() {
    val packed = LogBatches.pack(
      listOf(
        entry(1.0, "info", "a"),
        entry(2.0, "warning", "b"),
        entry(3.0, "info", "c"),
      ),
    )
    assertEquals(listOf("info", "warning", "info"), packed.map { it.level })
    assertEquals(listOf("a", "b", "c"), packed.map { it.text })
  }

  @Test
  fun doesNotDropLinesWhenSplittingOnSize() {
    val lines = (1..20).map { i ->
      entry(i.toDouble(), "info", "x".repeat(LogBatches.MAX_CHARS_PER_EVENT / 4) + i)
    }
    val packed = LogBatches.pack(lines)
    assertTrue(packed.size > 1)
    val restored = packed.flatMap { it.text.split('\n') }
    assertEquals(lines.map { it.text }, restored)
  }

  @Test
  fun oversizedSingleLineIsStillEmitted() {
    val huge = "h".repeat(LogBatches.MAX_CHARS_PER_EVENT + 16)
    val packed = LogBatches.pack(listOf(entry(1.0, "error", huge)))
    assertEquals(1, packed.size)
    assertEquals(huge, packed[0].text)
    assertEquals("error", packed[0].level)
  }

  @Test
  fun emptyInput() {
    assertEquals(emptyList<LogBatches.Packed>(), LogBatches.pack(emptyList()))
  }

  @Test
  fun emptyLinesAreKept() {
    val packed = LogBatches.pack(
      listOf(
        entry(1.0, "info", ""),
        entry(2.0, "info", "a"),
        entry(3.0, "info", ""),
      ),
    )
    assertEquals(1, packed.size)
    assertEquals("\na\n", packed[0].text)
  }

  @Test
  fun emptyLineAtLevelChangeIsKept() {
    val packed = LogBatches.pack(
      listOf(
        entry(1.0, "info", ""),
        entry(2.0, "warning", ""),
        entry(3.0, "error", "e"),
      ),
    )
    assertEquals(3, packed.size)
    assertEquals("", packed[0].text)
    assertEquals("", packed[1].text)
    assertEquals("e", packed[2].text)
  }

  @Test
  fun everyEntryAppearsInPackedText() {
    val entries = listOf(
      entry(1.0, "info", "one"),
      entry(2.0, "info", ""),
      entry(3.0, "warning", "two"),
      entry(4.0, "info", "three"),
    )
    val packed = LogBatches.pack(entries)
    val restored = packed.flatMap { chunk ->
      if (chunk.text.isEmpty()) listOf("") else chunk.text.split('\n')
    }
    assertEquals(entries.map { it.text }, restored)
  }

  private fun entry(timestampMs: Double, level: String, text: String) =
    LogEntry(timestampMs, level, text)
}
