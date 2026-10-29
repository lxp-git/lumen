package dev.lumen.inspector.helper

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialNotifierTest {
  private class RecordingPeers : ChromePeerManager() {
    val sent: MutableList<Any> = Collections.synchronizedList(ArrayList())
    val threads: MutableSet<String> = Collections.synchronizedSet(HashSet())
    @Volatile var gate: CountDownLatch? = null

    override fun sendNotificationToPeers(method: String, params: Any) {
      gate?.await(5, TimeUnit.SECONDS)
      threads.add(Thread.currentThread().name)
      sent.add(params)
    }
  }

  @Test
  fun sendsOffTheCallerInSubmissionOrder() {
    val peers = RecordingPeers()
    val notifier = SerialNotifier(peers, "test-dom-events")
    for (i in 0 until 500) notifier.sendNotification("DOM.childNodeInserted", i)
    assertTrue(notifier.awaitSent(5000))
    assertEquals((0 until 500).toList(), peers.sent.toList())
    assertEquals(setOf("test-dom-events"), peers.threads.toSet())
  }

  @Test
  fun awaitSentWaitsForEarlierNotifications() {
    val peers = RecordingPeers()
    val gate = CountDownLatch(1)
    peers.gate = gate
    val notifier = SerialNotifier(peers, "test-dom-events")
    notifier.sendNotification("DOM.childNodeRemoved", "first")
    assertFalse(notifier.awaitSent(100))
    gate.countDown()
    assertTrue(notifier.awaitSent(5000))
    assertEquals(listOf("first"), peers.sent.toList())
  }
}
