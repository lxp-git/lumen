package dev.lumen.inspector.kv

import android.content.Context
import dev.lumen.common.LogUtil
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.jvm.functions.Function2

/**
 * Mutates Jetpack DataStore through the live actor when one is found; otherwise
 * callers write the file and [publish] pokes the in-process cache.
 *
 * lumen-runtime does not depend on DataStore; everything is reflection.
 */
internal object DataStoreAccess {

  private const val SCAN_WAIT_MS = 2_000L

  private val known = ConcurrentHashMap<String, WeakReference<Any>>()
  private val delegates = ArrayList<WeakReference<Any>>()
  private val delegatesLock = Any()
  private val inFlight = ConcurrentHashMap.newKeySet<String>()
  private val scanned = AtomicBoolean(false)
  private val scanStarted = AtomicBoolean(false)
  private val scanDone = CountDownLatch(1)

  @Volatile
  private var classNames: List<String>? = null

  fun scanComplete(): Boolean = scanned.get()

  /** Kick the dex scan so a later [mutate] can see live actors. */
  fun prefetch(context: Context) {
    ensureScanStarted(context.applicationContext)
  }

  fun publish(context: Context, file: File, values: Map<String, Any>) {
    try {
      val loader = context.classLoader
      val impl = findImpl(context, file) ?: return
      val prefs = toPreferences(values, loader) ?: return
      writeCache(impl, prefs, loader)
    } catch (t: Throwable) {
      LogUtil.w(t, "DataStore in-process cache update failed for %s", file)
    }
  }

  enum class MutateResult {
    APPLIED,
    NO_IMPL,
    FAILED,
  }

  /**
   * Apply [transform] via `DataStore.updateData` when a live instance exists.
   * [MutateResult.NO_IMPL] means callers may rewrite the file; [FAILED] means
   * an in-flight actor was already started and must not be raced with a rewrite.
   */
  fun mutate(
    context: Context,
    file: File,
    transform: (MutableMap<String, Any>) -> Unit,
  ): MutateResult {
    val impl = findImpl(context, file)
    if (impl == null) {
      if (!scanned.get()) return MutateResult.FAILED
      return if (preferencesLibraryPresent(context)) {
        MutateResult.FAILED
      } else {
        MutateResult.NO_IMPL
      }
    }
    val loader = context.classLoader
    val update = impl.javaClass.methods.firstOrNull {
      it.name == "updateData" && it.parameterTypes.size == 2
    } ?: return MutateResult.NO_IMPL
    val path = pathOf(file)
    if (!inFlight.add(path)) return MutateResult.FAILED
    var handedOff = false
    return try {
      val block = object : Function2<Any, Continuation<Any>, Any?> {
        override fun invoke(prefs: Any, cont: Continuation<Any>): Any {
          return applyTransform(prefs, loader, transform)
        }
      }
      val waiter = BlockingContinuation<Any>()
      val ret = update.invoke(impl, block, waiter)
      if (ret === COROUTINE_SUSPENDED) {
        try {
          waiter.await()
        } catch (e: java.util.concurrent.TimeoutException) {
          handedOff = true
          Thread(
            {
              try {
                waiter.awaitForever()
              } catch (t: Throwable) {
                LogUtil.w(t, "DataStore.updateData late failure for %s", file)
              } finally {
                inFlight.remove(path)
              }
            },
            "lumen-datastore-await",
          ).apply {
            isDaemon = true
            start()
          }
          LogUtil.w(e, "DataStore.updateData still running for %s", file)
          return MutateResult.FAILED
        }
      }
      MutateResult.APPLIED
    } catch (e: InvocationTargetException) {
      val cause = e.cause
      if (cause is IllegalArgumentException) throw cause
      LogUtil.w(e, "DataStore.updateData failed for %s", file)
      MutateResult.FAILED
    } catch (e: IllegalArgumentException) {
      throw e
    } catch (t: Throwable) {
      LogUtil.w(t, "DataStore.updateData failed for %s", file)
      MutateResult.FAILED
    } finally {
      if (!handedOff) inFlight.remove(path)
    }
  }

  private fun preferencesLibraryPresent(context: Context): Boolean {
    return try {
      Class.forName(
        "androidx.datastore.preferences.core.Preferences",
        false,
        context.classLoader,
      )
      true
    } catch (_: Throwable) {
      false
    }
  }

  private fun pathOf(file: File): String {
    return try {
      file.canonicalPath
    } catch (_: Throwable) {
      file.absolutePath
    }
  }

