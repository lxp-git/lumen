package dev.lumen.inspector.network

import dev.lumen.store.NetworkRecord
import dev.lumen.store.WsFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WsFrameNetworkRowsTest {

  @Test
  fun indexesTextJsonAndSocketIoEvents() {
    assertTrue(WsFrameNetworkRows.shouldIndex(1, false, """{"i":1,"chunk":"stream-delta-1"}"""))
    assertTrue(WsFrameNetworkRows.shouldIndex(1, false, """42["chat",{"text":"hello"}]"""))
    assertTrue(WsFrameNetworkRows.shouldIndex(1, false, "hello-from-lumen-sample"))
  }

  @Test
  fun skipsPingPongEmptyAndBinary() {
    assertFalse(WsFrameNetworkRows.shouldIndex(1, false, "2"))
    assertFalse(WsFrameNetworkRows.shouldIndex(1, false, "3"))
    assertFalse(WsFrameNetworkRows.shouldIndex(1, false, "2probe"))
    assertFalse(WsFrameNetworkRows.shouldIndex(1, false, ""))
    assertFalse(WsFrameNetworkRows.shouldIndex(2, true, "hello"))
    assertFalse(WsFrameNetworkRows.shouldIndex(2, false, "hello"))
  }

  @Test
  fun transcriptRequestIdIsStableAndReversible() {
    val parent = "4321.7"
    val row = WsFrameNetworkRows.transcriptRequestId(parent)
    assertEquals("4321.7.lumen-ws", row)
    assertEquals(parent, WsFrameNetworkRows.parentRequestId(row))
    assertNull(WsFrameNetworkRows.parentRequestId(parent))
  }

  @Test
  fun transcriptUrlKeepsHostPathAndQuery() {
    val url = WsFrameNetworkRows.transcriptUrl(
      "wss://chat.example.com/socket.io/?EIO=4&transport=websocket",
    )
    assertEquals(
      "wss://chat.example.com/socket.io/?EIO=4&transport=websocket&lumen=ws-transcript",
      url,
    )
    val withoutScheme = url.substringAfter("://")
    assertTrue(withoutScheme.contains("chat.example.com"))
    assertTrue(withoutScheme.contains("lumen=ws-transcript"))
  }

  @Test
  fun transcriptUrlDoesNotEmbedPayload() {
    val url = WsFrameNetworkRows.transcriptUrl("wss://ws.postman-echo.com/raw")
    assertEquals("wss://ws.postman-echo.com/raw?lumen=ws-transcript", url)
    assertFalse(url.contains("hello-from-lumen-sample"))
  }

  @Test
  fun formatLinePrefixesDirection() {
    assertEquals("SEND hello", WsFrameNetworkRows.formatLine(true, "hello"))
    assertEquals("RECV hello", WsFrameNetworkRows.formatLine(false, "hello"))
    assertEquals(
      "RECV partial…[truncated]",
      WsFrameNetworkRows.formatLine(false, "partial", truncated = true),
    )
  }

  @Test
  fun buildTranscriptSkipsControlFramesAndKeepsPayloads() {
    val record = record(
      textFrame(outgoing = true, payload = "2"),
      textFrame(outgoing = true, payload = """42["chat",{"text":"hello"}]"""),
      textFrame(outgoing = false, payload = """{"ok":true}"""),
    )
    val body = WsFrameNetworkRows.buildTranscript(record)
    assertEquals(
      """
      SEND 42["chat",{"text":"hello"}]
      RECV {"ok":true}
      """.trimIndent(),
      body,
    )
    assertFalse(body.contains("SEND 2"))
  }

  @Test
  fun buildTranscriptKeepsNewestWhenCapped() {
    val record = record(
      textFrame(outgoing = true, payload = "aaaa"),
      textFrame(outgoing = true, payload = "bbbb"),
      textFrame(outgoing = true, payload = "cccc"),
    )
    // "SEND cccc" is 9 chars; with one newline a second line is 19.
    val body = WsFrameNetworkRows.buildTranscript(record, maxChars = 19)
    assertEquals("SEND bbbb\nSEND cccc", body)
    assertFalse(body.contains("aaaa"))
  }

  private fun record(vararg frames: WsFrame): NetworkRecord {
    return NetworkRecord(
      requestId = "1.0",
      url = "wss://example.com/ws",
      method = "GET",
      requestHeaders = emptyMap(),
      requestBody = null,
      startedAtMs = 0L,
      startedAtMonotonicMs = 0L,
    ).also { it.wsFrames.addAll(frames.toList()) }
  }

  private fun textFrame(outgoing: Boolean, payload: String): WsFrame {
    return WsFrame(
      timestampMs = 0L,
      timestampMonoMs = 0L,
      outgoing = outgoing,
      opcode = 1,
      payload = payload,
      binary = false,
      truncated = false,
    )
  }
}
