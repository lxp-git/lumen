package dev.lumen.inspector.kv

import android.content.Context
import android.content.SharedPreferences
import dev.lumen.common.LogUtil
import dev.lumen.inspector.domstorage.SharedPreferencesHelper
import java.io.File
import java.util.Collections

/**
 * Discovers and reads/writes the three Android KV engines Chrome DevTools maps:
 * SharedPreferences, Jetpack DataStore, MMKV.
 */
class KvCatalog(private val context: Context) {

  fun listStores(): List<KvIds.StoreRef> {
    val out = ArrayList<KvIds.StoreRef>()
    for (name in sharedPrefNames()) {
      out.add(KvIds.StoreRef(KvIds.Kind.SHARED_PREFERENCES, name))
    }
    for (store in dataStoreFiles()) {
      out.add(KvIds.StoreRef(KvIds.Kind.DATASTORE, store.storeName))
    }
    for (id in mmkvIds()) {
      out.add(KvIds.StoreRef(KvIds.Kind.MMKV, id))
    }
    return out
  }

  fun databaseNames(): List<String> {
    val names = ArrayList<String>()
    if (sharedPrefNames().isNotEmpty()) names.add(KvIds.DB_SHARED_PREFERENCES)
    if (dataStoreFiles().isNotEmpty()) names.add(KvIds.DB_DATASTORE)
    if (mmkvIds().isNotEmpty()) names.add(KvIds.DB_MMKV)
    return names
  }

  fun objectStores(databaseName: String): List<String> {
    val kind = KvIds.kindForDatabase(databaseName) ?: return emptyList()
    return when (kind) {
      KvIds.Kind.SHARED_PREFERENCES -> sharedPrefNames()
      KvIds.Kind.DATASTORE -> dataStoreFiles().map { it.storeName }
      KvIds.Kind.MMKV -> mmkvIds()
    }
  }

  fun entries(ref: KvIds.StoreRef): List<KvEntry> {
    return when (ref.kind) {
      KvIds.Kind.SHARED_PREFERENCES -> sharedPrefEntries(ref.name)
      KvIds.Kind.DATASTORE -> dataStoreEntries(ref.name)
      KvIds.Kind.MMKV -> MmkvAccess.listEntries(ref.name)
    }
  }

  fun entries(databaseName: String, objectStoreName: String): List<KvEntry> {
    val kind = KvIds.kindForDatabase(databaseName) ?: return emptyList()
    return entries(KvIds.StoreRef(kind, objectStoreName))
  }

  fun put(ref: KvIds.StoreRef, key: String, newValue: String): Boolean {
    val existing = entries(ref).firstOrNull { it.key == key }?.value
    return when (ref.kind) {
      KvIds.Kind.SHARED_PREFERENCES -> putSharedPref(ref.name, key, newValue, existing)
      KvIds.Kind.DATASTORE -> putDataStore(ref.name, key, newValue, existing)
      KvIds.Kind.MMKV -> MmkvAccess.put(ref.name, key, newValue, existing)
    }
  }

  fun remove(ref: KvIds.StoreRef, key: String): Boolean {
    return when (ref.kind) {
      KvIds.Kind.SHARED_PREFERENCES -> {
        prefs(ref.name).edit().remove(key).apply()
        true
      }
      KvIds.Kind.DATASTORE -> removeDataStore(ref.name, key)
      KvIds.Kind.MMKV -> MmkvAccess.remove(ref.name, key)
    }
  }

  fun clear(ref: KvIds.StoreRef): Boolean {
    return when (ref.kind) {
      KvIds.Kind.SHARED_PREFERENCES -> {
        prefs(ref.name).edit().clear().apply()
        true
      }
      KvIds.Kind.DATASTORE -> clearDataStore(ref.name)
      KvIds.Kind.MMKV -> MmkvAccess.clear(ref.name)
    }
  }

  // ── SharedPreferences ──────────────────────────────────────────────────

