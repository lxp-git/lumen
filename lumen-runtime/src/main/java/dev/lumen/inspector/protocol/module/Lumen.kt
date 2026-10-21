package dev.lumen.inspector.protocol.module

import dev.lumen.inspector.jsonrpc.JsonRpcPeer
import dev.lumen.inspector.jsonrpc.JsonRpcResult
import dev.lumen.inspector.jsonrpc.RawJsonResult
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod
import dev.lumen.json.annotation.JsonProperty
import dev.lumen.mock.MockEngine
import dev.lumen.store.EventStore
import dev.lumen.store.NetworkRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * Custom CDP domain `Lumen.*` for segment switching, export, mock rules, and agent status.
 * Stock Chrome panels do not call these methods.
 *
 * Class simple name must stay `Lumen` so MethodDispatcher exposes `Lumen.method`.
 */
class Lumen(
  private val store: EventStore,
  private val mockEngine: MockEngine,
) : ChromeDevtoolsDomain {

  @ChromeDevtoolsMethod
  fun getStatus(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val sessions = store.network.listSessions()
    return StatusResult(
      retentionDays = store.config.retentionDays,
      logPageSize = store.config.logPageSize,
      networkCount = store.network.allRecords().size,
      activeLogSegment = store.logs.activeSegmentId,
      segments = store.logs.listSegments().size,
      pastNetworkSessions = sessions.count { !it.current },
      mockRuleCount = mockEngine.listRules().size,
      mockRecording = mockEngine.isRecordingOverrides(),
    )
  }

  @ChromeDevtoolsMethod
  fun getLogSegments(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val items = store.logs.listSegments().map { seg ->
      SegmentItem(
        id = seg.id,
        fileName = seg.fileName,
        path = seg.path,
        sizeBytes = seg.sizeBytes,
        modifiedAtMs = seg.modifiedAtMs,
        lineCount = seg.lineCount,
        firstTimestampMs = seg.firstTimestampMs,
        lastTimestampMs = seg.lastTimestampMs,
      )
    }
    return SegmentsResult(items)
  }

  @ChromeDevtoolsMethod
  fun setActiveLogSegment(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val raw = params?.optString("segmentId")
    val id = raw?.takeIf { it.isNotEmpty() && it != "null" }
    store.logs.setActiveSegment(id)
    return ValueResult("ok")
  }

  @ChromeDevtoolsMethod
  fun exportHar(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val includeBodies = params?.optBoolean("includeBodies", true) ?: true
    val sessionId = params?.optString("sessionId")?.takeIf { it.isNotEmpty() }
    val file = store.network.exportHarToFile(includeBodies, sessionId)
    return ValueResult(file.absolutePath)
  }

  @ChromeDevtoolsMethod
  fun exportLogs(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val days = params?.optInt("days", store.config.retentionDays) ?: store.config.retentionDays
    val file = store.logs.exportBundle(days)
    return ValueResult(file.absolutePath)
  }

  @ChromeDevtoolsMethod
  fun listNetworkSessions(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val items = store.network.listSessions().map { s ->
      SessionItem(
        id = s.id,
        pid = s.pid ?: 0,
        path = s.path,
        sizeBytes = s.sizeBytes,
        modifiedAtMs = s.modifiedAtMs,
        entryCount = s.entryCount,
        current = s.current,
      )
    }
    return SessionListResult(items)
  }

  @ChromeDevtoolsMethod
  fun listMockRules(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val items = mockEngine.listRules().map { r ->
      MockRuleItem(
        id = r.id,
        urlContains = r.urlContains,
        urlGlob = r.urlGlob,
        urlEquals = r.urlEquals,
        method = r.method,
        status = r.status,
        delayMs = r.delayMs,
        source = r.source,
      )
    }
    return MockRuleListResult(items)
  }

  @ChromeDevtoolsMethod
  fun addMockRule(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    if (params == null) return ValueResult("missing params")
    val headers = params.optJSONObject("headers")?.let { obj ->
      obj.keys().asSequence().associateWith { obj.getString(it) }
    } ?: mapOf("Content-Type" to "application/json")
    val rule = mockEngine.addRuntimeRule(
      urlContains = params.optString("urlContains").takeIf { it.isNotEmpty() },
      urlGlob = params.optString("urlGlob").ifEmpty { params.optString("urlPattern") }.takeIf { it.isNotEmpty() },
      status = params.optInt("status", 200),
      headers = headers,
      body = params.optString("body", "{}"),
      method = params.optString("method").takeIf { it.isNotEmpty() },
      delayMs = params.optLong("delayMs", 0L),
    )
    return ValueResult(rule.id)
  }

  @ChromeDevtoolsMethod
  fun removeMockRule(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val id = params?.optString("id").orEmpty()
    val ok = id.isNotEmpty() && mockEngine.removeRule(id)
    return ValueResult(if (ok) "ok" else "not-found")
  }

  /** Toggle persisting DevTools overrides as replayable offline rules. */
  @ChromeDevtoolsMethod
  fun setMockRecording(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val enabled = params?.optBoolean("enabled", true) ?: true
    mockEngine.setRecordOverrides(enabled)
    return ValueResult(if (enabled) "recording" else "stopped")
  }

  /** Bulk-remove mock rules; optional `source` limits to "recorded" / "runtime" / "asset". */
  @ChromeDevtoolsMethod
  fun clearMockRules(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val source = params?.optString("source")?.takeIf { it.isNotEmpty() }
    return ValueResult("removed ${mockEngine.clearRules(source)}")
  }

  // ── Agent queries ─────────────────────────────────────────────────────────
  // Compact reads over the EventStore for scripts (the Lumen skill's CLI). Chrome's
  // panels never call these; they replay events instead.

  /** Newest-last request summaries. Filters: `urlContains`, `method`, `status`, `failed`. */
  @ChromeDevtoolsMethod
  fun listRequests(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val urlContains = params?.optString("urlContains").orEmpty()
    val method = params?.optString("method").orEmpty()
    val status = params?.optInt("status", 0) ?: 0
    val failedOnly = params?.optBoolean("failed", false) ?: false
    val limit = (params?.optInt("limit", 50) ?: 50).coerceIn(1, 1_000)
    val matches = store.network.allRecords().filter { r ->
      (urlContains.isEmpty() || r.url.contains(urlContains, ignoreCase = true)) &&
        (method.isEmpty() || r.method.equals(method, ignoreCase = true)) &&
        (status == 0 || r.statusCode == status) &&
        (!failedOnly || r.failedReason != null || (r.statusCode ?: 0) >= 400)
    }
    val requests = JSONArray()
    for (r in matches.takeLast(limit)) requests.put(summary(r))
    return RawJsonResult(JSONObject().put("total", matches.size).put("requests", requests))
  }

  /**
   * One exchange in full: headers, request body, response body (decoded; base64 when
   * binary), and the newest WebSocket frames. `maxBodyChars` caps the response body.
   */
  @ChromeDevtoolsMethod
  fun getRequest(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val id = params?.optString("requestId").orEmpty()
    val record = store.network.get(id) ?: return RawJsonResult(JSONObject().put("error", "not-found"))
    val maxBodyChars = (params?.optInt("maxBodyChars", 200_000) ?: 200_000).coerceAtLeast(0)
    val maxFrames = (params?.optInt("maxFrames", 200) ?: 200).coerceAtLeast(0)
    val out = summary(record)
      .put("requestHeaders", JSONObject(record.requestHeaders as Map<*, *>))
      .put("responseHeaders", JSONObject(record.responseHeaders as Map<*, *>))
      .put("requestBody", record.requestBody ?: JSONObject.NULL)
      .put("statusText", record.statusText ?: JSONObject.NULL)
      .put("note", record.note ?: JSONObject.NULL)
    store.network.readBody(id)?.let { (body, base64) ->
      out.put("responseBody", body.take(maxBodyChars))
        .put("responseBodyBase64", base64)
        .put("responseBodyTruncated", body.length > maxBodyChars)
    }
    if (record.isWebSocket) {
      val frames = JSONArray()
      for (f in record.wsFrames.snapshot().takeLast(maxFrames)) {
        frames.put(
          JSONObject()
            .put("time", f.timestampMs)
            .put("dir", if (f.outgoing) "send" else "recv")
            .put("opcode", f.opcode)
            .put("payload", f.payload)
            .put("truncated", f.truncated),
        )
      }
      out.put("frames", frames)
    }
    return RawJsonResult(out)
  }

  /**
   * Logcat search over the live tail and archived segments (newest `limit` matches, oldest
   * first). Filters: `contains` (case-insensitive), `level` (minimum: verbose, info,
   * warning, error), `sinceMs` (epoch millis).
   */
  @ChromeDevtoolsMethod
  fun getLogs(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val entries = store.logs.query(
      limit = (params?.optInt("limit", 200) ?: 200).coerceIn(1, 10_000),
      contains = params?.optString("contains")?.takeIf { it.isNotEmpty() },
      minLevel = params?.optString("level")?.takeIf { it.isNotEmpty() },
      sinceMs = params?.optDouble("sinceMs")?.takeUnless { it.isNaN() },
    )
    val logs = JSONArray()
    for (e in entries) {
      logs.put(JSONObject().put("time", e.timestampMs.toLong()).put("level", e.level).put("text", e.text))
    }
    return RawJsonResult(JSONObject().put("logs", logs))
  }

  private fun summary(r: NetworkRecord): JSONObject = JSONObject()
    .put("id", r.requestId)
    .put("method", r.method)
    .put("url", r.url)
    .put("status", r.statusCode ?: JSONObject.NULL)
    .put("mimeType", r.mimeType ?: JSONObject.NULL)
    .put("startedAt", r.startedAtMs)
    .put("durationMs", r.finishedAtMs?.let { it - r.startedAtMs } ?: JSONObject.NULL)
    .put("size", r.encodedDataLength)
    .put("mocked", r.mocked)
    .put("failed", r.failedReason ?: JSONObject.NULL)
    .put("webSocket", r.isWebSocket)
    .put("frameCount", r.wsFrameCount)

  class StatusResult(
    @JvmField @JsonProperty val retentionDays: Int,
    @JvmField @JsonProperty val logPageSize: Int,
    @JvmField @JsonProperty val networkCount: Int,
    @JvmField @JsonProperty val activeLogSegment: String?,
    @JvmField @JsonProperty val segments: Int,
    @JvmField @JsonProperty val pastNetworkSessions: Int,
    @JvmField @JsonProperty val mockRuleCount: Int,
    @JvmField @JsonProperty val mockRecording: Boolean,
  ) : JsonRpcResult

  class ValueResult(
    @JvmField @JsonProperty val value: String,
  ) : JsonRpcResult

  class SegmentItem(
    @JvmField @JsonProperty val id: String,
    @JvmField @JsonProperty val fileName: String,
    @JvmField @JsonProperty val path: String,
    @JvmField @JsonProperty val sizeBytes: Long,
    @JvmField @JsonProperty val modifiedAtMs: Long,
    @JvmField @JsonProperty val lineCount: Int,
    @JvmField @JsonProperty val firstTimestampMs: Long,
    @JvmField @JsonProperty val lastTimestampMs: Long,
  )

  class SegmentsResult(
    @JvmField @JsonProperty val segments: List<SegmentItem>,
  ) : JsonRpcResult

  class SessionItem(
    @JvmField @JsonProperty val id: String,
    @JvmField @JsonProperty val pid: Int,
    @JvmField @JsonProperty val path: String,
    @JvmField @JsonProperty val sizeBytes: Long,
    @JvmField @JsonProperty val modifiedAtMs: Long,
    @JvmField @JsonProperty val entryCount: Int,
    @JvmField @JsonProperty val current: Boolean,
  )

  class SessionListResult(
    @JvmField @JsonProperty val sessions: List<SessionItem>,
  ) : JsonRpcResult

  class MockRuleItem(
    @JvmField @JsonProperty val id: String,
    @JvmField @JsonProperty val urlContains: String?,
    @JvmField @JsonProperty val urlGlob: String?,
    @JvmField @JsonProperty val urlEquals: String?,
    @JvmField @JsonProperty val method: String?,
    @JvmField @JsonProperty val status: Int,
    @JvmField @JsonProperty val delayMs: Long,
    @JvmField @JsonProperty val source: String,
  )

  class MockRuleListResult(
    @JvmField @JsonProperty val rules: List<MockRuleItem>,
  ) : JsonRpcResult
}
