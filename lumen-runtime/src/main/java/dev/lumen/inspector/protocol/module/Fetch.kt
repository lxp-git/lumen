package dev.lumen.inspector.protocol.module

import android.util.Base64
import dev.lumen.inspector.helper.ChromePeerManager
import dev.lumen.inspector.helper.PeerRegistrationListener
import dev.lumen.inspector.jsonrpc.JsonRpcPeer
import dev.lumen.inspector.jsonrpc.JsonRpcResult
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod
import dev.lumen.json.annotation.JsonProperty
import dev.lumen.mock.MockEngine
import dev.lumen.store.EventStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * CDP Fetch domain — bridges Chrome DevTools Network Override / request interception
 * into the in-app [MockEngine], which the OkHttp interceptor consults.
 *
 * @see <a href="https://chromedevtools.github.io/devtools-protocol/tot/Fetch/">Fetch domain</a>
 */
class Fetch(
  private val engine: MockEngine,
  private val store: EventStore,
) : ChromeDevtoolsDomain {

  private val peers = ChromePeerManager()

  private val fetchListener = MockEngine.FetchListener { paused ->
    val params = JSONObject()
      .put("requestId", paused.fetchId)
      .put("request", JSONObject()
        .put("url", paused.url)
        .put("method", paused.method)
        .put("headers", mapToJson(paused.headers))
        .put("initialPriority", "High")
        .put("referrerPolicy", "strict-origin-when-cross-origin")
        .apply {
          if (paused.postData != null) put("postData", paused.postData)
        })
      .put("frameId", "1")
      .put("resourceType", paused.resourceType)
      .put("networkId", paused.networkId)
    if (paused.requestStage.equals("Response", ignoreCase = true)) {
      // Chrome Local Overrides only replace the body when this event looks
      // like a Response-stage pause (responseStatusCode present).
      paused.responseStatusCode?.let { params.put("responseStatusCode", it) }
      paused.responseStatusText?.let { params.put("responseStatusText", it) }
      val headerArr = JSONArray()
      for ((name, value) in paused.responseHeaders) {
        headerArr.put(JSONObject().put("name", name).put("value", value))
      }
      params.put("responseHeaders", headerArr)
    }
    peers.sendNotificationToPeers("Fetch.requestPaused", params)
  }

  init {
    peers.setListener(object : PeerRegistrationListener {
      override fun onPeerRegistered(peer: JsonRpcPeer) {}
      override fun onPeerUnregistered(peer: JsonRpcPeer) {
        if (!peers.hasRegisteredPeers()) {
          engine.removeFetchListener(fetchListener)
          engine.disableFetch()
        }
      }
    })
  }

  @ChromeDevtoolsMethod
  fun enable(peer: JsonRpcPeer, params: JSONObject?) {
    peers.addPeer(peer)
    engine.addFetchListener(fetchListener)
    val patterns = ArrayList<MockEngine.Pattern>()
    val arr = params?.optJSONArray("patterns")
    if (arr != null) {
      for (i in 0 until arr.length()) {
        val p = arr.getJSONObject(i)
        patterns.add(
          MockEngine.Pattern(
            urlPattern = p.optString("urlPattern", "*"),
            resourceType = if (p.has("resourceType")) p.getString("resourceType") else null,
            requestStage = p.optString("requestStage", "Request"),
          ),
        )
      }
    }
    engine.enableFetch(patterns)
  }

  @ChromeDevtoolsMethod
  fun disable(peer: JsonRpcPeer, params: JSONObject?) {
    peers.removePeer(peer)
    if (!peers.hasRegisteredPeers()) {
      engine.removeFetchListener(fetchListener)
      engine.disableFetch()
    }
  }

  @ChromeDevtoolsMethod
  fun fulfillRequest(peer: JsonRpcPeer, params: JSONObject?) {
    if (params == null) return
    val fetchId = params.getString("requestId")
    val code = params.optInt("responseCode", 200)
    val headers = ArrayList<Pair<String, String>>()
    val headerArr = params.optJSONArray("responseHeaders")
    if (headerArr != null) {
      for (i in 0 until headerArr.length()) {
        val h = headerArr.getJSONObject(i)
        headers.add(h.getString("name") to h.getString("value"))
      }
    }
    val bodyBytes = if (params.has("body")) {
      val raw = params.getString("body")
      if (params.optBoolean("binaryResponseHeaders", false)) {
        Base64.decode(raw, Base64.DEFAULT)
      } else {
        // CDP sends body as base64 when the binary flag is set; Chrome often base64-encodes anyway.
        try {
          Base64.decode(raw, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
          raw.toByteArray(Charsets.UTF_8)
        }
      }
    } else {
      ByteArray(0)
    }
    // Mark the corresponding network record as mocked when we can map ids.
    engine.fulfillRequest(fetchId, code, headers, bodyBytes)
  }

  @ChromeDevtoolsMethod
  fun continueRequest(peer: JsonRpcPeer, params: JSONObject?) {
    if (params == null) return
    val fetchId = params.getString("requestId")
    val url = if (params.has("url")) params.getString("url") else null
    val method = if (params.has("method")) params.getString("method") else null
    val headers = params.optJSONObject("headers")?.let { obj ->
      obj.keys().asSequence().associateWith { obj.getString(it) }
    }
    val postData = if (params.has("postData")) {
      Base64.decode(params.getString("postData"), Base64.DEFAULT)
    } else null
    engine.continueRequest(fetchId, url, method, headers, postData)
  }

  @ChromeDevtoolsMethod
  fun failRequest(peer: JsonRpcPeer, params: JSONObject?) {
    if (params == null) return
    val fetchId = params.getString("requestId")
    val reason = params.optString("errorReason", "Failed")
    engine.failRequest(fetchId, reason)
  }

  @ChromeDevtoolsMethod
  fun continueResponse(peer: JsonRpcPeer, params: JSONObject?) {
    if (params == null) return
    val fetchId = params.getString("requestId")
    val code = if (params.has("responseCode")) params.getInt("responseCode") else null
    val headers = parseHeaderList(params.optJSONArray("responseHeaders"))
    engine.continueRequest(fetchId, responseCode = code, responseHeaders = headers)
  }

  @ChromeDevtoolsMethod
  fun continueWithAuth(peer: JsonRpcPeer, params: JSONObject?) {
    if (params == null) return
    engine.continueRequest(params.getString("requestId"))
  }

  @ChromeDevtoolsMethod
  fun getResponseBody(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val fetchId = params?.optString("requestId").orEmpty()
    val bytes = engine.getPausedBody(fetchId) ?: ByteArray(0)
    return GetResponseBodyResult(
      body = Base64.encodeToString(bytes, Base64.NO_WRAP),
      base64Encoded = true,
    )
  }

  class GetResponseBodyResult(
    @JvmField @JsonProperty val body: String,
    @JvmField @JsonProperty val base64Encoded: Boolean,
  ) : JsonRpcResult

  private fun parseHeaderList(headerArr: JSONArray?): List<Pair<String, String>>? {
    if (headerArr == null) return null
    val headers = ArrayList<Pair<String, String>>(headerArr.length())
    for (i in 0 until headerArr.length()) {
      val h = headerArr.getJSONObject(i)
      headers.add(h.getString("name") to h.getString("value"))
    }
    return headers
  }

  private fun mapToJson(map: Map<String, String>): JSONObject {
    val o = JSONObject()
    for ((k, v) in map) o.put(k, v)
    return o
  }
}
