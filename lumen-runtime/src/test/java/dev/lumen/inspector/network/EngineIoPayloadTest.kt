package dev.lumen.inspector.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineIoPayloadTest {

  @Test
  fun detectsPollingUrls() {
    assertTrue(
      EngineIoPayload.isPollingUrl(
        "https://chat.example.com/socket.io/?EIO=4&transport=polling",
      ),
    )
    assertTrue(
      EngineIoPayload.isPollingUrl(
        "https://chat.example.com/socket.io/?transport=polling&EIO=3&sid=abc",
      ),
    )
    assertTrue(
      EngineIoPayload.isPollingUrl(
        "https://chat.example.com/engine.io/?transport=polling",
      ),
    )
    assertFalse(
      EngineIoPayload.isPollingUrl(
        "https://chat.example.com/socket.io/?EIO=4&transport=websocket",
      ),
    )
    assertFalse(
      EngineIoPayload.isPollingUrl("https://api.example.com/v1/items?transport=polling"),
    )
    assertFalse(EngineIoPayload.isPollingUrl("https://api.example.com/v1/items"))
  }

  @Test
  fun sidAndOriginFromUrl() {
    val handshake = "https://chat.example.com/socket.io/?EIO=4&transport=polling"
    assertNull(EngineIoPayload.sidFromUrl(handshake))
    assertEquals(
      "https://chat.example.com/socket.io/",
      EngineIoPayload.originKey(handshake),
    )
    assertEquals(
      "abc+def",
      EngineIoPayload.sidFromUrl(
        "https://chat.example.com/socket.io/?EIO=4&transport=polling&sid=abc+def",
      ),
    )
  }

  @Test
  fun decodeEio4SingleAndSeparated() {
    assertEquals(
      listOf("""0{"sid":"lv_VI97HAXpY6yYWAAAC"}"""),
      EngineIoPayload.decode("""0{"sid":"lv_VI97HAXpY6yYWAAAC"}"""),
    )
    assertEquals(
      listOf("4hello", "2"),
      EngineIoPayload.decode("4hello" + EngineIoPayload.RECORD_SEPARATOR + "2"),
    )
    assertEquals(listOf("""42["chat",{"text":"hi"}]"""), EngineIoPayload.decode("""42["chat",{"text":"hi"}]"""))
  }

  @Test
  fun decodeEio3LengthPrefixed() {
    val open = """0{"sid":"x"}"""
    val encoded = "${open.length}:$open"
    assertEquals(listOf(open), EngineIoPayload.decode(encoded))
    assertEquals(
      listOf("4hello", "2probe"),
      EngineIoPayload.decode("6:4hello6:2probe"),
    )
  }

  @Test
  fun skipsXhrOkAndEmpty() {
    assertTrue(EngineIoPayload.decode("ok").isEmpty())
    assertTrue(EngineIoPayload.decode("").isEmpty())
  }

  @Test
  fun openPacketSidAndClose() {
    val open = """0{"sid":"lv_VI97HAXpY6yYWAAAC","upgrades":["websocket"]}"""
    assertEquals("lv_VI97HAXpY6yYWAAAC", EngineIoPayload.sidFromOpenPacket(open))
    assertNull(EngineIoPayload.sidFromOpenPacket("""42["hello"]"""))
    assertTrue(EngineIoPayload.isClosePacket("1"))
    assertFalse(EngineIoPayload.isClosePacket("41"))
    assertTrue(EngineIoPayload.isEngineIoPacket("2"))
    assertTrue(EngineIoPayload.isEngineIoPacket("b4AQID"))
    assertFalse(EngineIoPayload.isEngineIoPacket("ok"))
  }

  @Test
  fun garbageLengthPrefixFallsBackToWholeBody() {
    assertEquals(listOf("99:short"), EngineIoPayload.decode("99:short"))
  }

  @Test
  fun lengthPrefixNearIntMaxDoesNotThrow() {
    val overflow = "2147483647:x"
    assertEquals(listOf(overflow), EngineIoPayload.decode(overflow))
    val tooLong = Long.MAX_VALUE.toString() + ":x"
    assertEquals(listOf(tooLong), EngineIoPayload.decode(tooLong))
  }
}

class EngineIoSessionTableTest {

  @Test
  fun handshakeThenSidReusesSession() {
    val table = EngineIoSessionTable()
    var n = 0
    val newId = { "id-${++n}" }
    val handshake = "https://api.example.com/socket.io/?EIO=4&transport=polling"
    val first = table.getOrCreate(handshake, newId)
    assertEquals("id-1", first.requestId)
    table.attachSid(first, "abc")
    val polled = "https://api.example.com/socket.io/?EIO=4&transport=polling&sid=abc"
    val second = table.getOrCreate(polled, newId)
    assertSame(first, second)
    assertEquals("id-1", second.requestId)
    assertEquals(1, n)
  }

