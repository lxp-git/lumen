package dev.lumen.mock

import android.content.Context
import dev.lumen.LumenConfig
import dev.lumen.common.LogRedirector
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Bodies above this stay out of the rule JSON and go to a sidecar file. */
private const val MAX_INLINE_BODY_BYTES = 512 * 1024

/** Overrides above this are served live but not persisted. */
private const val MAX_RECORDED_BODY_BYTES = 5 * 1024 * 1024

/**
 * Shared mock / interception engine.
 *
 * Priority: active CDP Fetch session (Chrome Network Override) > local asset rules.
 * OkHttp interceptor asks [shouldIntercept]; if true it [pause]s until Chrome answers
 * with fulfill / continue / fail.
 */
class MockEngine(
  private val config: LumenConfig,
) {
  data class Pattern(
    val urlPattern: String,
    val resourceType: String? = null,
    val requestStage: String = "Request",
  )

  data class LocalRule(
    val id: String,
    val urlContains: String? = null,
    val urlGlob: String? = null,
    /** Exact URL match — used by recorded DevTools overrides. */
    val urlEquals: String? = null,
    val status: Int = 200,
    val headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
    val body: String = "{}",
    /** Sidecar file for binary or oversized bodies; wins over [body] when set. */
    val bodyFile: File? = null,
    val method: String? = null,
    val delayMs: Long = 0,
    val source: String = "asset",
  ) {
    fun bodyBytes(): ByteArray =
      bodyFile?.takeIf { it.exists() }?.readBytes() ?: body.toByteArray()
  }

  sealed class Decision {
    data class Fulfill(
      val responseCode: Int,
      val responseHeaders: List<Pair<String, String>>,
      val body: ByteArray,
    ) : Decision()

    data class Continue(
      val url: String? = null,
      val method: String? = null,
      val headers: Map<String, String>? = null,
      val postData: ByteArray? = null,
      val responseCode: Int? = null,
      val responseHeaders: List<Pair<String, String>>? = null,
    ) : Decision()

    data class Fail(val errorReason: String) : Decision()

    data class TimedOut(val waitedMs: Long) : Decision()
  }

  data class PausedRequest(
    val fetchId: String,
    val networkId: String,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val postData: String?,
    val resourceType: String,
    val requestStage: String = "Request",
    val responseStatusCode: Int? = null,
    val responseStatusText: String? = null,
    val responseHeaders: List<Pair<String, String>> = emptyList(),
  )

  fun interface FetchListener {
    fun onRequestPaused(paused: PausedRequest)
  }

  private val tag = "MockEngine"
  private val fetchEnabled = AtomicBoolean(false)
  private val patterns = CopyOnWriteArrayList<Pattern>()
  private val localRules = CopyOnWriteArrayList<LocalRule>()
  private val pending = ConcurrentHashMap<String, Pending>()
  private val pendingByNetworkId = ConcurrentHashMap<String, String>()
  private val streams = ConcurrentHashMap<String, ByteArray>()
  private val fetchListeners = CopyOnWriteArrayList<FetchListener>()
  private val nextFetchId = AtomicLong(1)
  private val recordOverrides = AtomicBoolean(false)

  @Volatile
  private var recordedRulesDir: File? = null

  private class Pending(
    val fetchId: String,
    val networkId: String,
    val url: String,
    val method: String,
    val latch: CountDownLatch = CountDownLatch(1),
    @Volatile var decision: Decision? = null,
    @Volatile var responseBody: ByteArray? = null,
  )

  fun addFetchListener(listener: FetchListener) {
    if (!fetchListeners.contains(listener)) {
      fetchListeners.add(listener)
    }
  }

  fun removeFetchListener(listener: FetchListener) = fetchListeners.remove(listener)

  fun enableFetch(patterns: List<Pattern>) {
    this.patterns.clear()
    if (patterns.isEmpty()) {
      // Chrome Local Overrides intercept at Response; a bare Fetch.enable
      // must cover both stages or content override never replaces the body.
      this.patterns.add(Pattern(urlPattern = "*", requestStage = "Request"))
      this.patterns.add(Pattern(urlPattern = "*", requestStage = "Response"))
    } else {
      this.patterns.addAll(patterns)
    }
    fetchEnabled.set(true)
    FetchLog.i("Fetch.enable patterns=${this.patterns.joinToString { "${it.requestStage}:${it.urlPattern}" }}")
  }

  fun disableFetch() {
    fetchEnabled.set(false)
    patterns.clear()
    // Unblock anything waiting so the app doesn't hang.
    for ((id, p) in pending) {
      p.decision = Decision.Continue()
      p.latch.countDown()
      pending.remove(id)
    }
    pendingByNetworkId.clear()
  }

  fun isFetchEnabled(): Boolean = fetchEnabled.get()

  fun loadAssetRules(context: Context) {
    try {
      val assets = context.assets
      val list = try {
        assets.list(config.mockAssetsDir) ?: emptyArray()
      } catch (_: Exception) {
        emptyArray()
      }
      for (name in list) {
        if (!name.endsWith(".json")) continue
        val text = assets.open("${config.mockAssetsDir}/$name").bufferedReader().readText()
        val json = JSONObject(text)
        localRules.add(parseRule(json, id = name.removeSuffix(".json"), source = "asset"))
      }
      if (localRules.isNotEmpty()) {
        LogRedirector.i(tag, "Loaded ${localRules.size} local mock rules")
      }
    } catch (t: Throwable) {
      LogRedirector.w(tag, "loadAssetRules failed", t)
    }
  }

  /**
   * Point the engine at the private directory holding recorded DevTools overrides
   * (e.g. `filesDir/lumen/mocks`) and load whatever earlier sessions persisted.
   * Recorded rules replay through [matchLocalRule], so they keep working with no
   * DevTools attached and across process restarts.
   */
  fun initRecordedRules(dir: File, recordByDefault: Boolean) {
    recordedRulesDir = dir
    recordOverrides.set(recordByDefault)
    if (!dir.isDirectory) return
    val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
    var loaded = 0
    for (file in files.sortedBy { it.name }) {
      try {
        val json = JSONObject(file.readText())
        val rule = parseRule(json, id = file.name.removeSuffix(".json"), source = "recorded", dir = dir)
        localRules.add(0, rule)
        loaded++
      } catch (t: Throwable) {
        LogRedirector.w(tag, "Failed to load recorded rule ${file.name}", t)
      }
    }
    if (loaded > 0) {
      LogRedirector.i(tag, "Loaded $loaded recorded mock rules")
    }
  }

  /** Toggle capturing DevTools fulfils into persistent local rules. */
  fun setRecordOverrides(enabled: Boolean) {
    recordOverrides.set(enabled)
    FetchLog.i("record overrides ${if (enabled) "enabled" else "disabled"}")
  }

  fun isRecordingOverrides(): Boolean = recordOverrides.get()

  fun matchLocalRule(url: String, method: String): LocalRule? {
    if (!config.mockEnabled) return null
    return localRules.firstOrNull { rule ->
      methodMatches(rule, method) && urlMatches(rule, url)
    }
  }

  fun listRules(): List<LocalRule> = localRules.toList()

  fun addRuntimeRule(
    urlContains: String? = null,
    urlGlob: String? = null,
    status: Int = 200,
    headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
    body: String = "{}",
    method: String? = null,
    delayMs: Long = 0,
  ): LocalRule {
    val rule = LocalRule(
      id = "runtime-${nextFetchId.getAndIncrement()}",
      urlContains = urlContains,
      urlGlob = urlGlob,
      status = status,
      headers = headers,
      body = body,
      method = method,
      delayMs = delayMs,
      source = "runtime",
    )
    localRules.add(0, rule)
    return rule
  }

  fun removeRule(id: String): Boolean {
    val removed = localRules.removeAll { it.id == id }
    recordedRulesDir?.let { dir ->
      File(dir, "$id.json").delete()
      File(dir, "$id.body").delete()
    }
    return removed
  }

  fun shouldPauseForFetch(url: String, stage: String = "Request"): Boolean {
    if (!isFetchEnabled()) return false
    return patterns.any {
      it.requestStage.equals(stage, ignoreCase = true) && matches(it.urlPattern, url)
    }
  }

  /**
   * Notify Fetch listeners and block the calling (OkHttp) thread until a decision arrives
   * or [timeoutMs] elapses (auto-continue).
   */
  fun pause(
    networkId: String,
    url: String,
    method: String,
    headers: Map<String, String>,
    postData: String?,
    resourceType: String = "XHR",
    requestStage: String = "Request",
    responseStatusCode: Int? = null,
    responseStatusText: String? = null,
    responseHeaders: List<Pair<String, String>> = emptyList(),
    responseBody: ByteArray? = null,
    timeoutMs: Long = 120_000L,
  ): Decision {
    // Prefixed so a fetchId can never equal a Network requestId (bare integers from
    // NetworkEventReporterImpl); resolvePending's dual lookup relies on the two
    // namespaces staying disjoint.
    val fetchId = "lumen-fetch-${nextFetchId.getAndIncrement()}"
    val pendingWait = Pending(
      fetchId = fetchId,
      networkId = networkId,
      url = url,
      method = method,
      responseBody = responseBody,
    )
    pending[fetchId] = pendingWait
    pendingByNetworkId[networkId] = fetchId
    FetchLog.i(
      "pause fetchId=$fetchId networkId=$networkId stage=$requestStage " +
        "status=$responseStatusCode body=${responseBody?.size ?: -1} $method $url",
    )
    val paused = PausedRequest(
      fetchId = fetchId,
      networkId = networkId,
      url = url,
      method = method,
      headers = headers,
      postData = postData,
      resourceType = resourceType,
      requestStage = requestStage,
      responseStatusCode = responseStatusCode,
      responseStatusText = responseStatusText,
      responseHeaders = responseHeaders,
    )
    for (l in fetchListeners) {
      try {
        l.onRequestPaused(paused)
      } catch (t: Throwable) {
        LogRedirector.w(tag, "FetchListener failed", t)
      }
    }
    val ok = pendingWait.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    pending.remove(fetchId)
    pendingByNetworkId.remove(networkId)
    if (!ok) {
      FetchLog.w("Fetch pause timed out fetchId=$fetchId after ${timeoutMs}ms — continuing $url")
      return Decision.TimedOut(timeoutMs)
    }
    val decision = pendingWait.decision ?: Decision.Continue()
    FetchLog.i("resume fetchId=$fetchId decision=${decision::class.java.simpleName}")
    return decision
  }

  fun getPausedBody(requestId: String): ByteArray? = resolvePending(requestId)?.responseBody

  fun openBodyStream(requestId: String): String? {
    val body = getPausedBody(requestId) ?: return null
    val handle = "lumen-stream-$requestId"
    streams[handle] = body
    FetchLog.i("openBodyStream requestId=$requestId handle=$handle bytes=${body.size}")
    return handle
  }

  fun readStream(handle: String): ByteArray? = streams[handle]

  fun closeStream(handle: String) {
    streams.remove(handle)
  }

  fun fulfillRequest(
    fetchId: String,
    responseCode: Int,
    responseHeaders: List<Pair<String, String>>,
    body: ByteArray?,
  ) {
    val pendingWait = resolvePending(fetchId)
    val resolvedBody = when {
      body != null -> body
      pendingWait?.responseBody != null -> pendingWait.responseBody!!
      else -> ByteArray(0)
    }
    FetchLog.i(
      "fulfill requestId=$fetchId code=$responseCode headers=${responseHeaders.size} " +
        "body=${resolvedBody.size} usedOriginal=${body == null}",
    )
    // An explicit body means Chrome replaced the response (Local Overrides /
    // manual fulfil) rather than passing the original through — worth keeping.
    if (body != null && pendingWait != null) {
      maybeRecordOverride(pendingWait, responseCode, responseHeaders, body)
    }
    complete(fetchId, Decision.Fulfill(responseCode, responseHeaders, resolvedBody))
  }

  fun continueRequest(
    fetchId: String,
    url: String? = null,
    method: String? = null,
    headers: Map<String, String>? = null,
    postData: ByteArray? = null,
    responseCode: Int? = null,
    responseHeaders: List<Pair<String, String>>? = null,
  ) {
    complete(
      fetchId,
      Decision.Continue(url, method, headers, postData, responseCode, responseHeaders),
    )
  }

  fun failRequest(fetchId: String, errorReason: String) {
    FetchLog.w("fail requestId=$fetchId reason=$errorReason")
    complete(fetchId, Decision.Fail(errorReason))
  }

  private fun resolvePending(requestId: String): Pending? {
    pending[requestId]?.let { return it }
    val mapped = pendingByNetworkId[requestId] ?: return null
    return pending[mapped]
  }

  private fun complete(fetchId: String, decision: Decision) {
    val p = resolvePending(fetchId)
    if (p == null) {
      FetchLog.w("complete missed requestId=$fetchId pending=${pending.keys} decision=${decision::class.java.simpleName}")
      return
    }
    p.decision = decision
    p.latch.countDown()
  }

  private fun parseRule(json: JSONObject, id: String, source: String, dir: File? = null): LocalRule {
    val method = if (json.has("method") && !json.isNull("method") && json.getString("method").isNotEmpty()) {
      json.getString("method")
    } else {
      null
    }
    val contains = json.optString("urlContains", "").takeIf { it.isNotEmpty() }
    val glob = json.optString("urlGlob", json.optString("urlPattern", "")).takeIf { it.isNotEmpty() }
    val equals = json.optString("urlEquals", "").takeIf { it.isNotEmpty() }
    val bodyFileName = json.optString("bodyFile", "").takeIf { it.isNotEmpty() }
    return LocalRule(
      id = id,
      urlContains = contains,
      urlGlob = glob,
      urlEquals = equals,
      status = json.optInt("status", 200),
      headers = json.optJSONObject("headers")?.let { obj ->
        obj.keys().asSequence().associateWith { obj.getString(it) }
      } ?: mapOf("Content-Type" to "application/json"),
      body = json.optString("body", "{}"),
      bodyFile = if (bodyFileName != null && dir != null) File(dir, bodyFileName) else null,
      method = method,
      delayMs = json.optLong("delayMs", 0L),
      source = source,
    )
  }

  private fun maybeRecordOverride(
    pending: Pending,
    status: Int,
    headers: List<Pair<String, String>>,
    body: ByteArray,
  ) {
    if (!recordOverrides.get()) return
    val dir = recordedRulesDir ?: return
    if (body.size > MAX_RECORDED_BODY_BYTES) {
      FetchLog.w("override for ${pending.url} is ${body.size} bytes — served but not recorded")
      return
    }
    try {
      val rule = persistRecordedRule(dir, pending.url, pending.method, status, headers, body)
      localRules.removeAll { it.id == rule.id }
      localRules.add(0, rule)
      FetchLog.i("recorded override ${rule.id} ${pending.method} ${pending.url}")
    } catch (t: Throwable) {
      LogRedirector.w(tag, "Failed to record override for ${pending.url}", t)
    }
  }

  private fun persistRecordedRule(
    dir: File,
    url: String,
    method: String,
    status: Int,
    headers: List<Pair<String, String>>,
    body: ByteArray,
  ): LocalRule {
    dir.mkdirs()
    val id = "recorded-" + stableId(method, url)
    val headerMap = LinkedHashMap<String, String>()
    for ((name, value) in headers) {
      // The recorded body is already decoded and possibly edited; stale
      // Content-Encoding / Content-Length would corrupt the replayed response.
      if (name.equals("Content-Encoding", ignoreCase = true)) continue
      if (name.equals("Content-Length", ignoreCase = true)) continue
      headerMap[name] = value
    }
    val inlineBody = decodeUtf8OrNull(body)
    val sidecar = File(dir, "$id.body")
    if (inlineBody == null) {
      sidecar.writeBytes(body)
    } else {
      sidecar.delete()
    }
    val json = JSONObject()
      .put("urlEquals", url)
      .put("method", method)
      .put("status", status)
      .put("headers", JSONObject(headerMap as Map<*, *>))
    if (inlineBody == null) {
      json.put("bodyFile", sidecar.name)
    } else {
      json.put("body", inlineBody)
    }
    File(dir, "$id.json").writeText(json.toString(2))
    return LocalRule(
      id = id,
      urlEquals = url,
      status = status,
      headers = headerMap,
      body = inlineBody ?: "",
      bodyFile = if (inlineBody == null) sidecar else null,
      method = method,
      source = "recorded",
    )
  }

  private fun stableId(method: String, url: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest("$method $url".toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(16)
  }

  private fun decodeUtf8OrNull(bytes: ByteArray): String? {
    if (bytes.size > MAX_INLINE_BODY_BYTES) return null
    return try {
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    } catch (_: CharacterCodingException) {
      null
    }
  }

  private fun methodMatches(rule: LocalRule, method: String): Boolean =
    rule.method == null || rule.method.equals(method, ignoreCase = true)

  private fun urlMatches(rule: LocalRule, url: String): Boolean {
    val equals = rule.urlEquals
    if (equals != null) return equals == url
    val glob = rule.urlGlob
    if (glob != null) return matches(glob, url)
    val contains = rule.urlContains
    return contains != null && url.contains(contains)
  }

  private fun matches(pattern: String, url: String): Boolean {
    if (pattern == "*" || pattern == "<all_urls>") return true
    // CDP urlPattern: '*' = any run, '?' = one char, '\' escapes the next char.
    val regex = buildString {
      append('^')
      var index = 0
      while (index < pattern.length) {
        when (val char = pattern[index]) {
          '*' -> append(".*")
          '?' -> append('.')
          '\\' -> {
            if (index + 1 < pattern.length) {
              append(Regex.escape(pattern[index + 1].toString()))
              index++
            }
          }
          else -> append(Regex.escape(char.toString()))
        }
        index++
      }
      append('$')
    }
    return Regex(regex, RegexOption.IGNORE_CASE).matches(url)
  }
}
