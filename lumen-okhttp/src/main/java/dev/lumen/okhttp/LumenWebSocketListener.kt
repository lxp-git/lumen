package dev.lumen.okhttp

import android.os.SystemClock
import dev.lumen.LumenAgent
import dev.lumen.common.LogRedirector
import dev.lumen.inspector.network.NetworkEventReporter
import dev.lumen.inspector.network.NetworkEventReporterImpl
import dev.lumen.inspector.network.SimpleBinaryInspectorWebSocketFrame
import dev.lumen.inspector.network.SimpleTextInspectorWebSocketFrame
import dev.lumen.store.NetworkRecord
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps a host [WebSocketListener] and mirrors the full CDP WebSocket lifecycle into
 * [NetworkEventReporter] + EventStore. The Gradle plugin injects
 * [LumenOkHttp.wrapWebSocketListener] at `OkHttpClient.newWebSocket` call sites.
 *
 * Request-id correlation: when the upgrade HTTP call passes through [LumenInterceptor],
 * it stashes the id in [pendingIds] keyed by URL so frames share the Network panel row.
 * Resolution is lazy because construction runs *before* that interceptor.
 */
class LumenWebSocketListener(
  private val url: String,
  private val delegate: WebSocketListener,
) : WebSocketListener() {

  private val eventReporter: NetworkEventReporter = NetworkEventReporterImpl.get()

  @Volatile
  private var requestId: String? = null

  private val closed = AtomicBoolean(false)
  private var createdEmitted = false
  private val framesPushed = java.util.concurrent.atomic.AtomicInteger()

  /** Used by [LumenWebSocket] to tag outbound frames with the same Network row. */
  fun requestIdForSend(): String = resolveRequestId()

  private fun resolveRequestId(): String {
    requestId?.let { return it }
    val id = takeUpgrade(url) ?: eventReporter.nextRequestId()
    requestId = id
    return id
  }

  override fun onOpen(webSocket: WebSocket, response: Response) {
    val id = resolveRequestId()
    ensureCreated()
    val store = if (LumenAgent.isStarted()) LumenAgent.store else null
    val headers = LinkedHashMap<String, String>()
    for (i in 0 until response.headers.size) {
      headers[response.headers.name(i)] = response.headers.value(i)
    }
    val updated = store?.network?.update(id) {
      it.statusCode = response.code
      it.statusText = response.message
      it.responseHeaders = headers
      it.resourceType = "WebSocket"
      it.isWebSocket = true
    }
    if (updated == null) {
      store?.network?.put(
        NetworkRecord(
          requestId = id,
          url = url,
          method = "GET",
          requestHeaders = emptyMap(),
          requestBody = null,
          startedAtMs = System.currentTimeMillis(),
          startedAtMonotonicMs = SystemClock.elapsedRealtime(),
          statusCode = response.code,
          statusText = response.message,
          responseHeaders = headers,
          resourceType = "WebSocket",
          isWebSocket = true,
        ),
      )
    }

    if (eventReporter.isEnabled) {
      eventReporter.webSocketHandshakeResponseReceived(
        object : NetworkEventReporter.InspectorWebSocketResponse {
          override fun requestId(): String = id
          override fun statusCode(): Int = response.code
          override fun reasonPhrase(): String = response.message
          override fun headerCount(): Int = response.headers.size
          override fun headerName(index: Int): String = response.headers.name(index)
          override fun headerValue(index: Int): String = response.headers.value(index)
          override fun firstHeaderValue(name: String): String? = response.header(name)
          override fun requestHeaders(): NetworkEventReporter.InspectorHeaders? = null
        },
      )
    }
    // Apps almost always send from onOpen(webSocket, …). That instance is
    // OkHttp's RealWebSocket, not the object returned by newWebSocket — wrap it.
    delegate.onOpen(wrapOutbound(webSocket), response)
  }

  private fun wrapOutbound(webSocket: WebSocket): WebSocket {
    if (webSocket is LumenWebSocket) return webSocket
    return LumenWebSocket(webSocket) { requestIdForSend() }
  }

  override fun onMessage(webSocket: WebSocket, text: String) {
    val id = resolveRequestId()
    ensureCreated()
    if (eventReporter.isEnabled) {
      val n = framesPushed.incrementAndGet()
      if (n <= 8 || n % 25 == 0) {
        val preview = if (text.length > 48) text.substring(0, 48) + "…" else text
        LogRedirector.i("LumenWS", "recv #$n id=$id $preview")
      }
      eventReporter.webSocketFrameReceived(
        SimpleTextInspectorWebSocketFrame(id, LumenWebSocketReporter.clipText(text)),
      )
    }
    store()?.network?.archiveWsFrame(id, outgoing = false, text = text)
    delegate.onMessage(wrapOutbound(webSocket), text)
  }

  override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
    val id = resolveRequestId()
    ensureCreated()
    if (eventReporter.isEnabled) {
      eventReporter.webSocketFrameReceived(
        SimpleBinaryInspectorWebSocketFrame(id, LumenWebSocketReporter.clipBytes(bytes.toByteArray())),
      )
    }
    store()?.network?.archiveWsFrame(id, outgoing = false, binary = bytes.toByteArray())
    delegate.onMessage(wrapOutbound(webSocket), bytes)
  }

  override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
    delegate.onClosing(webSocket, code, reason)
  }

  override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
    markClosed("closed $code $reason")
    delegate.onClosed(webSocket, code, reason)
  }

  override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
    // Never-opened upgrades (emulator 10.0.2.2, Socket.IO retries) must not
    // become extra WebSocket rows in Chrome. Only sockets that already
    // advertised webSocketCreated stay on the Network panel.
    if (createdEmitted) {
      val id = resolveRequestId()
      if (eventReporter.isEnabled) {
        eventReporter.webSocketFrameError(id, t.toString())
      }
      markClosed(t.toString())
    } else {
      // Consume this attempt's pending upgrade id even if no frame event ever
      // fired, otherwise the next reconnect on the same URL would pick up the
      // failed attempt's id and mis-attribute its frames.
      val id = requestId ?: takeUpgrade(url)?.also { requestId = it }
      id?.let { failedId ->
        store()?.network?.update(failedId) {
          it.failedReason = t.toString()
          it.finishedAtMs = System.currentTimeMillis()
        }
      }
    }
    delegate.onFailure(webSocket, t, response)
  }

  private fun ensureCreated() {
    if (createdEmitted) return
    createdEmitted = true
    val id = resolveRequestId()
    val store = if (LumenAgent.isStarted()) LumenAgent.store else null
    if (store?.network?.get(id) == null) {
      store?.network?.put(
        NetworkRecord(
          requestId = id,
          url = url,
          method = "GET",
          requestHeaders = emptyMap(),
          requestBody = null,
          startedAtMs = System.currentTimeMillis(),
          startedAtMonotonicMs = SystemClock.elapsedRealtime(),
          resourceType = "WebSocket",
          isWebSocket = true,
        ),
      )
    } else {
      store.network.update(id) {
        it.resourceType = "WebSocket"
        it.isWebSocket = true
      }
    }
    if (eventReporter.isEnabled) {
      eventReporter.webSocketCreated(id, url)
      eventReporter.webSocketWillSendHandshakeRequest(
        object : NetworkEventReporter.InspectorWebSocketRequest {
          override fun id(): String = id
          override fun friendlyName(): String = url
          override fun headerCount(): Int = 0
          override fun headerName(index: Int): String = ""
          override fun headerValue(index: Int): String = ""
          override fun firstHeaderValue(name: String): String? = null
        },
      )
    }
  }

  private fun store() = if (LumenAgent.isStarted()) LumenAgent.store else null

  private fun markClosed(reason: String) {
    if (!closed.compareAndSet(false, true)) return
    if (!createdEmitted) return
    val id = resolveRequestId()
    val store = if (LumenAgent.isStarted()) LumenAgent.store else null
    store?.network?.update(id) {
      it.finishedAtMs = System.currentTimeMillis()
      if (it.failedReason == null && !reason.startsWith("closed")) {
        it.failedReason = reason
      }
    }
    if (eventReporter.isEnabled) {
      eventReporter.webSocketClosed(id)
    }
  }

  companion object {
    /** URL → FIFO of requestIds. Socket.IO reconnects reuse the same URL. */
    private val pendingIds = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<String>>()

    internal fun rememberUpgrade(url: String, requestId: String) {
      pendingIds.getOrPut(url) { java.util.concurrent.ConcurrentLinkedQueue() }.add(requestId)
    }

    internal fun takeUpgrade(url: String): String? = pendingIds[url]?.poll()
  }
}

