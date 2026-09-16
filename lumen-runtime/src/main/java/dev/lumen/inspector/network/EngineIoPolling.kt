package dev.lumen.inspector.network

import android.os.SystemClock
import dev.lumen.LumenAgent
import dev.lumen.common.LogRedirector
import dev.lumen.store.NetworkRecord
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Promotes OkHttp Engine.IO / Socket.IO `transport=polling` exchanges onto one
 * synthetic WebSocket Network row so Chrome Messages shows the packet stream.
 *
 * HTTP rows stay as they are (same as browser DevTools). Websocket upgrades are
 * a separate row via [dev.lumen.okhttp.LumenWebSocketListener] — not merged.
 */
object EngineIoPolling {
  private const val MAX_COPY_BYTES = 512 * 1024

  internal val sessions = EngineIoSessionTable()

  private val eventReporter: NetworkEventReporter = NetworkEventReporterImpl.get()

  @JvmStatic
  fun isPollingUrl(url: String): Boolean = EngineIoPayload.isPollingUrl(url)

  @JvmStatic
  fun captureOutgoing(url: String, body: String?) {
    try {
      if (!isPollingUrl(url)) return
      val session = sessionFor(url)
      ensureCreated(session, url)
      if (body.isNullOrEmpty()) return
      emitPackets(session, body, outgoing = true)
    } catch (t: Throwable) {
      LogRedirector.w("LumenWS", "polling decode failed: ${t.message}")
    }
  }

  @JvmStatic
  fun captureIncoming(url: String, body: String) {
    try {
      if (!isPollingUrl(url)) return
      val session = sessionFor(url)
      ensureCreated(session, url)
      emitPackets(session, body, outgoing = false)
    } catch (t: Throwable) {
      LogRedirector.w("LumenWS", "polling decode failed: ${t.message}")
    }
  }

  @JvmStatic
  fun wrapIncoming(url: String, stream: InputStream): InputStream {
    if (!isPollingUrl(url)) return stream
    return CollectingInputStream(stream, MAX_COPY_BYTES) { bytes ->
      if (bytes.isEmpty()) return@CollectingInputStream
      try {
        captureIncoming(url, String(bytes, Charsets.UTF_8))
      } catch (t: Throwable) {
        LogRedirector.w("LumenWS", "polling decode failed: ${t.message}")
      }
    }
  }

  private fun sessionFor(url: String): EngineIoSessionTable.Session {
    for (dead in sessions.evictStale()) closeSession(dead)
    return sessions.getOrCreate(url) { eventReporter.nextRequestId() }
  }

  private fun emitPackets(
    session: EngineIoSessionTable.Session,
    payload: String,
    outgoing: Boolean,
  ) {
    val packets = EngineIoPayload.decode(payload)
    for (packet in packets) {
      if (!EngineIoPayload.isEngineIoPacket(packet)) continue
      EngineIoPayload.sidFromOpenPacket(packet)?.let { sessions.attachSid(session, it) }
      pushFrame(session.requestId, packet, outgoing)
      if (EngineIoPayload.isClosePacket(packet)) {
        closeSession(session)
      }
    }
  }

  private fun pushFrame(requestId: String, packet: String, outgoing: Boolean) {
    val clipped = clip(packet)
    if (LumenAgent.isStarted()) {
      LumenAgent.store?.network?.archiveWsFrame(
        requestId,
        outgoing = outgoing,
        text = clipped,
      )
    }
    if (!eventReporter.isEnabled) return
    val frame = SimpleTextInspectorWebSocketFrame(requestId, clipped)
    if (outgoing) {
      eventReporter.webSocketFrameSent(frame)
    } else {
      eventReporter.webSocketFrameReceived(frame)
    }
  }

  private fun ensureCreated(session: EngineIoSessionTable.Session, url: String) {
    session.url = url
    persistWsRecord(session.requestId, url)
    if (!eventReporter.isEnabled) return
    // isEnabled is true as soon as the agent starts; webSocketCreated no-ops
    // without Network peers. Don't latch created until a peer can receive it.
    val peers = NetworkPeerManager.getInstanceOrNull()
    if (peers == null || !peers.hasRegisteredPeers()) return
    if (!session.created.compareAndSet(false, true)) return
    LogRedirector.i("LumenWS", "polling session ${session.requestId} $url")
    eventReporter.webSocketCreated(session.requestId, url)
  }

  private fun persistWsRecord(id: String, url: String) {
    val store = if (LumenAgent.isStarted()) LumenAgent.store else null
    if (store == null) return
    if (store.network.get(id) == null) {
      store.network.put(
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
  }

  private fun closeSession(session: EngineIoSessionTable.Session) {
    if (!sessions.close(session)) return
    val id = session.requestId
    if (LumenAgent.isStarted()) {
      LumenAgent.store?.network?.update(id) {
        it.finishedAtMs = System.currentTimeMillis()
      }
    }
    if (eventReporter.isEnabled) {
      eventReporter.webSocketClosed(id)
    }
  }

  private fun clip(text: String): String {
    val max = if (LumenAgent.isStarted()) LumenAgent.config.maxWsFrameChars else 16_384
    return if (text.length <= max) text else text.substring(0, max) + "…[truncated]"
  }

  private class CollectingInputStream(
    private val upstream: InputStream,
    private val maxCopy: Int,
    private val onComplete: (ByteArray) -> Unit,
  ) : InputStream() {
    private val copy = ByteArrayOutputStream()
    private val finished = AtomicBoolean(false)

    override fun read(): Int {
      val b = upstream.read()
      if (b == -1) {
        finish()
      } else if (copy.size() < maxCopy) {
        copy.write(b)
      }
      return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      val n = upstream.read(b, off, len)
      if (n == -1) {
        finish()
      } else if (n > 0 && copy.size() < maxCopy) {
        val room = maxCopy - copy.size()
        copy.write(b, off, minOf(n, room))
      }
      return n
    }

    override fun available(): Int = upstream.available()

    override fun close() {
      try {
        upstream.close()
      } finally {
        finish()
      }
    }

    private fun finish() {
      if (!finished.compareAndSet(false, true)) return
      try {
        onComplete(copy.toByteArray())
      } catch (_: IOException) {
      }
    }
  }
}
