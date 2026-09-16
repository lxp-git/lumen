package dev.lumen.inspector.protocol.module

import android.content.Context
import dev.lumen.common.LogUtil
import dev.lumen.inspector.console.CLog
import dev.lumen.inspector.jsonrpc.JsonRpcPeer
import dev.lumen.inspector.jsonrpc.JsonRpcResult
import dev.lumen.inspector.kv.KvCatalog
import dev.lumen.inspector.kv.KvEntry
import dev.lumen.inspector.kv.KvIds
import dev.lumen.inspector.kv.PreferencesProto
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod
import dev.lumen.json.annotation.JsonProperty
import org.json.JSONObject

/** Class simple name must stay `IndexedDB` so MethodDispatcher exposes `IndexedDB.*`. */
class IndexedDB(context: Context) : ChromeDevtoolsDomain {

  private val catalog = KvCatalog(context)

  @ChromeDevtoolsMethod
  fun enable(peer: JsonRpcPeer, params: JSONObject?) {
  }

  @ChromeDevtoolsMethod
  fun disable(peer: JsonRpcPeer, params: JSONObject?) {
  }

  @ChromeDevtoolsMethod
  fun requestDatabaseNames(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    return DatabaseNamesResult(catalog.databaseNames())
  }

  @ChromeDevtoolsMethod
  fun requestDatabase(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val databaseName = params?.optString("databaseName").orEmpty()
    val stores = catalog.objectStores(databaseName).map { name ->
      ObjectStore(
        name = name,
        keyPath = KeyPath(type = "null"),
        autoIncrement = false,
        indexes = emptyList(),
      )
    }
    return DatabaseResult(
      DatabaseWithObjectStores(
        name = databaseName,
        version = 1.0,
        objectStores = stores,
      ),
    )
  }

  @ChromeDevtoolsMethod
  fun getMetadata(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val databaseName = params?.optString("databaseName").orEmpty()
    val objectStoreName = params?.optString("objectStoreName").orEmpty()
    val count = try {
      catalog.entries(databaseName, objectStoreName).size
    } catch (t: Throwable) {
      LogUtil.w(t, "IndexedDB.getMetadata %s/%s", databaseName, objectStoreName)
      0
    }
    return MetadataResult(entriesCount = count.toDouble(), keyGeneratorValue = 0.0)
  }

  @ChromeDevtoolsMethod
  fun requestData(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val databaseName = params?.optString("databaseName").orEmpty()
    val objectStoreName = params?.optString("objectStoreName").orEmpty()
    val skipCount = params?.optInt("skipCount", 0) ?: 0
    val pageSize = params?.optInt("pageSize", 50) ?: 50
    val keyRange = params?.optJSONObject("keyRange")
    val all = try {
      catalog.entries(databaseName, objectStoreName)
    } catch (t: Throwable) {
      LogUtil.w(t, "IndexedDB.requestData %s/%s", databaseName, objectStoreName)
      emptyList()
    }
    val filtered = all.filter { inRange(it.key, keyRange) }
    val page = filtered.drop(skipCount.coerceAtLeast(0)).take(pageSize.coerceAtLeast(0))
    return DataResult(
      objectStoreDataEntries = page.map { toDataEntry(it) },
      hasMore = skipCount + page.size < filtered.size,
    )
  }

  @ChromeDevtoolsMethod
  fun deleteObjectStoreEntries(peer: JsonRpcPeer, params: JSONObject?) {
    val databaseName = params?.optString("databaseName").orEmpty()
    val objectStoreName = params?.optString("objectStoreName").orEmpty()
    val kind = KvIds.kindForDatabase(databaseName) ?: return
    val ref = KvIds.StoreRef(kind, objectStoreName)
    val keyRange = params?.optJSONObject("keyRange")
    val keys = catalog.entries(ref)
      .map { it.key }
      .filter { inRange(it, keyRange) }
    for (key in keys) {
      try {
        catalog.remove(ref, key)
      } catch (t: Throwable) {
        LogUtil.w(t, "IndexedDB delete %s/%s/%s", databaseName, objectStoreName, key)
      }
    }
  }

  @ChromeDevtoolsMethod
  fun clearObjectStore(peer: JsonRpcPeer, params: JSONObject?) {
    val databaseName = params?.optString("databaseName").orEmpty()
    val kind = KvIds.kindForDatabase(databaseName) ?: return
    val store = params?.optString("objectStoreName").orEmpty()
    if (store.isEmpty()) return
    try {
      catalog.clear(KvIds.StoreRef(kind, store))
    } catch (t: Throwable) {
      LogUtil.w(t, "IndexedDB clear %s/%s", databaseName, store)
    }
  }

