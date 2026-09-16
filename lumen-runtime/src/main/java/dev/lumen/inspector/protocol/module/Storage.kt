package dev.lumen.inspector.protocol.module

import dev.lumen.inspector.jsonrpc.JsonRpcPeer
import dev.lumen.inspector.jsonrpc.JsonRpcResult
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod
import dev.lumen.json.annotation.JsonProperty
import org.json.JSONObject

/** Class simple name must stay `Storage` so MethodDispatcher exposes `Storage.*`. */
class Storage : ChromeDevtoolsDomain {

  @ChromeDevtoolsMethod
  fun getStorageKey(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult = storageKeyResult()

  /** Chrome 114+ Application panel asks per-frame; one process → one key. */
  @ChromeDevtoolsMethod
  fun getStorageKeyForFrame(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult = storageKeyResult()

  @ChromeDevtoolsMethod
  fun getCookies(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    return GetCookiesResponse(cookies = emptyList())
  }

  @ChromeDevtoolsMethod
  fun setCookies(peer: JsonRpcPeer, params: JSONObject?) {
  }

  @ChromeDevtoolsMethod
  fun clearCookies(peer: JsonRpcPeer, params: JSONObject?) {
  }

  @ChromeDevtoolsMethod
  fun clearDataForOrigin(peer: JsonRpcPeer, params: JSONObject?) {
  }

  @ChromeDevtoolsMethod
  fun clearDataForStorageKey(peer: JsonRpcPeer, params: JSONObject?) {
  }

  @ChromeDevtoolsMethod
  fun getUsageAndQuota(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    return UsageAndQuotaResponse(
      usage = 0,
      quota = 0,
      overrideActive = false,
      usageBreakdown = emptyList(),
    )
  }

  @ChromeDevtoolsMethod
  fun trackCacheStorageForOrigin(peer: JsonRpcPeer, params: JSONObject?) {
  }

  @ChromeDevtoolsMethod
  fun trackIndexedDBForOrigin(peer: JsonRpcPeer, params: JSONObject?) {
    val origin = params?.optString("origin").orEmpty()
    if (origin.isNotEmpty()) {
      peer.invokeMethod(
        "Storage.indexedDBListUpdated",
        JSONObject().put("origin", origin),
        null,
      )
    } else {
      notifyIndexedDbList(peer, DEFAULT_STORAGE_KEY)
    }
  }

  @ChromeDevtoolsMethod
  fun trackIndexedDBForStorageKey(peer: JsonRpcPeer, params: JSONObject?) {
    notifyIndexedDbList(peer, params?.optString("storageKey", DEFAULT_STORAGE_KEY))
  }

  @ChromeDevtoolsMethod
  fun untrackCacheStorageForOrigin(peer: JsonRpcPeer, params: JSONObject?) {
  }

  @ChromeDevtoolsMethod
  fun untrackIndexedDBForOrigin(peer: JsonRpcPeer, params: JSONObject?) {
  }

  @ChromeDevtoolsMethod
  fun untrackIndexedDBForStorageKey(peer: JsonRpcPeer, params: JSONObject?) {
  }

  private fun notifyIndexedDbList(peer: JsonRpcPeer, storageKey: String?) {
    val key = if (storageKey.isNullOrEmpty()) DEFAULT_STORAGE_KEY else storageKey
    peer.invokeMethod(
      "Storage.indexedDBListUpdated",
      JSONObject().put("storageKey", key),
      null,
    )
  }

  private fun storageKeyResult(): JsonRpcResult = GetStorageKeyResponse(DEFAULT_STORAGE_KEY)

  class GetStorageKeyResponse(
    @JvmField @JsonProperty(required = true) val storageKey: String,
  ) : JsonRpcResult

  class GetCookiesResponse(
    @JvmField @JsonProperty(required = true) val cookies: List<JSONObject>,
  ) : JsonRpcResult

  class UsageAndQuotaResponse(
    @JvmField @JsonProperty(required = true) val usage: Long,
    @JvmField @JsonProperty(required = true) val quota: Long,
    @JvmField @JsonProperty(required = true) val overrideActive: Boolean,
    @JvmField @JsonProperty(required = true) val usageBreakdown: List<JSONObject>,
  ) : JsonRpcResult

  companion object {
    const val DEFAULT_STORAGE_KEY = "lumen-default"
  }
}
