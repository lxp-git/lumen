package dev.lumen.inspector.network

import dev.lumen.LumenAgent
import dev.lumen.inspector.jsonrpc.JsonRpcPeer
import dev.lumen.store.NetworkRecord
import org.json.JSONArray
import org.json.JSONObject
import java.nio.channels.NotYetConnectedException

/**
 * Chrome's Network filter only matches URL, and Network Search (Ctrl/Cmd+F)
 * skips WebSocket rows because `ResourceType.WebSocket.isTextType` is false —
 * it never calls [dev.lumen.inspector.protocol.module.Network.searchInResponseBody]
 * even though frame payloads are archived on the real socket.
 *
 * One synthetic XHR per socket holds a text transcript of indexable frames so
 * Search and the Response tab work. The real WebSocket row and Messages tab
 * stay unchanged.
 */
object WsFrameNetworkRows {
  const val ID_SUFFIX = ".lumen-ws"

  /** Soft cap so a long-lived Socket.IO does not feed megabytes into Search. */
  const val MAX_TRANSCRIPT_CHARS = 512 * 1024

  @JvmStatic
  fun transcriptRequestId(parentRequestId: String): String = parentRequestId + ID_SUFFIX

  @JvmStatic
  fun parentRequestId(requestId: String): String? =
    if (requestId.endsWith(ID_SUFFIX)) requestId.removeSuffix(ID_SUFFIX) else null

  @JvmStatic
  fun shouldIndex(opcode: Int, binary: Boolean, payload: String): Boolean {
    if (binary || opcode != 1) return false
    if (payload.isEmpty()) return false
    return !isEngineIoControl(payload)
  }

  /**
   * Engine.IO ping/pong (`2` / `3` / `2probe`). Same rule as archive eviction so
   * idle Socket.IO heartbeats don't inflate the transcript.
   */
  @JvmStatic
  fun isEngineIoControl(payload: String): Boolean {
    if (payload.isEmpty()) return false
    val c = payload[0]
    return (c == '2' || c == '3') && payload.length <= 16
  }

  /**
   * Same host/path as the socket so the filter still correlates the two rows;
   * `lumen=ws-transcript` marks the searchable copy.
   */
  @JvmStatic
  fun transcriptUrl(socketUrl: String): String {
    val base = if (socketUrl.isBlank()) "wss://lumen.local/ws" else socketUrl
    val hash = base.indexOf('#')
    val noFrag = if (hash >= 0) base.substring(0, hash) else base
    val sep = if (noFrag.contains('?')) "&" else "?"
    return noFrag + sep + "lumen=ws-transcript"
  }

  @JvmStatic
  fun formatLine(outgoing: Boolean, payload: String, truncated: Boolean = false): String {
    val prefix = if (outgoing) "SEND " else "RECV "
    return if (truncated) prefix + payload + "…[truncated]" else prefix + payload
  }

  @JvmStatic
  @JvmOverloads
  fun buildTranscript(
    record: NetworkRecord,
    maxChars: Int = MAX_TRANSCRIPT_CHARS,
  ): String {
    if (record.wsFrames.isEmpty()) return ""
    val lines = ArrayList<String>()
    var chars = 0
    for (i in record.wsFrames.lastIndex downTo 0) {
      val frame = record.wsFrames[i]
      if (!shouldIndex(frame.opcode, frame.binary, frame.payload)) continue
      val line = formatLine(frame.outgoing, frame.payload, frame.truncated)
      val add = if (chars == 0) line.length else line.length + 1
      if (chars > 0 && chars + add > maxChars) break
      lines.add(line)
      chars += add
    }
    if (lines.isEmpty()) return ""
    lines.reverse()
    return lines.joinToString("\n")
  }

  @JvmStatic
  fun emitLive(parentRequestId: String, payload: String?, opcode: Int) {
    if (payload == null) return
    if (!shouldIndex(opcode, binary = false, payload = payload)) return
    val peers = NetworkPeerManager.getInstanceOrNull()?.copyReceivingPeers() ?: return
    if (peers.isEmpty()) return
    val record = lookupRecord(parentRequestId) ?: return
    val ts = android.os.SystemClock.elapsedRealtime() / 1000.0
    for (peer in peers) {
      emit(peer, record, ts)
    }
  }

  /**
   * Handshake only. Later frames are picked up by [buildTranscript] when Chrome
   * calls search / getResponseBody; a second [Network.requestWillBeSent] would
   * spawn another row.
   */
  @JvmStatic
  fun emit(
    peer: JsonRpcPeer,
    record: NetworkRecord,
    timestampSec: Double,
  ) {
    val rowId = transcriptRequestId(record.requestId)
    val body = buildTranscript(record)
    if (body.isEmpty()) return
    if (!peer.markWsTranscript(rowId)) return
    val url = transcriptUrl(record.url)
    val encoded = body.length.toLong()
    try {
      peer.invokeMethod(
        "Network.requestWillBeSent",
        requestWillBeSent(rowId, url, timestampSec),
        null,
      )
      peer.invokeMethod(
        "Network.responseReceived",
        responseReceived(rowId, url, timestampSec + 0.0001),
        null,
      )
      peer.invokeMethod(
        "Network.loadingFinished",
        JSONObject()
          .put("requestId", rowId)
          .put("timestamp", timestampSec + 0.0002)
          .put("encodedDataLength", encoded),
        null,
      )
    } catch (_: NotYetConnectedException) {
    } catch (_: RuntimeException) {
    }
  }

  private fun lookupRecord(parentRequestId: String): NetworkRecord? {
    if (!LumenAgent.isStarted()) return null
    return LumenAgent.store?.network?.get(parentRequestId)
  }

  private fun requestWillBeSent(
    rowId: String,
    url: String,
    timestampSec: Double,
  ): JSONObject {
    val request = JSONObject()
      .put("url", url)
      .put("method", "GET")
      .put(
        "headers",
        JSONObject().put("X-Lumen-WebSocket-Transcript", "1"),
      )
    val initiator = JSONObject()
      .put("type", "script")
      .put(
        "stackTrace",
        JSONArray().put(
          JSONObject()
            .put("functionName", "WebSocket.transcript")
            .put("scriptId", "lumen")
            .put("url", url)
            .put("lineNumber", 0)
            .put("columnNumber", 0),
        ),
      )
    return JSONObject()
      .put("requestId", rowId)
      .put("frameId", "1")
      .put("loaderId", "1")
      .put("documentURL", url)
      .put("request", request)
      .put("timestamp", timestampSec)
      .put("initiator", initiator)
      .put("type", "XHR")
  }

  private fun responseReceived(
    rowId: String,
    url: String,
    timestampSec: Double,
  ): JSONObject {
    val response = JSONObject()
      .put("url", url)
      .put("status", 200)
      .put("statusText", "OK")
      .put("headers", JSONObject().put("Content-Type", "text/plain"))
      .put("mimeType", "text/plain")
      .put("connectionReused", false)
      .put("connectionId", 0)
      .put("fromDiskCache", false)
    return JSONObject()
      .put("requestId", rowId)
      .put("frameId", "1")
      .put("loaderId", "1")
      .put("timestamp", timestampSec)
      .put("type", "XHR")
      .put("response", response)
  }
}