  private fun sharedPrefNames(): List<String> {
    return SharedPreferencesHelper.getSharedPreferenceTags(context)
  }

  private fun prefs(name: String): SharedPreferences {
    return context.getSharedPreferences(name, Context.MODE_PRIVATE)
  }

  private fun sharedPrefEntries(name: String): List<KvEntry> {
    val prefs = prefs(name)
    val out = ArrayList<KvEntry>()
    for (entry in SharedPreferencesHelper.getSharedPreferenceEntriesSorted(prefs)) {
      val value = entry.value
      out.add(KvEntry(entry.key, value, typeName(value)))
    }
    return out
  }

  private fun putSharedPref(name: String, key: String, newValue: String, existing: Any?): Boolean {
    if (existing == null) {
      throw IllegalArgumentException("Unsupported: cannot add new key $key due to lack of type inference")
    }
    val coerced = SharedPreferencesHelper.valueFromString(newValue, existing)
    val editor = prefs(name).edit()
    when (coerced) {
      is Int -> editor.putInt(key, coerced)
      is Long -> editor.putLong(key, coerced)
      is Float -> editor.putFloat(key, coerced)
      is Boolean -> editor.putBoolean(key, coerced)
      is String -> editor.putString(key, coerced)
      is Set<*> -> {
        @Suppress("UNCHECKED_CAST")
        editor.putStringSet(key, coerced as Set<String>)
      }
      else -> throw IllegalArgumentException("Unsupported type=${coerced?.javaClass?.name}")
    }
    editor.apply()
    return true
  }

  // ── DataStore ──────────────────────────────────────────────────────────

  private data class DataStoreFile(
    val storeName: String,
    val file: File,
    val preferences: Boolean,
  )

  private fun dataStoreFiles(): List<DataStoreFile> {
    DataStoreAccess.prefetch(context)
    val dirs = dataStoreDirs()
    val used = HashSet<String>()
    val out = ArrayList<DataStoreFile>()
    for (dir in dirs) {
      val files = dir.listFiles() ?: continue
      for (file in files) {
        if (!file.isFile) continue
        val name = file.name
        if (name.endsWith(".lock") || name.endsWith(".tmp") || name.startsWith(".")) continue
        val (storeName, preferences) = when {
          name.endsWith(PREFERENCES_SUFFIX) ->
            name.substring(0, name.length - PREFERENCES_SUFFIX.length) to true
          name.endsWith(".pb") -> name.substring(0, name.length - 3) to false
          else -> continue
        }
        val unique = if (storeName in used) {
          val prefix = if (dir == File(context.noBackupFilesDir, "datastore")) {
            "no_backup."
          } else {
            "files."
          }
          prefix + storeName
        } else {
          storeName
        }
        used.add(storeName)
        used.add(unique)
        out.add(DataStoreFile(unique, file, preferences))
      }
    }
    out.sortBy { it.storeName }
    return out
  }

  private fun dataStoreDirs(): List<File> {
    return listOf(
      File(context.filesDir, "datastore"),
      File(context.noBackupFilesDir, "datastore"),
    )
  }

  private fun dataStoreFile(storeName: String): DataStoreFile? {
    return dataStoreFiles().firstOrNull { it.storeName == storeName }
  }

  private fun dataStoreEntries(storeName: String): List<KvEntry> {
    val store = dataStoreFile(storeName) ?: return emptyList()
    return try {
      val bytes = store.file.readBytes()
      if (store.preferences) {
        PreferencesProto.decode(bytes).entries
          .sortedBy { it.key }
          .map { (k, v) -> KvEntry(k, v, typeName(v)) }
      } else {
        val json = PreferencesProto.dumpGeneric(bytes)
        listOf(KvEntry("(proto)", json.toString(2), "json"))
      }
    } catch (t: Throwable) {
      LogUtil.w(t, "Failed to parse DataStore %s", store.file)
      listOf(KvEntry("(error)", t.message ?: t.javaClass.simpleName, "string"))
    }
  }

