package dev.lumen.inspector.protocol.module

import dev.lumen.inspector.helper.ChromePeerManager
import dev.lumen.inspector.helper.PeerRegistrationListener
import dev.lumen.inspector.jsonrpc.JsonRpcPeer
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod
import dev.lumen.store.EventStore
import dev.lumen.store.LogArchive
import dev.lumen.store.LogCatLine
import dev.lumen.store.LogEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * CDP Log domain backed by [EventStore.logs]. Replays a Console-safe page on enable,
 * then streams live lines while the active segment is "latest". Every logcat
 * priority (V/D/I/W/E) is forwarded; Android `D`/`V` are promoted to CDP `info`
 * because Chrome's Default levels hide `verbose`.
 *
 * Segment switches (notification or `Lumen.setActiveLogSegment`) first emit a
 * `Runtime.consoleAPICalled` of type `clear` so Chrome Console replaces the page
 * instead of stacking another 5k lines.
 */
class Log(
  private val store: EventStore,
) : ChromeDevtoolsDomain {

  private val peers = ChromePeerManager()

  private val logListener = object : LogArchive.Listener {
    override fun onLogEntry(entry: LogEntry) {
      if (peers.hasRegisteredPeers() && store.logs.activeSegmentId == null) {
        peers.sendNotificationToPeers("Log.entryAdded", entryAddedParams(entry))
      }
    }

    override fun onSegmentChanged(segmentId: String?) {
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

      override fun onPeerUnregistered(peer: JsonRpcPeer) {}
    })
    store.logs.addListener(logListener)
  }

  @ChromeDevtoolsMethod
  fun enable(peer: JsonRpcPeer, params: JSONObject?) {
    peers.addPeer(peer)
  }

  @ChromeDevtoolsMethod
  fun disable(peer: JsonRpcPeer, params: JSONObject?) {
    peers.removePeer(peer)
  }

  @ChromeDevtoolsMethod
  fun clear(peer: JsonRpcPeer, params: JSONObject?) {
    store.logs.clearAll()
  }

  private fun replayPage(peer: JsonRpcPeer) {
    for (entry in store.logs.pageForReplay()) {
      peer.invokeMethod("Log.entryAdded", entryAddedParams(entry), null)
    }
  }

  private fun replayToPeers() {
    for (entry in store.logs.pageForReplay()) {
      peers.sendNotificationToPeers("Log.entryAdded", entryAddedParams(entry))
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
    val entry = LogEntry(
      System.currentTimeMillis().toDouble(),
      "info",
      "─────── Lumen: viewing $label ───────",
    )
    peers.sendNotificationToPeers("Log.entryAdded", entryAddedParams(entry))
  }

  private fun entryAddedParams(entry: LogEntry): JSONObject =
    JSONObject().put(
      "entry",
      JSONObject()
        .put("source", "other")
        .put("level", LogCatLine.chromeLevel(entry.level))
        .put("text", entry.text)
        .put("timestamp", entry.timestampMs),
    )
}
