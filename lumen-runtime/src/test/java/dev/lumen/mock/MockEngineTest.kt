package dev.lumen.mock

import dev.lumen.LumenConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    Thread.sleep(50)
    engine.fulfillRequest("1", 200, listOf("Content-Type" to "application/json"), body = null)
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
    Thread.sleep(50)
    val handle = engine.openBodyStream("1")
    assertEquals("stream-body", String(engine.readStream(handle!!)!!))
    engine.continueRequest("1")
    executor.shutdownNow()
    assertFalse(engine.shouldPauseForFetch("https://example.com", "Request"))
  }
}
