package dev.lumen.mock

import dev.lumen.LumenConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MockEngineTest {

  @get:Rule
  val tmp = TemporaryFolder()

  /** Pauses [url] on a background thread and returns its fetchId once paused. */
  private fun pauseInBackground(
    engine: MockEngine,
    url: String,
    method: String = "GET",
  ): String {
    val fetchId = AtomicReference<String>()
    val paused = CountDownLatch(1)
    val listener = MockEngine.FetchListener { p ->
      if (p.url == url) {
        fetchId.set(p.fetchId)
        paused.countDown()
      }
    }
    engine.addFetchListener(listener)
    val executor = Executors.newSingleThreadExecutor()
    executor.submit {
      engine.pause(
        networkId = "net-$url",
        url = url,
        method = method,
        headers = emptyMap(),
        postData = null,
        requestStage = "Response",
        timeoutMs = 2_000,
      )
    }
    executor.shutdown()
    assertTrue(paused.await(2, TimeUnit.SECONDS))
    engine.removeFetchListener(listener)
    return fetchId.get()
  }

  @Test
  fun chromeHttpsQuestionMarkPatternMatchesHttpsUrl() {
    val engine = MockEngine(LumenConfig.DEFAULT)
    engine.enableFetch(
      listOf(
        MockEngine.Pattern(
          urlPattern =
            "http?://staging-mobile-backend.flowgpt.com/prompt/abc/new-user-autoreply",
          requestStage = "Response",
        )
      )
    )
    assertTrue(
      engine.shouldPauseForFetch(
        "https://staging-mobile-backend.flowgpt.com/prompt/abc/new-user-autoreply",
        "Response",
      )
    )
    assertFalse(
      engine.shouldPauseForFetch(
        "https://staging-mobile-backend.flowgpt.com/prompt/abc/new-user-autoreply",
        "Request",
      )
    )
  }

  @Test
  fun emptyEnablePatternsCoverRequestAndResponse() {
    val engine = MockEngine(LumenConfig.DEFAULT)
    engine.enableFetch(emptyList())
    assertTrue(engine.shouldPauseForFetch("https://example.com/a", "Request"))
    assertTrue(engine.shouldPauseForFetch("https://example.com/a", "Response"))
  }

  @Test
  fun fulfillUsesOriginalBodyWhenChromeOmitsBody() {
    val engine = MockEngine(LumenConfig.DEFAULT)
    engine.enableFetch(listOf(MockEngine.Pattern("*", requestStage = "Response")))
    val original = """{"ok":true}""".toByteArray()
    val fetchId = AtomicReference<String>()
    val pausedLatch = CountDownLatch(1)
    engine.addFetchListener { paused ->
      fetchId.set(paused.fetchId)
      pausedLatch.countDown()
    }
    val executor = Executors.newSingleThreadExecutor()
    val future =
      executor.submit<MockEngine.Decision> {
        engine.pause(
          networkId = "net-1",
          url = "https://example.com/api",
          method = "GET",
          headers = emptyMap(),
          postData = null,
          requestStage = "Response",
          responseStatusCode = 200,
          responseBody = original,
          timeoutMs = 2_000,
        )
      }
    assertTrue(pausedLatch.await(2, TimeUnit.SECONDS))
    engine.fulfillRequest(fetchId.get(), 200, listOf("Content-Type" to "application/json"), body = null)
    val decision = future.get(2, TimeUnit.SECONDS)
    executor.shutdownNow()
    assertTrue(decision is MockEngine.Decision.Fulfill)
    assertArrayEquals(original, (decision as MockEngine.Decision.Fulfill).body)
  }

  @Test
  fun fulfillAcceptsNetworkIdAsWellAsFetchId() {
    val engine = MockEngine(LumenConfig.DEFAULT)
    engine.enableFetch(listOf(MockEngine.Pattern("*", requestStage = "Response")))
    val executor = Executors.newSingleThreadExecutor()
    val future =
      executor.submit<MockEngine.Decision> {
        engine.pause(
          networkId = "okhttp-7",
          url = "https://example.com/api",
          method = "POST",
          headers = emptyMap(),
          postData = null,
          requestStage = "Response",
          timeoutMs = 2_000,
        )
      }
    Thread.sleep(50)
    engine.fulfillRequest("okhttp-7", 201, emptyList(), "over".toByteArray())
    val decision = future.get(2, TimeUnit.SECONDS)
    executor.shutdownNow()
    assertTrue(decision is MockEngine.Decision.Fulfill)
    assertEquals(201, (decision as MockEngine.Decision.Fulfill).responseCode)
    assertEquals("over", String(decision.body))
  }

  @Test
  fun streamHandleServesPausedBody() {
    val engine = MockEngine(LumenConfig.DEFAULT)
    engine.enableFetch(listOf(MockEngine.Pattern("*", requestStage = "Response")))
    val fetchId = AtomicReference<String>()
    val pausedLatch = CountDownLatch(1)
    engine.addFetchListener { paused ->
      fetchId.set(paused.fetchId)
      pausedLatch.countDown()
    }
    val executor = Executors.newSingleThreadExecutor()
    executor.submit {
      engine.pause(
        networkId = "n",
        url = "https://example.com",
        method = "GET",
        headers = emptyMap(),
        postData = null,
        requestStage = "Response",
        responseBody = "stream-body".toByteArray(),
        timeoutMs = 2_000,
      )
    }
    assertTrue(pausedLatch.await(2, TimeUnit.SECONDS))
    val handle = engine.openBodyStream(fetchId.get())
    assertEquals("stream-body", String(engine.readStream(handle!!)!!))
    engine.continueRequest(fetchId.get())
    executor.shutdownNow()
    assertFalse(engine.shouldPauseForFetch("https://example.com", "Request"))
  }

  @Test
  fun numericNetworkIdNeverResolvesAnotherRequestsPause() {
    val engine = MockEngine(LumenConfig.DEFAULT)
    engine.enableFetch(listOf(MockEngine.Pattern("*", requestStage = "Response")))
    val pausedByNetworkId = ConcurrentHashMap<String, MockEngine.PausedRequest>()
    val pausedA = CountDownLatch(1)
    val pausedB = CountDownLatch(1)
    engine.addFetchListener { paused ->
      pausedByNetworkId[paused.networkId] = paused
      when (paused.networkId) {
        "77" -> pausedA.countDown()
        "1" -> pausedB.countDown()
      }
    }
    val executor = Executors.newFixedThreadPool(2)
    val futureA = executor.submit<MockEngine.Decision> {
      engine.pause(
        networkId = "77",
        url = "https://example.com/a",
        method = "GET",
        headers = emptyMap(),
        postData = null,
        requestStage = "Response",
        timeoutMs = 5_000,
      )
    }
    assertTrue(pausedA.await(2, TimeUnit.SECONDS))
    // Request B's networkId "1" equals the counter value request A drew for its
    // fetchId. Resolving by "1" must reach B via the networkId map, never A.
    val futureB = executor.submit<MockEngine.Decision> {
      engine.pause(
        networkId = "1",
        url = "https://example.com/b",
        method = "GET",
        headers = emptyMap(),
        postData = null,
        requestStage = "Response",
        timeoutMs = 5_000,
      )
    }
    assertTrue(pausedB.await(2, TimeUnit.SECONDS))
    engine.fulfillRequest("1", 200, emptyList(), "for-b".toByteArray())
    val decisionB = futureB.get(2, TimeUnit.SECONDS)
    assertTrue(decisionB is MockEngine.Decision.Fulfill)
    assertEquals("for-b", String((decisionB as MockEngine.Decision.Fulfill).body))
    // A must still be paused; release it by its real fetchId.
    engine.continueRequest(pausedByNetworkId.getValue("77").fetchId)
    val decisionA = futureA.get(2, TimeUnit.SECONDS)
    executor.shutdownNow()
    assertTrue(decisionA is MockEngine.Decision.Continue)
  }

  @Test
  fun recordedOverrideReplaysWithoutDevToolsAndAcrossRestart() {
    val dir = tmp.newFolder("mocks")
    val url = "https://api.example.com/user?id=1"
    val engine = MockEngine(LumenConfig.DEFAULT)
    engine.initRecordedRules(dir, recordByDefault = true)
    engine.enableFetch(listOf(MockEngine.Pattern("*", requestStage = "Response")))
    val fetchId = pauseInBackground(engine, url)
    engine.fulfillRequest(
      fetchId,
      201,
      listOf("Content-Type" to "application/json", "Content-Length" to "999"),
      """{"mock":true}""".toByteArray(),
    )

    // Replays in the same process with DevTools gone.
    engine.disableFetch()
    val live = engine.matchLocalRule(url, "GET")
    assertEquals("recorded", live!!.source)

    // Replays after a process restart (fresh engine, same directory).
    val restarted = MockEngine(LumenConfig.DEFAULT)
    restarted.initRecordedRules(dir, recordByDefault = false)
    val rule = restarted.matchLocalRule(url, "GET")
    assertEquals(201, rule!!.status)
    assertEquals("""{"mock":true}""", String(rule.bodyBytes()))
    // Stale Content-Length must not survive recording.
    assertFalse(rule.headers.keys.any { it.equals("Content-Length", ignoreCase = true) })
    // Exact URL + method keying: no accidental generalisation.
    assertNull(restarted.matchLocalRule("https://api.example.com/user?id=2", "GET"))
    assertNull(restarted.matchLocalRule(url, "POST"))
  }

  @Test
  fun binaryOverrideBodyRoundTripsThroughSidecarFile() {
    val dir = tmp.newFolder("mocks")
    val url = "https://cdn.example.com/logo.png"
    val binary = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0xFF.toByte(), 0xFE.toByte())
    val engine = MockEngine(LumenConfig.DEFAULT)
    engine.initRecordedRules(dir, recordByDefault = true)
    engine.enableFetch(listOf(MockEngine.Pattern("*", requestStage = "Response")))
    engine.fulfillRequest(pauseInBackground(engine, url), 200, listOf("Content-Type" to "image/png"), binary)

    assertTrue(dir.listFiles()!!.any { it.name.endsWith(".body") })
    val restarted = MockEngine(LumenConfig.DEFAULT)
    restarted.initRecordedRules(dir, recordByDefault = false)
    assertArrayEquals(binary, restarted.matchLocalRule(url, "GET")!!.bodyBytes())
  }

  @Test
  fun removeRuleDeletesPersistedFiles() {
    val dir = tmp.newFolder("mocks")
    val url = "https://api.example.com/flag"
    val engine = MockEngine(LumenConfig.DEFAULT)
    engine.initRecordedRules(dir, recordByDefault = true)
    engine.enableFetch(listOf(MockEngine.Pattern("*", requestStage = "Response")))
    engine.fulfillRequest(pauseInBackground(engine, url), 200, emptyList(), "on".toByteArray())

    val rule = engine.matchLocalRule(url, "GET")!!
    assertTrue(File(dir, "${rule.id}.json").exists())
    assertTrue(engine.removeRule(rule.id))
    assertFalse(File(dir, "${rule.id}.json").exists())
    assertNull(engine.matchLocalRule(url, "GET"))
  }

  @Test
  fun recordingDisabledByDefaultPersistsNothing() {
    val dir = tmp.newFolder("mocks")
    val url = "https://api.example.com/live"
    val engine = MockEngine(LumenConfig.DEFAULT)
    engine.initRecordedRules(dir, recordByDefault = false)
    engine.enableFetch(listOf(MockEngine.Pattern("*", requestStage = "Response")))
    engine.fulfillRequest(pauseInBackground(engine, url), 200, emptyList(), "tmp".toByteArray())

    assertTrue(dir.listFiles()!!.isEmpty())
    assertNull(engine.matchLocalRule(url, "GET"))
  }
}
