package dev.lumen.inspector.kv

import dev.lumen.common.LogUtil
import java.io.File
import java.lang.reflect.Method

/**
 * Optional reflection bridge to Tencent MMKV. lumen-runtime does not depend on
 * MMKV; if the host app has it on the classpath we read/write via `mmkvWithID`.
 * Never calls `MMKV.initialize` — that would pin the default root before the
 * host's `MMKV.initialize(this, customPath / cryptKey)`.
 */
internal object MmkvAccess {
  @Volatile
  private var state: State? = null

  private class State(
    val mmkvWithID: Method,
    val allKeys: Method,
    val decodeInt: Method,
    val decodeLong: Method,
    val decodeFloat: Method,
    val decodeDouble: Method,
    val decodeBytes: Method,
    val getValueSize: Method?,
    val encodeString: Method,
    val encodeBool: Method,
    val encodeInt: Method,
    val encodeLong: Method,
    val encodeFloat: Method,
    val encodeDouble: Method,
    val encodeBytes: Method,
    val encodeStringSet: Method?,
    val remove: Method,
    val clearAll: Method,
    val getRootDir: Method?,
  )

  fun available(): Boolean = resolve() != null

  fun rootDir(): File? {
    val s = resolve() ?: return null
    val dir = try {
      s.getRootDir?.invoke(null) as? String
    } catch (_: Throwable) {
      null
    }
    return dir?.takeIf { it.isNotEmpty() }?.let { File(it) }
  }

  fun listEntries(mmapId: String): List<KvEntry> {
    if (resolve() == null) {
      return listOf(KvEntry("(unavailable)", "MMKV not on classpath", "string"))
    }
    val kv = open(mmapId) ?: return listOf(
      KvEntry("(unavailable)", "MMKV not initialized by host", "string"),
    )
    val s = resolve() ?: return emptyList()
    val keys = try {
      s.allKeys.invoke(kv) as? Array<*>
    } catch (t: Throwable) {
      LogUtil.w(t, "MMKV.allKeys failed for %s", mmapId)
      null
    } ?: return emptyList()
    val out = ArrayList<KvEntry>(keys.size)
    for (raw in keys) {
      val key = raw as? String ?: continue
      val (value, type) = readValue(s, kv, key)
      out.add(KvEntry(key, value, type))
    }
    out.sortBy { it.key }
    return out
  }

  fun put(mmapId: String, key: String, newValue: String, existing: Any?): Boolean {
    val kv = open(mmapId) ?: return false
    val s = resolve() ?: return false
    return try {
      val encoded = when (existing) {
        is Boolean -> s.encodeBool.invoke(kv, key, PreferencesProto.coerce(newValue, existing))
        is Int -> s.encodeInt.invoke(kv, key, PreferencesProto.coerce(newValue, existing) as Int)
        is Long -> s.encodeLong.invoke(kv, key, PreferencesProto.coerce(newValue, existing) as Long)
        is Float -> s.encodeFloat.invoke(kv, key, PreferencesProto.coerce(newValue, existing) as Float)
        is Double -> s.encodeDouble.invoke(kv, key, PreferencesProto.coerce(newValue, existing) as Double)
        is ByteArray -> s.encodeBytes.invoke(
          kv,
          key,
          PreferencesProto.coerce(newValue, existing) as ByteArray,
        )
        is Set<*> -> {
          val coerced = PreferencesProto.coerce(newValue, existing)
          if (s.encodeStringSet != null) {
            s.encodeStringSet.invoke(kv, key, coerced)
          } else {
            s.encodeString.invoke(kv, key, newValue)
          }
        }
        else -> s.encodeString.invoke(kv, key, newValue)
      }
      encoded != false
    } catch (e: IllegalArgumentException) {
      throw e
    } catch (t: Throwable) {
      LogUtil.w(t, "MMKV encode failed %s/%s", mmapId, key)
      false
    }
  }

  fun remove(mmapId: String, key: String): Boolean {
    val kv = open(mmapId) ?: return false
    val s = resolve() ?: return false
    return try {
      s.remove.invoke(kv, key)
      true
    } catch (t: Throwable) {
      LogUtil.w(t, "MMKV remove failed %s/%s", mmapId, key)
      false
    }
  }

  fun clear(mmapId: String): Boolean {
    val kv = open(mmapId) ?: return false
    val s = resolve() ?: return false
    return try {
      s.clearAll.invoke(kv)
      true
    } catch (t: Throwable) {
      LogUtil.w(t, "MMKV clearAll failed %s", mmapId)
      false
    }
  }

