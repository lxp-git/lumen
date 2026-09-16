package dev.lumen.inspector.protocol.module

import android.content.Context
import dev.lumen.inspector.console.CLog
import dev.lumen.inspector.domstorage.DOMStoragePeerManager
import dev.lumen.inspector.jsonrpc.JsonRpcPeer
import dev.lumen.inspector.jsonrpc.JsonRpcResult
import dev.lumen.inspector.kv.KvCatalog
import dev.lumen.inspector.kv.KvEntry
import dev.lumen.inspector.kv.KvIds
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod
import dev.lumen.json.ObjectMapper
import dev.lumen.json.annotation.JsonProperty
import org.json.JSONObject

/**
 * Chrome `DOMStorage` domain — Application → Local Storage.
 *
 * Class simple name must stay `DOMStorage` so MethodDispatcher exposes
 * `DOMStorage.*`. Nested types stay public for [DOMStoragePeerManager].
 */
class DOMStorage(context: Context) : ChromeDevtoolsDomain {

  private val peerManager = DOMStoragePeerManager(context)
  private val objectMapper = ObjectMapper()
  private val catalog = KvCatalog(context)

  @ChromeDevtoolsMethod
  fun enable(peer: JsonRpcPeer, params: JSONObject?) {
    peerManager.addPeer(peer)
  }

  @ChromeDevtoolsMethod
  fun disable(peer: JsonRpcPeer, params: JSONObject?) {
    peerManager.removePeer(peer)
  }

  @ChromeDevtoolsMethod
  fun getDOMStorageItems(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val storage = storageId(params)
    val entries = ArrayList<List<String>>()
    if (storage.isLocalStorage) {
      val origin = firstNonEmpty(storage.securityOrigin, storage.storageKey)
      val ref = origin?.let { KvIds.parseOrigin(it) }
      if (ref != null) {
        addEntries(entries, catalog.entries(ref), null)
      } else if (origin == null || KvIds.isCombinedOrigin(origin)) {
        for (store in catalog.listStores()) {
          addEntries(entries, catalog.entries(store), KvIds.combinedPrefix(store))
        }
      }
    }
    return GetDOMStorageItemsResult(entries)
  }

  @ChromeDevtoolsMethod
  fun setDOMStorageItem(peer: JsonRpcPeer, params: JSONObject?) {
    val storage = storageId(params)
    val key = params?.getString("key").orEmpty()
    val value = params?.getString("value").orEmpty()
    if (!storage.isLocalStorage) return
    try {
      val resolved = resolveKey(storage, key)
      val oldDisplay = currentDisplay(resolved)
      try {
        if (!catalog.put(resolved.ref, resolved.entryKey, value)) {
          throw AssignmentException("Failed to write $key")
        }
      } catch (e: IllegalArgumentException) {
        revert(storage, key, value, oldDisplay, e.message)
      } catch (e: AssignmentException) {
        revert(storage, key, value, oldDisplay, e.message)
      }
    } catch (e: IllegalArgumentException) {
      revert(storage, key, value, null, e.message)
    }
  }

  @ChromeDevtoolsMethod
  fun removeDOMStorageItem(peer: JsonRpcPeer, params: JSONObject?) {
    val storage = storageId(params)
    val key = params?.getString("key").orEmpty()
    if (!storage.isLocalStorage) return
    val resolved = resolveKey(storage, key)
    catalog.remove(resolved.ref, resolved.entryKey)
  }

  @ChromeDevtoolsMethod
  fun clear(peer: JsonRpcPeer, params: JSONObject?) {
    val storage = storageId(params)
    if (!storage.isLocalStorage) return
    val origin = firstNonEmpty(storage.securityOrigin, storage.storageKey)
    val ref = origin?.let { KvIds.parseOrigin(it) }
    if (ref != null) {
      catalog.clear(ref)
      return
    }
    if (origin != null && !KvIds.isCombinedOrigin(origin)) return
    // Combined Local Storage is every engine in one table. Chrome's Clear All
    // would otherwise wipe SharedPreferences + DataStore + MMKV in one click.
    val message = "Clear-all on combined Local Storage is disabled; delete a row or use Application → IndexedDB to clear one store."
    CLog.writeToConsole(
      peerManager,
      Console.MessageLevel.ERROR,
      Console.MessageSource.STORAGE,
      message,
    )
    for (store in catalog.listStores()) {
      val prefix = KvIds.combinedPrefix(store)
      for (kv in catalog.entries(store)) {
        peerManager.signalItemAdded(storage, prefix + kv.key, kv.displayValue)
      }
    }
  }

