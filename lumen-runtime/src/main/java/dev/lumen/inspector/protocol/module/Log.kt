package dev.lumen.inspector.protocol.module

import dev.lumen.common.LogRedirector
import dev.lumen.inspector.helper.ChromePeerManager
import dev.lumen.inspector.helper.PeerRegistrationListener
import dev.lumen.inspector.jsonrpc.JsonRpcPeer
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod
import dev.lumen.store.EventStore
import dev.lumen.store.LogArchive
import dev.lumen.store.LogEntry
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** CDP Log domain: pack live lines so Console is not one event per logcat row. */
class Log(
  private val store: EventStore,
) : ChromeDevtoolsDomain {

  private val peers = ChromePeerManager()
  private val flushLock = Any()
  private val pending = ArrayList<LogEntry>()
  private var flushScheduled = false
  private var flushTask: ScheduledFuture<*>? = null
  private var epoch = 0

  private val flusher: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "lumen-log-cdp").apply { isDaemon = true }
    }

  private val logListener = object : LogArchive.Listener {
    override fun onLogEntry(entry: LogEntry) {
      if (peers.hasRegisteredPeers() && store.logs.activeSegmentId == null) {
        enqueueLive(entry)
      }
    }

    override fun onSegmentChanged(segmentId: String?) {
      clearPending()
      if (!peers.hasRegisteredPeers()) return
      emitClear()
      replayToPeers()
      emitBanner(segmentId)
    }
  }

  init {
    peers.setListener(object : PeerRegistrationListener {
      override fun onPeerRegistered(peer: JsonRpcPeer) {
        replayPage(peer)
      }

      override fun onPeerUnregistered(peer: JsonRpcPeer) {
        if (!peers.hasRegisteredPeers()) clearPending()
      }
    })
    store.logs.addListener(logListener)
  }

  @ChromeDevtoolsMethod
  fun enable(peer: JsonRpcPeer, params: JSONObject?) {
    peers.addPeer(peer)
  }

  @ChromeDevtoolsMethod
  fun disable(peer: JsonRpcPeer, params: JSONObject?) {
    // Immediate so the JSON-RPC disable ack cannot overtake this batch.
    flushPending(immediate = true)
    peers.removePeer(peer)
  }

  @ChromeDevtoolsMethod
  fun clear(peer: JsonRpcPeer, params: JSONObject?) {
    clearPending()
    store.logs.clearAll()
  }

  private fun enqueueLive(entry: LogEntry) {
    var flushNow = false
    synchronized(flushLock) {
      pending.add(entry)
      if (!flushScheduled) {
        try {
          flushTask = flusher.schedule(
            { flushPending(immediate = false) },
            LogBatches.FLUSH_WINDOW_MS,
            TimeUnit.MILLISECONDS,
          )
          flushScheduled = true
        } catch (_: RejectedExecutionException) {
          flushNow = true
        }
      }
    }
    if (flushNow) flushPending(immediate = false)
  }

  private fun flushPending(immediate: Boolean) {
    val batch: ArrayList<LogEntry>
    val capturedEpoch: Int
    synchronized(flushLock) {
      flushScheduled = false
      flushTask?.cancel(false)
      flushTask = null
      if (pending.isEmpty()) return
      batch = ArrayList(pending)
      pending.clear()
      capturedEpoch = epoch
    }
    if (!stillLive(capturedEpoch)) return
    emitPacked(batch) { packed ->
      if (!stillLive(capturedEpoch)) return@emitPacked
      val params = entryAddedParams(packed)
      if (immediate) {
        for (p in peers.copyReceivingPeers()) {
          p.invokeMethodImmediate("Log.entryAdded", params)
        }
      } else {
        peers.sendNotificationToPeers("Log.entryAdded", params)
      }
    }
  }

  private fun stillLive(capturedEpoch: Int): Boolean {
    synchronized(flushLock) {
      if (capturedEpoch != epoch) return false
    }
    return peers.hasRegisteredPeers() && store.logs.activeSegmentId == null
  }

  private fun clearPending() {
    synchronized(flushLock) {
      pending.clear()
      epoch++
      flushScheduled = false
      flushTask?.cancel(false)
      flushTask = null
    }
  }

  private fun replayPage(peer: JsonRpcPeer) {
    emitPacked(store.logs.pageForReplay()) { packed ->
      peer.invokeMethod("Log.entryAdded", entryAddedParams(packed), null)
    }
  }

  private fun replayToPeers() {
    emitPacked(store.logs.pageForReplay()) { packed ->
      peers.sendNotificationToPeers("Log.entryAdded", entryAddedParams(packed))
    }
  }

  private inline fun emitPacked(
    entries: List<LogEntry>,
    send: (LogBatches.Packed) -> Unit,
  ) {
    for (packed in LogBatches.pack(entries)) {
      try {
        send(packed)
      } catch (t: Throwable) {
        LogRedirector.w(TAG, "failed to deliver packed log event", t)
      }
    }
  }

  private fun emitClear() {
    val params = JSONObject()
      .put("type", "clear")
      .put("args", JSONArray())
      .put("executionContextId", 1)
      .put("timestamp", System.currentTimeMillis().toDouble())
    peers.sendNotificationToPeers("Runtime.consoleAPICalled", params)
  }

  private fun emitBanner(segmentId: String?) {
    val label = segmentId ?: "live"
    emitPacked(
      listOf(
        LogEntry(
          System.currentTimeMillis().toDouble(),
          "info",
          "─────── Lumen: viewing $label ───────",
        ),
      ),
    ) { packed ->
      peers.sendNotificationToPeers("Log.entryAdded", entryAddedParams(packed))
    }
  }

  private fun entryAddedParams(packed: LogBatches.Packed): JSONObject =
    JSONObject().put(
      "entry",
      JSONObject()
        .put("source", "other")
        .put("level", packed.level)
        .put("text", packed.text)
        .put("timestamp", packed.timestampMs),
    )

  private companion object {
    const val TAG = "LumenLog"
  }
}
