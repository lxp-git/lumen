package dev.lumen.inspector.helper

import dev.lumen.common.LogRedirector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Encodes and sends notifications to [peerManager]'s peers on one background thread, in the
 * order they were submitted.
 *
 * DOM events are produced on the main thread while it walks the view tree. Encoding them there
 * (reflection plus `JSONObject.toString()` of every inserted subtree, once per peer) held the UI
 * thread long enough to ANR when an Activity with a large view tree appeared.
 *
 * Params must not change after [sendNotification]: build a new event per call (never a pooled
 * one) out of plain values, never Views.
 */
class SerialNotifier(
  private val peerManager: ChromePeerManager,
  threadName: String,
) {
  private val executor = ThreadPoolExecutor(
    1, 1, IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(),
  ) { task ->
    Thread(task, threadName).apply {
      isDaemon = true
      // Android maps this to THREAD_PRIORITY_BACKGROUND, below the UI and render threads.
      priority = Thread.NORM_PRIORITY - 1
    }
  }.apply { allowCoreThreadTimeOut(true) }

  fun sendNotification(method: String, params: Any) {
    executor.execute {
      try {
        peerManager.sendNotificationToPeers(method, params)
      } catch (e: RuntimeException) {
        LogRedirector.w(TAG, "Dropped $method", e)
      }
    }
  }

  /**
   * Waits until everything submitted before this call has been handed to the peers' sockets.
   *
   * @return false if that took longer than [timeoutMs].
   */
  fun awaitSent(timeoutMs: Long): Boolean {
    val sent = CountDownLatch(1)
    executor.execute { sent.countDown() }
    return sent.await(timeoutMs, TimeUnit.MILLISECONDS)
  }

  private companion object {
    const val TAG = "LumenCDP"
    const val IDLE_TIMEOUT_SECONDS = 30L
  }
}