/** Outbound frame reporter used by [LumenWebSocket.send]. */
object LumenWebSocketReporter {
  private val eventReporter: NetworkEventReporter = NetworkEventReporterImpl.get()

  @JvmStatic
  fun frameSentText(requestId: String, text: String) {
    if (eventReporter.isEnabled) {
      eventReporter.webSocketFrameSent(SimpleTextInspectorWebSocketFrame(requestId, clipText(text)))
    }
    if (LumenAgent.isStarted()) {
      LumenAgent.store?.network?.archiveWsFrame(requestId, outgoing = true, text = text)
    }
  }

  @JvmStatic
  fun frameSentBinary(requestId: String, payload: ByteArray) {
    if (eventReporter.isEnabled) {
      eventReporter.webSocketFrameSent(
        SimpleBinaryInspectorWebSocketFrame(requestId, clipBytes(payload)),
      )
    }
    if (LumenAgent.isStarted()) {
      LumenAgent.store?.network?.archiveWsFrame(requestId, outgoing = true, binary = payload)
    }
  }

  @JvmStatic
  fun clipText(text: String): String {
    val max = if (LumenAgent.isStarted()) LumenAgent.config.maxWsFrameChars else 16_384
    return if (text.length <= max) text else text.substring(0, max) + "…[truncated]"
  }

  @JvmStatic
  fun clipBytes(payload: ByteArray): ByteArray {
    val max = if (LumenAgent.isStarted()) LumenAgent.config.maxWsFrameChars else 16_384
    return if (payload.size <= max) payload else payload.copyOf(max)
  }
}
