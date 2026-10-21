package dev.lumen.store

import org.junit.Assert.assertEquals
import org.junit.Test

class WsFrameLogTest {
  private fun frame(payload: String) = WsFrame(
    timestampMs = 0L,
    timestampMonoMs = 0L,
    outgoing = false,
    opcode = 1,
    payload = payload,
    binary = false,
    truncated = false,
  )

  @Test
  fun evictsEngineIoHeartbeatsBeforeMessages() {
    val log = WsFrameLog()
    for (p in listOf("42[\"a\"]", "2", "42[\"b\"]", "3", "42[\"c\"]")) log.add(frame(p), max = 3)
    assertEquals(listOf("42[\"a\"]", "42[\"b\"]", "42[\"c\"]"), log.snapshot().map { it.payload })
  }

  @Test
  fun evictsOldestWhenOnlyMessagesRemain() {
    val log = WsFrameLog()
    for (p in listOf("one", "two", "three")) log.add(frame(p), max = 2)
    assertEquals(listOf("two", "three"), log.snapshot().map { it.payload })
    assertEquals(2, log.size)
  }

  @Test
  fun snapshotIsDetachedFromLaterAppends() {
    val log = WsFrameLog()
    log.add(frame("a"), max = 10)
    val snapshot = log.snapshot()
    log.add(frame("b"), max = 10)
    assertEquals(1, snapshot.size)
  }
}