  private fun findImpl(context: Context, file: File): Any? {
    val path = try {
      file.canonicalPath
    } catch (_: Throwable) {
      file.absolutePath
    }
    known[path]?.get()?.let { return unwrap(it) }
    val app = context.applicationContext
    matchIn(app, path)?.let { return it }
    ensureScanStarted(app)
    if (!scanned.get()) {
      try {
        scanDone.await(SCAN_WAIT_MS, TimeUnit.MILLISECONDS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
    known[path]?.get()?.let { return unwrap(it) }
    harvestDelegates()
    return known[path]?.get()?.let { unwrap(it) }
  }

  private fun ensureScanStarted(app: Context) {
    if (scanned.get()) return
    if (!scanStarted.compareAndSet(false, true)) return
    Thread(
      {
        try {
          scanStatics(app)
        } finally {
          scanned.set(true)
          scanDone.countDown()
        }
      },
      "lumen-datastore-scan",
    ).apply {
      isDaemon = true
      start()
    }
  }

  private fun scanStatics(app: Context) {
    val loader = app.classLoader
    for (name in classNames(app)) {
      val clazz = try {
        Class.forName(name, false, loader)
      } catch (_: Throwable) {
        continue
      }
      for (field in clazz.declaredFields) {
        if (!Modifier.isStatic(field.modifiers)) continue
        if (!looksLikeDataStore(field.type)) continue
        val raw = try {
          field.isAccessible = true
          field.get(null)
        } catch (_: Throwable) {
          null
        } ?: continue
        rememberDelegate(raw)
        val store = unwrapDelegate(raw) ?: continue
        val impl = unwrap(store)
        val storeFile = fileOf(impl) ?: continue
        val storePath = try {
          storeFile.canonicalPath
        } catch (_: Throwable) {
          storeFile.absolutePath
        }
        known[storePath] = WeakReference(impl)
      }
    }
  }

  private fun matchIn(target: Any, path: String): Any? {
    var c: Class<*>? = target.javaClass
    while (c != null && c != Any::class.java) {
      for (field in c.declaredFields) {
        if (!looksLikeDataStore(field.type)) continue
        val raw = try {
          field.isAccessible = true
          field.get(target)
        } catch (_: Throwable) {
          null
        } ?: continue
        rememberDelegate(raw)
        val store = unwrapDelegate(raw) ?: continue
        val impl = unwrap(store)
        val storeFile = fileOf(impl) ?: continue
        val storePath = try {
          storeFile.canonicalPath
        } catch (_: Throwable) {
          storeFile.absolutePath
        }
        known[storePath] = WeakReference(impl)
        if (storePath == path) return impl
      }
      c = c.superclass
    }
    return null
  }

  private fun looksLikeDataStore(type: Class<*>): Boolean {
    var c: Class<*>? = type
    while (c != null && c != Any::class.java) {
      if (isDataStoreTypeName(c.name)) return true
      for (iface in c.interfaces) {
        if (isDataStoreTypeName(iface.name)) return true
      }
      c = c.superclass
    }
    return false
  }

  private fun isDataStoreTypeName(name: String): Boolean {
    if (name == "androidx.datastore.core.DataStore" || name.endsWith(".DataStore")) return true
    return name.contains("DataStoreDelegate") ||
      name.contains("PreferenceDataStoreSingletonDelegate")
  }

  private fun rememberDelegate(raw: Any) {
    if (!raw.javaClass.name.contains("Delegate")) return
    synchronized(delegatesLock) {
      val it = delegates.iterator()
      while (it.hasNext()) {
        val existing = it.next().get()
        if (existing == null) {
          it.remove()
        } else if (existing === raw) {
          return
        }
      }
      delegates.add(WeakReference(raw))
    }
  }

  private fun harvestDelegates() {
    synchronized(delegatesLock) {
      val it = delegates.iterator()
      while (it.hasNext()) {
        val raw = it.next().get()
        if (raw == null) {
          it.remove()
          continue
        }
        val store = unwrapDelegate(raw) ?: continue
        val impl = unwrap(store)
        val storeFile = fileOf(impl) ?: continue
        known[pathOf(storeFile)] = WeakReference(impl)
      }
    }
  }

  private fun unwrapDelegate(raw: Any): Any? {
    val inst = field(raw.javaClass, "INSTANCE")?.get(raw)
    if (inst != null) return inst
    val name = raw.javaClass.name
    if (name.contains("DataStore") && !name.contains("Delegate")) return raw
    return null
  }

  private fun unwrap(store: Any): Any {
    var cur = store
    while (true) {
      val f = field(cur.javaClass, "delegate") ?: break
      val next = f.get(cur) ?: break
      if (next === cur) break
      cur = next
    }
    return cur
  }

  private fun fileOf(impl: Any): File? {
    field(impl.javaClass, "produceFile")?.get(impl)?.let { fn ->
      return invokeFile(fn)
    }
    field(impl.javaClass, "storage")?.get(impl)?.let { storage ->
      field(storage.javaClass, "produceFile")?.get(storage)?.let { fn ->
        return invokeFile(fn)
      }
    }
    field(impl.javaClass, "file")?.let { f ->
      return f.get(impl) as? File
    }
    return null
  }

  private fun invokeFile(fn: Any): File? {
    return try {
      fn.javaClass.getMethod("invoke").invoke(fn) as? File
    } catch (_: Throwable) {
      null
    }
  }

  private fun writeCache(impl: Any, prefs: Any, loader: ClassLoader) {
    bumpCoordinator(impl)
    val cache = field(impl.javaClass, "inMemoryCache")?.get(impl)
    if (cache != null) {
      val version = currentVersion(cache) + 1
      val data = newData(prefs, version, loader) ?: return
      try {
        cache.javaClass.getMethod("tryUpdate", data.javaClass.superclass ?: data.javaClass)
          .invoke(cache, data)
      } catch (_: Throwable) {
        for (m in cache.javaClass.methods) {
          if (m.name != "tryUpdate" || m.parameterTypes.size != 1) continue
          m.invoke(cache, data)
          break
        }
      }
      return
    }
    field(impl.javaClass, "downstreamFlow")?.get(impl)?.let { flow ->
      val data = newData(prefs, 0, loader) ?: return
      try {
        flow.javaClass.getMethod("setValue", Any::class.java).invoke(flow, data)
      } catch (_: Throwable) {
        field(flow.javaClass, "value")?.set(flow, data)
      }
    }
  }

  private fun bumpCoordinator(impl: Any) {
    val lazy = field(impl.javaClass, "coordinator\$delegate") ?: return
    val initialized = try {
      lazy.javaClass.getMethod("isInitialized").invoke(lazy) as Boolean
    } catch (_: Throwable) {
      true
    }
    if (!initialized) return
    val coord = try {
      lazy.javaClass.getMethod("getValue").invoke(lazy)
    } catch (_: Throwable) {
      null
    } ?: return
    val version = field(coord.javaClass, "version")?.get(coord) ?: return
    try {
      version.javaClass.getMethod("incrementAndGet").invoke(version)
    } catch (_: Throwable) {
      try {
        version.javaClass.getMethod("getAndIncrement").invoke(version)
      } catch (_: Throwable) {
      }
    }
  }

  private fun currentVersion(cache: Any): Int {
    val state = try {
      cache.javaClass.getMethod("getCurrentState").invoke(cache)
    } catch (_: Throwable) {
      field(cache.javaClass, "cachedValue")?.get(cache)
    } ?: return 0
    return intProp(state, "version")
  }

  private fun intProp(obj: Any, name: String): Int {
    try {
      val getter = "get" + name.replaceFirstChar { it.uppercase() }
      val v = obj.javaClass.getMethod(getter).invoke(obj)
      if (v is Int) return v
    } catch (_: Throwable) {
    }
    val f = field(obj.javaClass, name)?.get(obj)
    return f as? Int ?: 0
  }

  private fun newData(prefs: Any, version: Int, loader: ClassLoader): Any? {
    val hash = prefs.hashCode()
    val names = arrayOf(
      "androidx.datastore.core.Data",
      "androidx.datastore.core.SingleProcessDataStore\$Data",
    )
    for (name in names) {
      val cl = try {
        Class.forName(name, false, loader)
      } catch (_: Throwable) {
        continue
      }
      for (ctor in cl.declaredConstructors) {
        ctor.isAccessible = true
        try {
          return when (ctor.parameterTypes.size) {
            3 -> ctor.newInstance(prefs, hash, version)
            2 -> ctor.newInstance(prefs, hash)
            else -> continue
          }
        } catch (_: Throwable) {
        }
      }
    }
    return null
  }

  private fun applyTransform(
    prefs: Any,
    loader: ClassLoader,
    transform: (MutableMap<String, Any>) -> Unit,
  ): Any {
    val map = LinkedHashMap(prefsToMap(prefs))
    transform(map)
    return toPreferences(map, loader)
      ?: throw IllegalStateException("Failed to rebuild Preferences")
  }

  private fun prefsToMap(prefs: Any): Map<String, Any> {
    val raw = try {
      prefs.javaClass.getMethod("asMap").invoke(prefs) as? Map<*, *>
    } catch (_: Throwable) {
      null
    } ?: return emptyMap()
    val out = LinkedHashMap<String, Any>(raw.size)
    for ((k, v) in raw) {
      if (k == null || v == null) continue
      val name = try {
        k.javaClass.getMethod("getName").invoke(k) as? String
      } catch (_: Throwable) {
        field(k.javaClass, "name")?.get(k) as? String
      } ?: continue
      out[name] = v
    }
    return out
  }

  private class BlockingContinuation<T> : Continuation<T> {
    private val latch = CountDownLatch(1)
    @Volatile private var outcome: Result<T>? = null
    override val context = EmptyCoroutineContext
    override fun resumeWith(result: Result<T>) {
      outcome = result
      latch.countDown()
    }
    fun await(timeoutMs: Long = 5_000): T {
      if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        throw java.util.concurrent.TimeoutException("DataStore.updateData timed out")
      }
      return result()
    }

    fun awaitForever(): T {
      latch.await()
      return result()
    }

    private fun result(): T {
      return (outcome ?: throw IllegalStateException("DataStore.updateData produced no result"))
        .getOrThrow()
    }
  }

  private fun toPreferences(values: Map<String, Any>, loader: ClassLoader): Any? {
    val keysCl = try {
      Class.forName("androidx.datastore.preferences.core.PreferencesKeys", false, loader)
    } catch (_: Throwable) {
      return null
    }
    val mutableCl = try {
      Class.forName("androidx.datastore.preferences.core.MutablePreferences", false, loader)
    } catch (_: Throwable) {
      return null
    }
    val map = LinkedHashMap<Any, Any>(values.size)
    for ((name, value) in values) {
      val key = preferenceKey(keysCl, name, value) ?: continue
      map[key] = value
    }
    val ctors = mutableCl.declaredConstructors.sortedByDescending { it.parameterTypes.size }
    for (ctor in ctors) {
      val params = ctor.parameterTypes
      if (params.isEmpty() || !java.util.Map::class.java.isAssignableFrom(params[0])) continue
      ctor.isAccessible = true
      try {
        return when (params.size) {
          2 -> ctor.newInstance(map, true)
          1 -> ctor.newInstance(map)
          else -> continue
        }
      } catch (_: Throwable) {
      }
    }
    return null
  }

  private fun preferenceKey(keysCl: Class<*>, name: String, value: Any): Any? {
    val method = when (value) {
      is Boolean -> "booleanKey"
      is Int -> "intKey"
      is Long -> "longKey"
      is Float -> "floatKey"
      is Double -> "doubleKey"
      is Set<*> -> "stringSetKey"
      is ByteArray -> "byteArrayKey"
      else -> "stringKey"
    }
    return try {
      keysCl.getMethod(method, String::class.java).invoke(null, name)
    } catch (_: Throwable) {
      null
    }
  }

  private fun classNames(context: Context): List<String> {
    classNames?.let { return it }
    val out = ArrayList<String>()
    try {
      val loader = context.classLoader
      val pathList = field(loader.javaClass, "pathList")?.get(loader) ?: return emptyList()
      val elements = field(pathList.javaClass, "dexElements")?.get(pathList) as? Array<*>
        ?: return emptyList()
      for (element in elements) {
        if (element == null) continue
        val dexFile = field(element.javaClass, "dexFile")?.get(element) ?: continue
        @Suppress("UNCHECKED_CAST")
        val entries = dexFile.javaClass.getMethod("entries").invoke(dexFile) as Enumeration<String>
        while (entries.hasMoreElements()) {
          val n = entries.nextElement()
          if (skipDexClass(n)) continue
          out.add(n)
        }
      }
    } catch (t: Throwable) {
      LogUtil.w(t, "DataStore class scan failed")
    }
    classNames = out
    return out
  }

  private fun skipDexClass(name: String): Boolean {
    if (name.startsWith("android.") ||
      name.startsWith("androidx.") ||
      name.startsWith("java.") ||
      name.startsWith("javax.") ||
      name.startsWith("kotlin.") ||
      name.startsWith("kotlinx.") ||
      name.startsWith("okhttp3.") ||
      name.startsWith("okio.") ||
      name.startsWith("com.google.")
    ) {
      return true
    }
    val simple = name.substringAfterLast('.')
    if (simple == "R" || simple.startsWith("R$") || simple == "BuildConfig") return true
    val inner = simple.substringAfter('$', missingDelimiterValue = "")
    return inner.isNotEmpty() && inner.all { it.isDigit() }
  }

  private fun field(clazz: Class<*>, name: String): Field? {
    var c: Class<*>? = clazz
    while (c != null && c != Any::class.java) {
      try {
        val f = c.getDeclaredField(name)
        f.isAccessible = true
        return f
      } catch (_: NoSuchFieldException) {
        c = c.superclass
      }
    }
    return null
  }
}