  private fun open(mmapId: String): Any? {
    val s = resolve() ?: return null
    if (!hostInitialized(s)) return null
    return try {
      s.mmkvWithID.invoke(null, mmapId)
    } catch (t: Throwable) {
      LogUtil.w(t, "MMKV.mmkvWithID(%s) failed", mmapId)
      null
    }
  }

  private fun hostInitialized(s: State): Boolean {
    if (s.getRootDir == null) return true
    val existing = try {
      s.getRootDir.invoke(null) as? String
    } catch (_: Throwable) {
      null
    }
    return !existing.isNullOrEmpty()
  }

  /**
   * Probe only size-safe MMKV getters. Calling `decodeFloat` on a 1-byte
   * int, or `decodeString` / `decodeStringSet` / `decodeBytes` on a varint,
   * makes native MMKV log `reach end` / `truncatedMessage` for every key.
   */
  private fun readValue(s: State, kv: Any, key: String): Pair<Any?, String> {
    val rawSize = try {
      (s.getValueSize?.invoke(kv, key) as? Int) ?: 0
    } catch (_: Throwable) {
      0
    }
    val asInt = try {
      s.decodeInt.invoke(kv, key, 0) as Int
    } catch (_: Throwable) {
      0
    }
    val asLong = try {
      s.decodeLong.invoke(kv, key, 0L) as Long
    } catch (_: Throwable) {
      0L
    }
    val extra = MmkvValueCodec.extraProbes(rawSize, asInt, asLong)
    val asFloat = if (MmkvExtraProbe.FLOAT in extra) {
      try {
        s.decodeFloat.invoke(kv, key, 0f) as Float
      } catch (_: Throwable) {
        0f
      }
    } else {
      0f
    }
    val asDouble = if (MmkvExtraProbe.DOUBLE in extra) {
      try {
        s.decodeDouble.invoke(kv, key, 0.0) as Double
      } catch (_: Throwable) {
        0.0
      }
    } else {
      0.0
    }
    val bytes = if (MmkvExtraProbe.BYTES in extra) {
      try {
        s.decodeBytes.invoke(kv, key) as? ByteArray
      } catch (_: Throwable) {
        null
      }
    } else {
      null
    }
    return MmkvValueCodec.decode(
      MmkvValueCodec.Probes(
        stringSet = bytes?.let { MmkvValueCodec.parseStringVector(it) },
        rawSize = rawSize,
        asInt = asInt,
        asLong = asLong,
        asFloat = asFloat,
        asDouble = asDouble,
        bytes = bytes,
      ),
    )
  }

  @Synchronized
  private fun resolve(): State? {
    state?.let { return it }
    return try {
      val mmkv = Class.forName("com.tencent.mmkv.MMKV")
      fun req(name: String, vararg types: Class<*>): Method = mmkv.getMethod(name, *types)
      fun opt(name: String, vararg types: Class<*>): Method? = try {
        mmkv.getMethod(name, *types)
      } catch (_: Throwable) {
        null
      }
      val loaded = State(
        mmkvWithID = req("mmkvWithID", String::class.java),
        allKeys = req("allKeys"),
        decodeInt = req("decodeInt", String::class.java, java.lang.Integer.TYPE),
        decodeLong = req("decodeLong", String::class.java, java.lang.Long.TYPE),
        decodeFloat = req("decodeFloat", String::class.java, java.lang.Float.TYPE),
        decodeDouble = req("decodeDouble", String::class.java, java.lang.Double.TYPE),
        decodeBytes = req("decodeBytes", String::class.java),
        getValueSize = opt("getValueSize", String::class.java),
        encodeString = req("encode", String::class.java, String::class.java),
        encodeBool = req("encode", String::class.java, java.lang.Boolean.TYPE),
        encodeInt = req("encode", String::class.java, java.lang.Integer.TYPE),
        encodeLong = req("encode", String::class.java, java.lang.Long.TYPE),
        encodeFloat = req("encode", String::class.java, java.lang.Float.TYPE),
        encodeDouble = req("encode", String::class.java, java.lang.Double.TYPE),
        encodeBytes = req("encode", String::class.java, ByteArray::class.java),
        encodeStringSet = opt("encode", String::class.java, Set::class.java),
        remove = req("removeValueForKey", String::class.java),
        clearAll = req("clearAll"),
        getRootDir = opt("getRootDir"),
      )
      state = loaded
      loaded
    } catch (_: ClassNotFoundException) {
      null
    } catch (t: Throwable) {
      LogUtil.w(t, "MMKV reflection bind failed")
      null
    }
  }
}