  @Test
  fun sidOnUrlWithoutHandshakeStillCreatesOneRow() {
    val table = EngineIoSessionTable()
    var n = 0
    val url = "https://api.example.com/socket.io/?EIO=4&transport=polling&sid=s1"
    val a = table.getOrCreate(url, { "id-${++n}" })
    val b = table.getOrCreate(url, { "id-${++n}" })
    assertSame(a, b)
    assertEquals("id-1", a.requestId)
  }

  @Test
  fun differentSidsAreDifferentSessions() {
    val table = EngineIoSessionTable()
    var n = 0
    val newId = { "id-${++n}" }
    val a = table.getOrCreate(
      "https://api.example.com/socket.io/?EIO=4&transport=polling&sid=one",
      newId,
    )
    val b = table.getOrCreate(
      "https://api.example.com/socket.io/?EIO=4&transport=polling&sid=two",
      newId,
    )
    assertEquals("id-1", a.requestId)
    assertEquals("id-2", b.requestId)
  }

  @Test
  fun closeAllowsReconnectToAllocateNewId() {
    val table = EngineIoSessionTable()
    var n = 0
    val newId = { "id-${++n}" }
    val url = "https://api.example.com/socket.io/?EIO=4&transport=polling&sid=abc"
    val first = table.getOrCreate(url, newId)
    assertTrue(table.close(first))
    assertFalse(table.close(first))
    val second = table.getOrCreate(url, newId)
    assertEquals("id-2", second.requestId)
  }

  @Test
  fun attachSidDoesNotOverwriteDifferentSid() {
    val table = EngineIoSessionTable()
    val handshake = "https://api.example.com/socket.io/?EIO=4&transport=polling"
    val session = table.getOrCreate(handshake) { "id-1" }
    table.attachSid(session, "abc")
    table.attachSid(session, "other")
    assertEquals("abc", session.sid)
    val other = table.getOrCreate("$handshake&sid=other") { "id-2" }
    assertEquals("id-2", other.requestId)
  }

  @Test
  fun concurrentSidPromoteDoesNotDropHandshake() {
    val handshakeUrl = "https://api.example.com/socket.io/?EIO=4&transport=polling"
    val polledUrl = "$handshakeUrl&sid=abc"
    repeat(200) {
      val table = EngineIoSessionTable()
      val handshake = table.getOrCreate(handshakeUrl) { "handshake" }
      val barrier = java.util.concurrent.CyclicBarrier(2)
      val t1 = Thread {
        barrier.await()
        table.attachSid(handshake, "abc")
      }
      val t2 = Thread {
        barrier.await()
        table.getOrCreate(polledUrl) { "poll" }
      }
      t1.start()
      t2.start()
      t1.join()
      t2.join()
      val live = table.getOrCreate(polledUrl) { "lost" }
      assertSame("iteration $it dropped handshake session", handshake, live)
      assertEquals("handshake", live.requestId)
    }
  }

  @Test
  fun staleSessionIsEvicted() {
    var now = 0L
    val table = EngineIoSessionTable(nowMs = { now }, ttlMs = 1000L, maxSessions = 64)
    val url = "https://api.example.com/socket.io/?EIO=4&transport=polling&sid=abc"
    val first = table.getOrCreate(url) { "id-1" }
    now = 1000L
    val evicted = table.evictStale()
    assertEquals(1, evicted.size)
    assertSame(first, evicted[0])
    val second = table.getOrCreate(url) { "id-2" }
    assertEquals("id-2", second.requestId)
  }

  @Test
  fun recentSessionIsKept() {
    var now = 0L
    val table = EngineIoSessionTable(nowMs = { now }, ttlMs = 1000L)
    val url = "https://api.example.com/socket.io/?EIO=4&transport=polling&sid=abc"
    val first = table.getOrCreate(url) { "id-1" }
    now = 999L
    assertTrue(table.evictStale().isEmpty())
    val second = table.getOrCreate(url) { "id-2" }
    assertSame(first, second)
  }

  @Test
  fun capDropsOldest() {
    var now = 0L
    val table = EngineIoSessionTable(nowMs = { now }, ttlMs = 1_000_000L, maxSessions = 2)
    val a = table.getOrCreate(
      "https://a.example/socket.io/?EIO=4&transport=polling&sid=a",
    ) { "a" }
    now = 1
    table.getOrCreate(
      "https://b.example/socket.io/?EIO=4&transport=polling&sid=b",
    ) { "b" }
    now = 2
    table.getOrCreate(
      "https://c.example/socket.io/?EIO=4&transport=polling&sid=c",
    ) { "c" }
    val evicted = table.evictStale()
    assertEquals(1, evicted.size)
    assertSame(a, evicted[0])
  }
}
