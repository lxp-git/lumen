package dev.lumen.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectProxyMessagesTest {
  @Test
  fun keepsEnableAndTargetAttach() {
    assertTrue(InspectProxyMessages.shouldKeep("Network.enable"))
    assertTrue(InspectProxyMessages.shouldKeep("Log.enable"))
    assertTrue(InspectProxyMessages.shouldKeep("Fetch.enable"))
    assertTrue(InspectProxyMessages.shouldKeep("Target.setAutoAttach"))
    assertTrue(InspectProxyMessages.shouldKeep("Target.setDiscoverTargets"))
    assertFalse(InspectProxyMessages.shouldKeep("Network.getResponseBody"))
    assertFalse(InspectProxyMessages.shouldKeep("Runtime.evaluate"))
  }

  @Test
  fun recordDedupesByMethod() {
    val recorded = ArrayList<String>()
    InspectProxyMessages.record(recorded, """{"id":1,"method":"Network.enable","params":{}}""")
    InspectProxyMessages.record(recorded, """{"id":2,"method":"Log.enable"}""")
    InspectProxyMessages.record(recorded, """{"id":3,"method":"Network.enable","params":{"maxTotalBufferSize":1}}""")
    InspectProxyMessages.record(recorded, """{"id":4,"method":"Network.getResponseBody"}""")
    assertEquals(2, recorded.size)
    assertTrue(recorded[0].contains("Log.enable"))
    assertTrue(recorded[1].contains("maxTotalBufferSize"))
  }

  @Test
  fun rewriteIdAndFilterReplayResponses() {
    val rewritten = InspectProxyMessages.rewriteId("""{"id":7,"method":"Network.enable"}""", 990001)
    assertTrue(rewritten.contains("990001"))
    assertFalse(rewritten.contains("\"id\":7"))
    assertTrue(InspectProxyMessages.isReplayResponse("""{"id":990001,"result":{}}""", setOf(990001)))
    assertFalse(InspectProxyMessages.isReplayResponse("""{"id":8,"result":{}}""", setOf(990001)))
    assertFalse(InspectProxyMessages.isReplayResponse("""{"method":"Network.requestWillBeSent"}""", setOf(990001)))
  }
}
