package dev.lumen.inspector.protocol.module

import android.content.Context
import android.os.SystemClock
import dev.lumen.inspector.jsonrpc.JsonRpcException
import dev.lumen.inspector.jsonrpc.JsonRpcPeer
import dev.lumen.inspector.jsonrpc.JsonRpcResult
import dev.lumen.inspector.jsonrpc.protocol.JsonRpcError
import dev.lumen.inspector.network.NetworkPeerManager
import dev.lumen.inspector.network.WsFrameNetworkRows
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod
import dev.lumen.json.annotation.JsonProperty
import dev.lumen.store.EventStore
import dev.lumen.store.NetworkRecord
import org.json.JSONObject
import java.io.IOException
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * CDP Network domain driven by [EventStore].
 * On enable, replays the current-process session window then streams live events
 * (live push still originates from [dev.lumen.inspector.network.NetworkEventReporterImpl]
 * and [dev.lumen.okhttp.LumenInterceptor]).
 */
class Network(
  context: Context,
  private val store: EventStore,
) : ChromeDevtoolsDomain {

  private val peerManager: NetworkPeerManager =
    NetworkPeerManager.getOrCreateInstance(context)

  @ChromeDevtoolsMethod
  fun enable(peer: JsonRpcPeer, params: JSONObject?) {
    // Chrome re-calls Network.enable during Target attach. Replaying
    // webSocketCreated a second time resets the Messages tab — skip the
    // handshake if this peer already got it, but still replay frames onto
    // the existing NetworkRequest.
    val firstPeer = peerManager.addPeer(peer)
    dev.lumen.common.LogRedirector.i(
      "LumenCDP",
      "Network.enable firstPeer=$firstPeer session=${peer.sessionId} peers=${peerManager.copyReceivingPeers().size}",
    )
    if (!firstPeer) {
      return
    }
    replaySession(peer)
  }

  @ChromeDevtoolsMethod
  fun disable(peer: JsonRpcPeer, params: JSONObject?) {
    peerManager.removePeer(peer)
  }

  @ChromeDevtoolsMethod
  fun setUserAgentOverride(peer: JsonRpcPeer, params: JSONObject?) {
  }

  @ChromeDevtoolsMethod
  fun setCacheDisabled(peer: JsonRpcPeer, params: JSONObject?) {
  }

  @ChromeDevtoolsMethod
  fun setBypassServiceWorker(peer: JsonRpcPeer, params: JSONObject?) {
  }

  @ChromeDevtoolsMethod
  fun emulateNetworkConditions(peer: JsonRpcPeer, params: JSONObject?) {
  }

  @ChromeDevtoolsMethod
  fun setAcceptedEncodings(peer: JsonRpcPeer, params: JSONObject?) {
  }

  @ChromeDevtoolsMethod
  fun canEmulateNetworkConditions(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    return BooleanResult(false)
  }

  @ChromeDevtoolsMethod
  fun canClearBrowserCache(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    return BooleanResult(false)
  }

  @ChromeDevtoolsMethod
  fun canClearBrowserCookies(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    return BooleanResult(false)
  }

  @ChromeDevtoolsMethod
  fun getRequestPostData(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val requestId = params?.optString("requestId").orEmpty()
    val rec = store.network.get(requestId)
    return PostDataResult(rec?.requestBody ?: "")
  }

  class BooleanResult(
    @JvmField @JsonProperty val result: Boolean,
  ) : JsonRpcResult

  class PostDataResult(
    @JvmField @JsonProperty val postData: String,
  ) : JsonRpcResult

  @ChromeDevtoolsMethod
  fun getResponseBody(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val requestId = params?.optString("requestId").orEmpty()
    transcriptBody(requestId)?.let { payload ->
      return GetResponseBodyResponse().also {
        it.body = payload
        it.base64Encoded = false
      }
    }
    // Prefer EventStore; fall back to legacy temp files; never throw — Chrome's
    // Network panel calls this optimistically and a JSON-RPC error is noisy.
    store.network.readBody(requestId)?.let { body ->
      return GetResponseBodyResponse().also {
        it.body = body.first
        it.base64Encoded = body.second
      }
    }
    try {
      val data = peerManager.responseBodyFileManager.readFile(requestId)
      return GetResponseBodyResponse().also {
        it.body = data.data
        it.base64Encoded = data.base64Encoded
      }
    } catch (_: Exception) {
      // missing body
    } catch (_: OutOfMemoryError) {
      // huge body
    }
    return GetResponseBodyResponse().also {
      it.body = ""
      it.base64Encoded = false
    }
  }

  /**
   * Network panel Search (Ctrl/Cmd+F) calls this once per request. A stub `{}`
   * makes Chrome treat every body as a non-match, so "find this field in all
   * responses" appears broken.
   */
  @ChromeDevtoolsMethod
  fun searchInResponseBody(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val requestId = params?.optString("requestId").orEmpty()
    val query = params?.optString("query").orEmpty()
    val caseSensitive = params?.optBoolean("caseSensitive", false) ?: false
    val isRegex = params?.optBoolean("isRegex", false) ?: false
    val result = SearchInResponseBodyResponse()
    if (requestId.isEmpty() || query.isEmpty()) {
      return result
    }
    val haystack = collectSearchableText(requestId)
    result.result = findMatches(haystack, query, caseSensitive, isRegex)
    return result
  }

  private fun transcriptBody(requestId: String): String? {
    val parentId = WsFrameNetworkRows.parentRequestId(requestId) ?: return null
    val record = store.network.get(parentId) ?: return null
    return WsFrameNetworkRows.buildTranscript(record).takeIf { it.isNotEmpty() }
  }

  private fun collectSearchableText(requestId: String): String {
    transcriptBody(requestId)?.let { return it }
    val record = store.network.get(requestId)
    val chunks = ArrayList<String>()
    store.network.readBody(requestId)?.let { (body, base64) ->
      if (body.isNotEmpty()) {
        chunks.add(
          if (base64) {
            try {
              String(android.util.Base64.decode(body, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
              body
            }
          } else {
            body
          },
        )
      }
    }
    if (chunks.isEmpty()) {
      try {
        val data = peerManager.responseBodyFileManager.readFile(requestId)
        if (!data.data.isNullOrEmpty()) {
          chunks.add(
            if (data.base64Encoded) {
              try {
                String(android.util.Base64.decode(data.data, android.util.Base64.DEFAULT), Charsets.UTF_8)
              } catch (_: Exception) {
                data.data
              }
            } else {
              data.data
            },
          )
        }
      } catch (_: Exception) {
      }
    }
    record?.requestBody?.takeIf { it.isNotEmpty() }?.let { chunks.add(it) }
    record?.wsFrames?.forEach { frame ->
      if (frame.payload.isNotEmpty()) {
        chunks.add(frame.payload)
      }
    }
    return chunks.joinToString("\n")
  }

  private fun findMatches(
    text: String,
    query: String,
    caseSensitive: Boolean,
    isRegex: Boolean,
  ): List<SearchMatch> {
    if (text.isEmpty()) return emptyList()
    val matcher: (String) -> Boolean = if (isRegex) {
      val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
      val pattern = try {
        Pattern.compile(query, flags)
      } catch (_: PatternSyntaxException) {
        return emptyList()
      }
      ({ line -> pattern.matcher(line).find() })
    } else if (caseSensitive) {
      ({ line -> line.contains(query) })
    } else {
      ({ line -> line.contains(query, ignoreCase = true) })
    }
    val matches = ArrayList<SearchMatch>()
    val lines = text.split('\n')
    for (i in lines.indices) {
      val line = lines[i]
      if (matcher(line)) {
        val match = SearchMatch()
        match.lineNumber = i.toDouble()
        match.lineContent = if (line.length > 1_000) line.substring(0, 1_000) + "…" else line
        matches.add(match)
        if (matches.size >= 200) break
      }
    }
    return matches
  }

  private fun replaySession(peer: JsonRpcPeer) {
    val records = store.network.snapshotForReplay()
    for (record in records) {
      if (record.isWebSocket || record.resourceType.equals("WebSocket", ignoreCase = true)) {
        // Skip handshake attempts that never opened — they used to flood
        // Chrome's Network list with empty WebSocket rows.
        if (record.statusCode == 101 || record.wsFrames.isNotEmpty()) {
          emitWebSocketReplay(peer, record)
        }
        continue
      }
      emitRequestWillBeSent(peer, record)
      if (record.statusCode != null) {
        emitResponseReceived(peer, record)
        if (record.failedReason != null) {
          emitLoadingFailed(peer, record)
        } else if (record.finishedAtMs != null) {
          emitLoadingFinished(peer, record)
        }
      } else if (record.failedReason != null) {
        emitLoadingFailed(peer, record)
      }
    }
  }

  /** Replay handshake plus archived frames (same payloads the live panel would have shown). */
  private fun emitWebSocketReplay(peer: JsonRpcPeer, record: NetworkRecord) {
    val ts = record.startedAtMonotonicMs / 1000.0
    // Skip a second webSocketCreated: Chrome 151 always constructs a fresh
    // NetworkRequest and the Messages tab stays bound to the empty one.
    if (peer.markWsCreated(record.requestId)) {
      peer.invokeMethod(
        "Network.webSocketCreated",
        WebSocketCreatedParams().also {
          it.requestId = record.requestId
          it.url = record.url
        },
        null,
      )
      peer.invokeMethod(
        "Network.webSocketWillSendHandshakeRequest",
        WebSocketWillSendHandshakeRequestParams().also {
          it.requestId = record.requestId
          it.timestamp = ts
          it.wallTime = record.startedAtMs / 1000.0
          it.request = WebSocketRequest().also { req ->
            req.headers = headersJson(record.requestHeaders)
          }
        },
        null,
      )
      if (record.statusCode != null) {
        peer.invokeMethod(
          "Network.webSocketHandshakeResponseReceived",
          WebSocketHandshakeResponseReceivedParams().also {
            it.requestId = record.requestId
            it.timestamp = ts + 0.001
            it.response = WebSocketResponse().also { resp ->
              resp.status = record.statusCode ?: 0
              resp.statusText = record.statusText ?: ""
              resp.headers = headersJson(record.responseHeaders)
            }
          },
          null,
        )
      }
    }
    if (record.wsFrames.isNotEmpty()) {
      for ((index, archived) in record.wsFrames.withIndex()) {
        val payload = if (archived.truncated) {
          archived.payload + "…[truncated]"
        } else {
          archived.payload
        }
        val params = JSONObject()
          .put("requestId", record.requestId)
          .put("timestamp", archived.timestampMonoMs / 1000.0 + index * 0.000001)
          .put(
            "response",
            JSONObject()
              .put("opcode", archived.opcode)
              .put("mask", archived.outgoing)
              .put("payloadData", payload),
          )
        val method = if (archived.outgoing) {
          "Network.webSocketFrameSent"
        } else {
          "Network.webSocketFrameReceived"
        }
        peer.invokeMethod(method, params, null)
      }
      emitTranscriptRow(peer, record)
    } else if (record.wsFrameCount > 0) {
      peer.invokeMethod(
        "Network.webSocketFrameReceived",
        WebSocketFrameReceivedParams().also {
          it.requestId = record.requestId
          it.timestamp = ts + 0.002
          it.response = WebSocketFrame().also { frame ->
            frame.opcode = 1
            frame.mask = false
            frame.payloadData = "[replay] ${record.wsFrameCount} frames (payloads not retained)"
          }
        },
        null,
      )
    }
    if (record.finishedAtMs != null || record.failedReason != null) {
      if (record.failedReason != null) {
        peer.invokeMethod(
          "Network.webSocketFrameError",
          WebSocketFrameErrorParams().also {
            it.requestId = record.requestId
            it.timestamp = ts + 0.003
            it.errorMessage = record.failedReason
          },
          null,
        )
      }
      peer.invokeMethod(
        "Network.webSocketClosed",
        WebSocketClosedParams().also {
          it.requestId = record.requestId
          it.timestamp = ts + 0.004
        },
        null,
      )
    }
  }

  /**
   * Chrome Search skips WebSocket bodies (`isTextType == false`). One XHR per
   * socket holds the frame transcript so Search / Response still work.
   */
  private fun emitTranscriptRow(peer: JsonRpcPeer, record: NetworkRecord) {
    val ts = record.wsFrames.firstOrNull { frame ->
      WsFrameNetworkRows.shouldIndex(frame.opcode, frame.binary, frame.payload)
    }?.timestampMonoMs?.div(1000.0) ?: (record.startedAtMonotonicMs / 1000.0)
    WsFrameNetworkRows.emit(peer, record, ts)
  }

  private fun emitRequestWillBeSent(peer: JsonRpcPeer, record: NetworkRecord) {
    val request = Request().also {
      it.url = record.url
      it.method = record.method
      it.headers = headersJson(record.requestHeaders)
      it.postData = record.requestBody
    }
    val initiator = Initiator().also {
      it.type = InitiatorType.OTHER
    }
    val params = RequestWillBeSentParams().also {
      it.requestId = record.requestId
      it.frameId = "1"
      it.loaderId = "1"
      it.documentURL = record.url
      it.request = request
      it.timestamp = record.startedAtMonotonicMs / 1000.0
      it.initiator = initiator
      it.type = resourceType(record)
    }
    peer.invokeMethod("Network.requestWillBeSent", params, null)
  }

  private fun emitResponseReceived(peer: JsonRpcPeer, record: NetworkRecord) {
    val response = Response().also {
      it.url = record.url
      it.status = record.statusCode ?: 0
      it.statusText = record.statusText ?: ""
      it.headers = headersJson(record.responseHeaders)
      it.mimeType = record.mimeType ?: "application/octet-stream"
      it.connectionReused = record.connectionReused
      it.connectionId = 0
      it.fromDiskCache = record.fromDiskCache
    }
    val params = ResponseReceivedParams().also {
      it.requestId = record.requestId
      it.frameId = "1"
      it.loaderId = "1"
      it.timestamp = (record.finishedAtMs ?: record.startedAtMs).let {
        // Use monotonic-ish seconds for CDP; wall delta is fine for replay.
        record.startedAtMonotonicMs / 1000.0 +
          ((record.finishedAtMs ?: record.startedAtMs) - record.startedAtMs) / 1000.0
      }
      it.type = resourceType(record)
      it.response = response
    }
    peer.invokeMethod("Network.responseReceived", params, null)
  }

  private fun emitLoadingFinished(peer: JsonRpcPeer, record: NetworkRecord) {
    val params = LoadingFinishedParams().also {
      it.requestId = record.requestId
      it.timestamp = record.startedAtMonotonicMs / 1000.0 +
        ((record.finishedAtMs ?: record.startedAtMs) - record.startedAtMs) / 1000.0
      it.encodedDataLength = record.encodedDataLength
    }
    peer.invokeMethod("Network.loadingFinished", params, null)
  }

  private fun emitLoadingFailed(peer: JsonRpcPeer, record: NetworkRecord) {
    val params = LoadingFailedParams().also {
      it.requestId = record.requestId
      it.timestamp = SystemClock.elapsedRealtime() / 1000.0
      it.errorText = record.failedReason ?: "failed"
      it.type = resourceType(record)
    }
    peer.invokeMethod("Network.loadingFailed", params, null)
  }

  private fun headersJson(headers: Map<String, String>): JSONObject {
    val o = JSONObject()
    for ((k, v) in headers) o.put(k, v)
    return o
  }

  private fun resourceType(record: NetworkRecord): Page.ResourceType {
    if (record.isWebSocket || record.resourceType.equals("WebSocket", ignoreCase = true)) {
      return Page.ResourceType.WEBSOCKET
    }
    // Archived rows default to "Other". Chrome Search only calls
    // searchInResponseBody when isTextType is true (XHR/Fetch/Document/…),
    // so a JSON body typed Other is invisible to Ctrl+F even though
    // the CDP method itself works.
    val stored = record.resourceType
    if (stored.isNotBlank() && !stored.equals("Other", ignoreCase = true)) {
      try {
        return Page.ResourceType.valueOf(stored.uppercase())
      } catch (_: Exception) {
      }
    }
    return mimeResourceType(record.mimeType)
  }

  private fun mimeResourceType(mimeType: String?): Page.ResourceType {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return Page.ResourceType.OTHER
    return when {
      mime.startsWith("image/") -> Page.ResourceType.IMAGE
      mime == "application/json" || mime.endsWith("+json") -> Page.ResourceType.XHR
      mime == "text/javascript" || mime == "application/javascript" ||
        mime == "application/x-javascript" -> Page.ResourceType.SCRIPT
      mime.startsWith("text/") -> Page.ResourceType.DOCUMENT
      else -> Page.ResourceType.OTHER
    }
  }

  // --- CDP DTOs kept as nested types so ObjectMapper + existing reporter keep working ---

  class GetResponseBodyResponse : JsonRpcResult {
    @JvmField @JsonProperty(required = true) var body: String? = null
    @JvmField @JsonProperty(required = true) var base64Encoded: Boolean = false
  }

  class SearchInResponseBodyResponse : JsonRpcResult {
    @JvmField @JsonProperty(required = true) var result: List<SearchMatch> = emptyList()
  }

  class SearchMatch {
    @JvmField @JsonProperty(required = true) var lineNumber: Double = 0.0
    @JvmField @JsonProperty(required = true) var lineContent: String? = null
  }

  class RequestWillBeSentParams {
    @JvmField @JsonProperty(required = true) var requestId: String? = null
    @JvmField @JsonProperty(required = true) var frameId: String? = null
    @JvmField @JsonProperty(required = true) var loaderId: String? = null
    @JvmField @JsonProperty(required = true) var documentURL: String? = null
    @JvmField @JsonProperty(required = true) var request: Request? = null
    @JvmField @JsonProperty(required = true) var timestamp: Double = 0.0
    @JvmField @JsonProperty(required = true) var initiator: Initiator? = null
    @JvmField @JsonProperty var redirectResponse: Response? = null
    @JvmField @JsonProperty var type: Page.ResourceType? = null
  }

  class ResponseReceivedParams {
    @JvmField @JsonProperty(required = true) var requestId: String? = null
    @JvmField @JsonProperty(required = true) var frameId: String? = null
    @JvmField @JsonProperty(required = true) var loaderId: String? = null
    @JvmField @JsonProperty(required = true) var timestamp: Double = 0.0
    @JvmField @JsonProperty(required = true) var type: Page.ResourceType? = null
    @JvmField @JsonProperty(required = true) var response: Response? = null
  }

  class LoadingFinishedParams {
    @JvmField @JsonProperty(required = true) var requestId: String? = null
    @JvmField @JsonProperty(required = true) var timestamp: Double = 0.0
    /** Chrome uses this for the Network "transferred" column. Missing ⇒ 0 B. */
    @JvmField @JsonProperty var encodedDataLength: Long = 0
  }

  class LoadingFailedParams {
    @JvmField @JsonProperty(required = true) var requestId: String? = null
    @JvmField @JsonProperty(required = true) var timestamp: Double = 0.0
    @JvmField @JsonProperty(required = true) var errorText: String? = null
    @JvmField @JsonProperty var type: Page.ResourceType? = null
  }

  class DataReceivedParams {
    @JvmField @JsonProperty(required = true) var requestId: String? = null
    @JvmField @JsonProperty(required = true) var timestamp: Double = 0.0
    @JvmField @JsonProperty(required = true) var dataLength: Int = 0
    @JvmField @JsonProperty(required = true) var encodedDataLength: Int = 0
  }

  class Request {
    @JvmField @JsonProperty(required = true) var url: String? = null
    @JvmField @JsonProperty(required = true) var method: String? = null
    @JvmField @JsonProperty(required = true) var headers: JSONObject? = null
    @JvmField @JsonProperty var postData: String? = null
  }

  class Initiator {
    @JvmField @JsonProperty(required = true) var type: InitiatorType? = null
    @JvmField @JsonProperty var stackTrace: MutableList<Console.CallFrame>? = null
  }

  enum class InitiatorType(private val protocolValue: String) {
    PARSER("parser"),
    SCRIPT("script"),
    OTHER("other");

    @dev.lumen.json.annotation.JsonValue
    fun getProtocolValue(): String = protocolValue
  }

  class Response {
    @JvmField @JsonProperty(required = true) var url: String? = null
    @JvmField @JsonProperty(required = true) var status: Int = 0
    @JvmField @JsonProperty(required = true) var statusText: String? = null
    @JvmField @JsonProperty(required = true) var headers: JSONObject? = null
    @JvmField @JsonProperty var headersText: String? = null
    @JvmField @JsonProperty(required = true) var mimeType: String? = null
    @JvmField @JsonProperty var requestHeaders: JSONObject? = null
    @JvmField @JsonProperty var requestHeadersText: String? = null
    @JvmField @JsonProperty(required = true) var connectionReused: Boolean = false
    @JvmField @JsonProperty(required = true) var connectionId: Int = 0
    @JvmField @JsonProperty(required = true) var fromDiskCache: Boolean? = false
    @JvmField @JsonProperty var timing: ResourceTiming? = null
  }

  class ResourceTiming {
    @JvmField @JsonProperty(required = true) var requestTime: Double = 0.0
    @JvmField @JsonProperty(required = true) var proxyStart: Double = 0.0
    @JvmField @JsonProperty(required = true) var proxyEnd: Double = 0.0
    @JvmField @JsonProperty(required = true) var dnsStart: Double = 0.0
    @JvmField @JsonProperty(required = true) var dnsEnd: Double = 0.0
    @JvmField @JsonProperty(required = true) var connectionStart: Double = 0.0
    @JvmField @JsonProperty(required = true) var connectionEnd: Double = 0.0
    @JvmField @JsonProperty(required = true) var sslStart: Double = 0.0
    @JvmField @JsonProperty(required = true) var sslEnd: Double = 0.0
    @JvmField @JsonProperty(required = true) var sendStart: Double = 0.0
    @JvmField @JsonProperty(required = true) var sendEnd: Double = 0.0
    @JvmField @JsonProperty(required = true) var receivedHeadersEnd: Double = 0.0
  }

  class WebSocketCreatedParams {
    @JvmField @JsonProperty(required = true) var requestId: String? = null
    @JvmField @JsonProperty(required = true) var url: String? = null
  }

  class WebSocketClosedParams {
    @JvmField @JsonProperty(required = true) var requestId: String? = null
    @JvmField @JsonProperty(required = true) var timestamp: Double = 0.0
  }

  class WebSocketWillSendHandshakeRequestParams {
    @JvmField @JsonProperty(required = true) var requestId: String? = null
    @JvmField @JsonProperty(required = true) var timestamp: Double = 0.0
    @JvmField @JsonProperty(required = true) var wallTime: Double = 0.0
    @JvmField @JsonProperty(required = true) var request: WebSocketRequest? = null
  }

  class WebSocketRequest {
    @JvmField @JsonProperty(required = true) var headers: JSONObject? = null
  }

  class WebSocketHandshakeResponseReceivedParams {
    @JvmField @JsonProperty(required = true) var requestId: String? = null
    @JvmField @JsonProperty(required = true) var timestamp: Double = 0.0
    @JvmField @JsonProperty(required = true) var response: WebSocketResponse? = null
  }

  class WebSocketResponse {
    @JvmField @JsonProperty(required = true) var status: Int = 0
    @JvmField @JsonProperty(required = true) var statusText: String? = null
    @JvmField @JsonProperty(required = true) var headers: JSONObject? = null
    @JvmField @JsonProperty var headersText: String? = null
    @JvmField @JsonProperty var requestHeaders: JSONObject? = null
    @JvmField @JsonProperty var requestHeadersText: String? = null
  }

  class WebSocketFrameReceivedParams {
    @JvmField @JsonProperty(required = true) var requestId: String? = null
    @JvmField @JsonProperty(required = true) var timestamp: Double = 0.0
    @JvmField @JsonProperty(required = true) var response: WebSocketFrame? = null
  }

  class WebSocketFrameSentParams {
    @JvmField @JsonProperty(required = true) var requestId: String? = null
    @JvmField @JsonProperty(required = true) var timestamp: Double = 0.0
    @JvmField @JsonProperty(required = true) var response: WebSocketFrame? = null
  }

  class WebSocketFrame {
    @JvmField @JsonProperty(required = true) var opcode: Int = 0
    @JvmField @JsonProperty(required = true) var mask: Boolean = false
    @JvmField @JsonProperty(required = true) var payloadData: String? = null
  }

  class WebSocketFrameErrorParams {
    @JvmField @JsonProperty(required = true) var requestId: String? = null
    @JvmField @JsonProperty(required = true) var timestamp: Double = 0.0
    @JvmField @JsonProperty(required = true) var errorMessage: String? = null
  }
}
