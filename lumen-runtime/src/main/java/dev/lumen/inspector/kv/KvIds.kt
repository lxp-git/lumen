package dev.lumen.inspector.kv

/** IndexedDB database names and Local Storage key prefixes. */
object KvIds {
  const val DB_SHARED_PREFERENCES = "SharedPreferences"
  const val DB_DATASTORE = "DataStore"
  const val DB_MMKV = "MMKV"

  const val ORIGIN_DATASTORE = "datastore:"
  const val ORIGIN_MMKV = "mmkv:"

  enum class Kind {
    SHARED_PREFERENCES,
    DATASTORE,
    MMKV,
  }

  data class StoreRef(
    val kind: Kind,
    val name: String,
  ) {
    val databaseName: String
      get() = when (kind) {
        Kind.SHARED_PREFERENCES -> DB_SHARED_PREFERENCES
        Kind.DATASTORE -> DB_DATASTORE
        Kind.MMKV -> DB_MMKV
      }

    /** Chrome Local Storage origin (frame.securityOrigin). */
    val origin: String
      get() = when (kind) {
        Kind.SHARED_PREFERENCES -> name
        Kind.DATASTORE -> ORIGIN_DATASTORE + name
        Kind.MMKV -> ORIGIN_MMKV + name
      }
  }

  const val COMBINED_SEP = " / "

  fun parseOrigin(origin: String): StoreRef? {
    if (origin.isEmpty()) return null
    if (isCombinedOrigin(origin)) return null
    return when {
      origin.startsWith(ORIGIN_DATASTORE) ->
        StoreRef(Kind.DATASTORE, origin.substring(ORIGIN_DATASTORE.length).takeIf { it.isNotEmpty() }
          ?: return null)
      origin.startsWith(ORIGIN_MMKV) ->
        StoreRef(Kind.MMKV, origin.substring(ORIGIN_MMKV.length).takeIf { it.isNotEmpty() }
          ?: return null)
      else -> StoreRef(Kind.SHARED_PREFERENCES, origin)
    }
  }

  /**
   * Chrome 114+ Application panel keys Local Storage by
   * [dev.lumen.inspector.protocol.module.Storage.DEFAULT_STORAGE_KEY] (or the
   * `lumen://` frame URL). That table is the flattened `Engine / store / key`
   * view, not a single prefs file.
   */
  @JvmStatic
  fun isCombinedOrigin(origin: String?): Boolean {
    if (origin.isNullOrEmpty()) return true
    if (origin == "lumen-default") return true
    return origin.contains("://")
  }

  @JvmStatic
  fun combinedKey(kind: Kind, storeName: String, entryKey: String): String {
    val db = when (kind) {
      Kind.SHARED_PREFERENCES -> DB_SHARED_PREFERENCES
      Kind.DATASTORE -> DB_DATASTORE
      Kind.MMKV -> DB_MMKV
    }
    return "$db$COMBINED_SEP$storeName$COMBINED_SEP$entryKey"
  }

  fun combinedPrefix(ref: StoreRef): String {
    return ref.databaseName + COMBINED_SEP + ref.name + COMBINED_SEP
  }

  fun kindForDatabase(databaseName: String): Kind? = when (databaseName) {
    DB_SHARED_PREFERENCES -> Kind.SHARED_PREFERENCES
    DB_DATASTORE -> Kind.DATASTORE
    DB_MMKV -> Kind.MMKV
    else -> null
  }
}

data class KvEntry(
  val key: String,
  val value: Any?,
  val typeName: String,
) {
  val displayValue: String
    get() = PreferencesProto.display(value)
}
