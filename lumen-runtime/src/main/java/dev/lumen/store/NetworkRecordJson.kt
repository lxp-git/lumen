package dev.lumen.store

import org.json.JSONObject

/**
 * Session-jsonl lines for [NetworkRecord]. A record appends one line per lifecycle update; each
 * line repeats the scalar fields but carries a header map only when the record holds a different
 * map than the previous line did, so a request's headers are written about once. Readers fold a
 * record's lines together with [merge]. Bodies live in sidecar files, never in the jsonl.
 */
internal object NetworkRecordJson {

  /** The next line for [record]; marks the header maps it includes as written. */
  fun line(record: NetworkRecord): JSONObject {
    // put(name, null) removes the key, so an unset field never clobbers an earlier line's value.
    val o = JSONObject()
      .put("id", record.requestId)
      .put("url", record.url)
      .put("method", record.method)
      .put("status", record.statusCode)
      .put("statusText", record.statusText)
      .put("startedAt", record.startedAtMs)
      .put("startedMono", record.startedAtMonotonicMs)
      .put("finishedAt", record.finishedAtMs)
      .put("mocked", record.mocked)
      .put("encodedDataLength", record.encodedDataLength)
      .put("ws", record.isWebSocket)
      .put("type", record.resourceType)
      .put("mimeType", record.mimeType)
      .put("failed", record.failedReason)
      .put("note", record.note)
      .put("bodyEncoding", record.responseBodyEncoding)
      .put("fromDiskCache", record.fromDiskCache)
      .put("frames", record.wsFrameCount)
    val requestHeaders = record.requestHeaders
    if (requestHeaders !== record.persistedRequestHeaders &&
      (requestHeaders.isNotEmpty() || record.persistedRequestHeaders != null)
    ) {
      o.put("requestHeaders", JSONObject(requestHeaders as Map<*, *>))
      record.persistedRequestHeaders = requestHeaders
    }
    val responseHeaders = record.responseHeaders
    if (responseHeaders !== record.persistedResponseHeaders &&
      (responseHeaders.isNotEmpty() || record.persistedResponseHeaders != null)
    ) {
      o.put("responseHeaders", JSONObject(responseHeaders as Map<*, *>))
      record.persistedResponseHeaders = responseHeaders
    }
    return o
  }

  /** Folds a later [line] of the same record into [into]: the line's keys win. */
  fun merge(into: JSONObject, line: JSONObject) {
    for (key in line.keys()) {
      into.put(key, line.get(key))
    }
  }

  /** A record rebuilt from its merged lines; bodies and WebSocket frames are not included. */
  fun record(o: JSONObject): NetworkRecord =
    NetworkRecord(
      requestId = o.optString("id"),
      url = o.optString("url"),
      method = o.optString("method", "GET"),
      requestHeaders = headers(o.optJSONObject("requestHeaders")),
      requestBody = null,
      startedAtMs = o.optLong("startedAt"),
      startedAtMonotonicMs = o.optLong("startedMono", 0L),
      statusCode = if (o.has("status") && !o.isNull("status")) o.optInt("status") else null,
      statusText = o.stringOrNull("statusText"),
      responseHeaders = headers(o.optJSONObject("responseHeaders")),
      mimeType = o.stringOrNull("mimeType"),
      fromDiskCache = o.optBoolean("fromDiskCache", false),
      finishedAtMs = if (o.has("finishedAt") && !o.isNull("finishedAt")) o.optLong("finishedAt") else null,
      failedReason = o.stringOrNull("failed"),
      encodedDataLength = o.optLong("encodedDataLength", 0L),
      responseBodyEncoding = o.stringOrNull("bodyEncoding"),
      mocked = o.optBoolean("mocked", false),
      resourceType = o.optString("type", "Other"),
      isWebSocket = o.optBoolean("ws", false),
      wsFrameCount = o.optInt("frames", 0),
      note = o.stringOrNull("note"),
    )

  private fun headers(o: JSONObject?): Map<String, String> {
    if (o == null) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (key in o.keys()) {
      out[key] = o.optString(key)
    }
    return out
  }

  private fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
}