  private fun putDataStore(storeName: String, key: String, newValue: String, existing: Any?): Boolean {
    val store = dataStoreFile(storeName) ?: return false
    if (!store.preferences) {
      throw IllegalArgumentException("Proto DataStore is read-only without a schema")
    }
    val apply: (MutableMap<String, Any>) -> Unit = { map ->
      if (existing == null && !map.containsKey(key)) {
        map[key] = newValue
      } else {
        map[key] = PreferencesProto.coerce(newValue, existing ?: map[key])
      }
    }
    when (DataStoreAccess.mutate(context, store.file, apply)) {
      DataStoreAccess.MutateResult.APPLIED -> return true
      DataStoreAccess.MutateResult.FAILED -> return false
      DataStoreAccess.MutateResult.NO_IMPL -> {
        val map = LinkedHashMap(PreferencesProto.decode(store.file.readBytes()))
        apply(map)
        return fallbackDataStoreWrite(store.file, map)
      }
    }
  }

  private fun removeDataStore(storeName: String, key: String): Boolean {
    val store = dataStoreFile(storeName) ?: return false
    if (!store.preferences) return false
    var removed = false
    when (
      DataStoreAccess.mutate(context, store.file) { map ->
        removed = map.remove(key) != null
      }
    ) {
      DataStoreAccess.MutateResult.APPLIED -> return removed
      DataStoreAccess.MutateResult.FAILED -> return false
      DataStoreAccess.MutateResult.NO_IMPL -> {
        val map = LinkedHashMap(PreferencesProto.decode(store.file.readBytes()))
        if (map.remove(key) == null) return false
        return fallbackDataStoreWrite(store.file, map)
      }
    }
  }

  private fun clearDataStore(storeName: String): Boolean {
    val store = dataStoreFile(storeName) ?: return false
    if (!store.preferences) return false
    when (DataStoreAccess.mutate(context, store.file) { it.clear() }) {
      DataStoreAccess.MutateResult.APPLIED -> return true
      DataStoreAccess.MutateResult.FAILED -> return false
      DataStoreAccess.MutateResult.NO_IMPL -> {
        return fallbackDataStoreWrite(store.file, emptyMap())
      }
    }
  }

  /** File rewrite only when no DataStore library (hence no live actor) exists. */
  private fun fallbackDataStoreWrite(file: File, map: Map<String, Any>): Boolean {
    if (!DataStoreAccess.scanComplete()) return false
    writeAtomically(file, if (map.isEmpty()) ByteArray(0) else PreferencesProto.encode(map))
    DataStoreAccess.publish(context, file, map)
    return true
  }

  private fun writeAtomically(file: File, bytes: ByteArray) {
    val tmp = File(file.parentFile, file.name + ".lumen-tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(file)) {
      file.writeBytes(bytes)
      tmp.delete()
    }
  }

  // ── MMKV ───────────────────────────────────────────────────────────────

  private fun mmkvIds(): List<String> {
    val dir = MmkvAccess.rootDir() ?: return emptyList()
    val files = dir.listFiles() ?: return emptyList()
    val names = HashSet<String>(files.size)
    for (file in files) names.add(file.name)
    val ids = ArrayList<String>()
    for (file in files) {
      if (!file.isFile) continue
      val name = file.name
      if (name.startsWith(".")) continue
      if (name.endsWith(".crc") || name.endsWith(".crc32") || name.endsWith(".lock")) continue
      if ("$name.crc" !in names && "$name.crc32" !in names) continue
      ids.add(name)
    }
    Collections.sort(ids)
    return ids
  }

  companion object {
    private const val PREFERENCES_SUFFIX = ".preferences_pb"

    fun typeName(value: Any?): String {
      return when (value) {
        null -> "null"
        is Boolean -> "boolean"
        is Int -> "int"
        is Long -> "long"
        is Float -> "float"
        is Double -> "double"
        is Set<*> -> "stringSet"
        is ByteArray -> "bytes"
        else -> "string"
      }
    }
  }
}
