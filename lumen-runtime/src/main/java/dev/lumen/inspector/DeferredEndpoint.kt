package dev.lumen.inspector

import android.os.SystemClock
import dev.lumen.websocket.SimpleEndpoint
import dev.lumen.websocket.SimpleSession
import java.util.concurrent.TimeUnit

/**
 * Accepts the WebSocket upgrade (HTTP 101) before CDP modules exist so Chrome's
 * 1s ADB HttpUpgrade can succeed. [onOpen] waits until [attach].
 */
class DeferredEndpoint : SimpleEndpoint {
  private val lock = Object()

  @Volatile
  private var delegate: SimpleEndpoint? = null

  fun attach(endpoint: SimpleEndpoint) {
    synchronized(lock) {
      delegate = endpoint
      lock.notifyAll()
    }
  }

  private fun awaitDelegate(): SimpleEndpoint {
    synchronized(lock) {
      val deadline = SystemClock.uptimeMillis() + ATTACH_TIMEOUT_MS
      while (delegate == null) {
        val left = deadline - SystemClock.uptimeMillis()
        check(left > 0) { "CDP modules not ready for Chrome reconnect" }
        lock.wait(left)
      }
      return delegate!!
    }
  }

  override fun onOpen(session: SimpleSession) {
    awaitDelegate().onOpen(session)
  }

  override fun onClose(session: SimpleSession, closeReasonCode: Int, closeReasonPhrase: String) {
    delegate?.onClose(session, closeReasonCode, closeReasonPhrase)
  }

  override fun onMessage(session: SimpleSession, message: String) {
    awaitDelegate().onMessage(session, message)
  }

  override fun onMessage(session: SimpleSession, message: ByteArray, messageLen: Int) {
    awaitDelegate().onMessage(session, message, messageLen)
  }

  override fun onError(session: SimpleSession, t: Throwable) {
    delegate?.onError(session, t)
  }

  private companion object {
    val ATTACH_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(8)
  }
}
