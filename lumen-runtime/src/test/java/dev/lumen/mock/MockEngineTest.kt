package dev.lumen.mock

import dev.lumen.LumenConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MockEngineTest {

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
}
