package dev.lumen.okhttp

import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Delegating [WebSocket] that reports outbound frames to CDP while preserving host behavior.
 * Produced by [LumenOkHttp.wrapWebSocket] after [OkHttpClient.newWebSocket].
 */
class LumenWebSocket(
  private val delegate: WebSocket,
  private val requestIdProvider: () -> String?,
) : WebSocket {
  override fun request(): Request = delegate.request()

  override fun queueSize(): Long = delegate.queueSize()

  override fun send(text: String): Boolean {
    val id = requestIdProvider()
    if (id != null) {
      LumenWebSocketReporter.frameSentText(id, text)
    }
    return delegate.send(text)
  }

  override fun send(bytes: ByteString): Boolean {
    val id = requestIdProvider()
    if (id != null) {
      LumenWebSocketReporter.frameSentBinary(id, bytes.toByteArray())
    }
    return delegate.send(bytes)
  }

  override fun close(code: Int, reason: String?): Boolean = delegate.close(code, reason)

  override fun cancel() = delegate.cancel()
}