  @ChromeDevtoolsMethod
  fun deleteDatabase(peer: JsonRpcPeer, params: JSONObject?) {
    CLog.writeToConsole(
      Console.MessageLevel.ERROR,
      Console.MessageSource.STORAGE,
      "Delete database is disabled; clear one object store instead.",
    )
    // Chrome drops the node on a successful RPC; refresh so files stay in the tree.
    val payload = JSONObject()
    val origin = params?.optString("securityOrigin").orEmpty()
    val storageKey = params?.optString("storageKey").orEmpty()
    if (origin.isNotEmpty()) payload.put("origin", origin)
    if (storageKey.isNotEmpty()) payload.put("storageKey", storageKey)
    if (payload.length() == 0) payload.put("storageKey", Storage.DEFAULT_STORAGE_KEY)
    peer.invokeMethod("Storage.indexedDBListUpdated", payload, null)
  }

  private fun toDataEntry(entry: KvEntry): DataEntry {
    return DataEntry(
      key = remote(entry.key),
      primaryKey = remote(entry.key),
      value = remote(entry.value, entry.typeName),
    )
  }

  private fun remote(value: Any?, typeName: String? = null): RemoteObject {
    return when (value) {
      null -> RemoteObject(type = "object", subtype = "null", value = JSONObject.NULL, description = "null")
      is Boolean -> RemoteObject(type = "boolean", value = value, description = value.toString())
      is Number -> RemoteObject(type = "number", value = value, description = value.toString())
      is ByteArray -> {
        val hex = value.joinToString("") { b -> "%02x".format(b) }
        RemoteObject(type = "string", value = hex, description = "bytes:$hex")
      }
      is Set<*> -> {
        val text = PreferencesProto.display(value)
        RemoteObject(type = "string", value = text, description = "Set $text")
      }
      else -> {
        val text = value.toString()
        val desc = if (typeName != null && typeName != "string") "$text ($typeName)" else text
        RemoteObject(type = "string", value = text, description = desc)
      }
    }
  }

  private fun inRange(key: String, keyRange: JSONObject?): Boolean {
    if (keyRange == null) return true
    val lower = keyFrom(keyRange.optJSONObject("lower"))
    val upper = keyFrom(keyRange.optJSONObject("upper"))
    val lowerOpen = keyRange.optBoolean("lowerOpen", false)
    val upperOpen = keyRange.optBoolean("upperOpen", false)
    if (lower != null) {
      val cmp = key.compareTo(lower)
      if (cmp < 0 || (lowerOpen && cmp == 0)) return false
    }
    if (upper != null) {
      val cmp = key.compareTo(upper)
      if (cmp > 0 || (upperOpen && cmp == 0)) return false
    }
    return true
  }

  private fun keyFrom(key: JSONObject?): String? {
    if (key == null) return null
    if (key.has("string")) return key.optString("string")
    if (key.has("number")) return key.opt("number")?.toString()
    return null
  }

  class DatabaseNamesResult(
    @JvmField @JsonProperty(required = true) val databaseNames: List<String>,
  ) : JsonRpcResult

  class DatabaseResult(
    @JvmField @JsonProperty(required = true) val databaseWithObjectStores: DatabaseWithObjectStores,
  ) : JsonRpcResult

  class DatabaseWithObjectStores(
    @JvmField @JsonProperty(required = true) val name: String,
    @JvmField @JsonProperty(required = true) val version: Double,
    @JvmField @JsonProperty(required = true) val objectStores: List<ObjectStore>,
  )

  class ObjectStore(
    @JvmField @JsonProperty(required = true) val name: String,
    @JvmField @JsonProperty(required = true) val keyPath: KeyPath,
    @JvmField @JsonProperty(required = true) val autoIncrement: Boolean,
    @JvmField @JsonProperty(required = true) val indexes: List<ObjectStoreIndex>,
  )

  class ObjectStoreIndex(
    @JvmField @JsonProperty(required = true) val name: String,
    @JvmField @JsonProperty(required = true) val keyPath: KeyPath,
    @JvmField @JsonProperty(required = true) val unique: Boolean,
    @JvmField @JsonProperty(required = true) val multiEntry: Boolean,
  )

  class KeyPath(
    @JvmField @JsonProperty(required = true) val type: String,
    @JvmField @JsonProperty val string: String? = null,
  )

  class MetadataResult(
    @JvmField @JsonProperty(required = true) val entriesCount: Double,
    @JvmField @JsonProperty(required = true) val keyGeneratorValue: Double,
  ) : JsonRpcResult

  class DataResult(
    @JvmField @JsonProperty(required = true) val objectStoreDataEntries: List<DataEntry>,
    @JvmField @JsonProperty(required = true) val hasMore: Boolean,
  ) : JsonRpcResult

  class DataEntry(
    @JvmField @JsonProperty(required = true) val key: RemoteObject,
    @JvmField @JsonProperty(required = true) val primaryKey: RemoteObject,
    @JvmField @JsonProperty(required = true) val value: RemoteObject,
  )

  class RemoteObject(
    @JvmField @JsonProperty(required = true) val type: String,
    @JvmField @JsonProperty val subtype: String? = null,
    @JvmField @JsonProperty val value: Any? = null,
    @JvmField @JsonProperty val description: String? = null,
  )
}