  private fun storageId(params: JSONObject?): StorageId {
    val raw = params?.optJSONObject("storageId") ?: JSONObject()
    return objectMapper.convertValue(raw, StorageId::class.java)
  }

  private fun addEntries(entries: MutableList<List<String>>, kvEntries: List<KvEntry>, prefix: String?) {
    for (kv in kvEntries) {
      entries.add(listOf(if (prefix == null) kv.key else prefix + kv.key, kv.displayValue))
    }
  }

  /**
   * Modern Chrome keys Local Storage by `storageKey=lumen-default`. Flatten
   * every engine into `Engine / name / key` rows in that case.
   */
  private fun resolveStore(storage: StorageId): KvIds.StoreRef? {
    val origin = firstNonEmpty(storage.securityOrigin, storage.storageKey) ?: return null
    return KvIds.parseOrigin(origin)
  }

  private fun resolveKey(storage: StorageId, key: String): ResolvedKey {
    val ref = resolveStore(storage)
    if (ref != null) return ResolvedKey(ref, key)
    val first = key.indexOf(KvIds.COMBINED_SEP)
    val second = if (first < 0) -1 else key.indexOf(KvIds.COMBINED_SEP, first + KvIds.COMBINED_SEP.length)
    if (first < 0 || second < 0) {
      throw IllegalArgumentException("Not a combined KV key: $key")
    }
    val db = key.substring(0, first)
    val name = key.substring(first + KvIds.COMBINED_SEP.length, second)
    val entryKey = key.substring(second + KvIds.COMBINED_SEP.length)
    val kind = KvIds.kindForDatabase(db)
      ?: throw IllegalArgumentException("Unknown KV engine in key: $key")
    return ResolvedKey(KvIds.StoreRef(kind, name), entryKey)
  }

  private fun currentDisplay(resolved: ResolvedKey): String? {
    return catalog.entries(resolved.ref).firstOrNull { it.key == resolved.entryKey }?.displayValue
  }

  private fun revert(
    storage: StorageId,
    key: String,
    attempted: String,
    oldDisplay: String?,
    message: String?,
  ) {
    CLog.writeToConsole(
      peerManager,
      Console.MessageLevel.ERROR,
      Console.MessageSource.STORAGE,
      message ?: "",
    )
    if (oldDisplay != null) {
      peerManager.signalItemUpdated(storage, key, attempted, oldDisplay)
    } else {
      peerManager.signalItemRemoved(storage, key)
    }
  }

  class StorageId {
    @JvmField @JsonProperty var securityOrigin: String? = null
    @JvmField @JsonProperty var storageKey: String? = null
    @JvmField @JsonProperty(required = true) var isLocalStorage: Boolean = false
  }

  class GetDOMStorageItemsResult(
    @JvmField @JsonProperty(required = true) val entries: List<List<String>>,
  ) : JsonRpcResult

  class DomStorageItemsClearedParams {
    @JvmField @JsonProperty(required = true) var storageId: StorageId? = null
  }

  class DomStorageItemRemovedParams {
    @JvmField @JsonProperty(required = true) var storageId: StorageId? = null
    @JvmField @JsonProperty(required = true) var key: String? = null
  }

  class DomStorageItemAddedParams {
    @JvmField @JsonProperty(required = true) var storageId: StorageId? = null
    @JvmField @JsonProperty(required = true) var key: String? = null
    @JvmField @JsonProperty(required = true) var newValue: String? = null
  }

  class DomStorageItemUpdatedParams {
    @JvmField @JsonProperty(required = true) var storageId: StorageId? = null
    @JvmField @JsonProperty(required = true) var key: String? = null
    @JvmField @JsonProperty(required = true) var oldValue: String? = null
    @JvmField @JsonProperty(required = true) var newValue: String? = null
  }

  private class ResolvedKey(val ref: KvIds.StoreRef, val entryKey: String)

  private class AssignmentException(message: String) : Exception(message)

  companion object {
    private fun firstNonEmpty(a: String?, b: String?): String? {
      if (!a.isNullOrEmpty()) return a
      if (!b.isNullOrEmpty()) return b
      return null
    }
  }
}
