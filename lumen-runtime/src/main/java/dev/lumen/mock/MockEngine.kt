package dev.lumen.mock

import android.content.Context
import dev.lumen.LumenConfig
import dev.lumen.common.LogRedirector
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    val status: Int = 200,
    val headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
    val body: String = "{}",
    val method: String? = null,
    val delayMs: Long = 0,
    val source: String = "asset",
  )

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
  private val fetchListeners = CopyOnWriteArrayList<FetchListener>()
  private val nextFetchId = AtomicLong(1)

  private class Pending(
    val latch: CountDownLatch = CountDownLatch(1),
    @Volatile var decision: Decision? = null,
    @Volatile var responseBody: ByteArray? = null,
  )

  fun addFetchListener(listener: FetchListener) = fetchListeners.add(listener)
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

  fun removeRule(id: String): Boolean = localRules.removeAll { it.id == id }

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
    resourceType: String = "Other",
    requestStage: String = "Request",
    responseStatusCode: Int? = null,
    responseStatusText: String? = null,
    responseHeaders: List<Pair<String, String>> = emptyList(),
    responseBody: ByteArray? = null,
    timeoutMs: Long = 120_000L,
  ): Decision {
    val fetchId = nextFetchId.getAndIncrement().toString()
    val pendingWait = Pending(responseBody = responseBody)
    pending[fetchId] = pendingWait
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
    if (!ok) {
      LogRedirector.w(tag, "Fetch pause timed out for $url after ${timeoutMs}ms — continuing")
      return Decision.TimedOut(timeoutMs)
    }
    return pendingWait.decision ?: Decision.Continue()
  }

  fun getPausedBody(fetchId: String): ByteArray? = pending[fetchId]?.responseBody

  fun fulfillRequest(
    fetchId: String,
    responseCode: Int,
    responseHeaders: List<Pair<String, String>>,
    body: ByteArray,
  ) {
    complete(fetchId, Decision.Fulfill(responseCode, responseHeaders, body))
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
    complete(fetchId, Decision.Fail(errorReason))
  }

  private fun complete(fetchId: String, decision: Decision) {
    val p = pending[fetchId] ?: return
    p.decision = decision
    p.latch.countDown()
  }

  private fun parseRule(json: JSONObject, id: String, source: String): LocalRule {
    val method = if (json.has("method") && !json.isNull("method") && json.getString("method").isNotEmpty()) {
      json.getString("method")
    } else {
      null
    }
    val contains = json.optString("urlContains", "").takeIf { it.isNotEmpty() }
    val glob = json.optString("urlGlob", json.optString("urlPattern", "")).takeIf { it.isNotEmpty() }
    return LocalRule(
      id = id,
      urlContains = contains,
      urlGlob = glob,
      status = json.optInt("status", 200),
      headers = json.optJSONObject("headers")?.let { obj ->
        obj.keys().asSequence().associateWith { obj.getString(it) }
      } ?: mapOf("Content-Type" to "application/json"),
      body = json.optString("body", "{}"),
      method = method,
      delayMs = json.optLong("delayMs", 0L),
      source = source,
    )
  }

  private fun methodMatches(rule: LocalRule, method: String): Boolean =
    rule.method == null || rule.method.equals(method, ignoreCase = true)

  private fun urlMatches(rule: LocalRule, url: String): Boolean {
    val glob = rule.urlGlob
    if (glob != null) return matches(glob, url)
    val contains = rule.urlContains
    return contains != null && url.contains(contains)
  }

  private fun matches(pattern: String, url: String): Boolean {
    if (pattern == "*" || pattern == "<all_urls>") return true
    // CDP patterns are simple globs: * is a wildcard, everything else is literal.
    // (Regex.escape produces \Q…\E quoting, so escape each literal segment and
    // join with ".*" instead of trying to post-process the escaped string.)
    val regex = Regex(
      pattern.split("*").joinToString(separator = ".*", transform = Regex::escape),
      RegexOption.IGNORE_CASE,
    )
    return regex.matches(url)
  }
}
