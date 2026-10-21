package dev.lumen.store

import android.content.Context
import android.util.Base64
import dev.lumen.BuildConfig
import dev.lumen.LumenConfig
import dev.lumen.common.LogRedirector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Process-lifetime ring of network exchanges plus durable metadata/body spill under
 * `filesDir/lumen/network/`. Capture is always on; DevTools peers only subscribe.
 */
class NetworkArchive(
  context: Context,
  private val config: LumenConfig,
) {
  private val tag = "NetworkArchive"
  private val root = File(context.applicationContext.filesDir, "lumen/network").also { it.mkdirs() }
  private val bodiesDir = File(root, "bodies").also { it.mkdirs() }
  private val metaFile = File(root, "session-${android.os.Process.myPid()}.jsonl")
  private val meta = JsonlAppender(metaFile, "lumen-net-meta")

  private val lock = Any()
  private val bodyPrune = BodyPruneScheduler(
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "lumen-body-prune").apply { isDaemon = true }
    },
  ) {
    try {
      BodyQuota.prune(bodiesDir, config.networkBodyQuotaBytes)
    } catch (t: Throwable) {
      LogRedirector.w(tag, "body prune failed", t)
    }
  }
  private val records = LinkedHashMap<String, NetworkRecord>()
  private val order = ArrayDeque<String>()

  fun put(record: NetworkRecord) {
    synchronized(lock) {
      if (!records.containsKey(record.requestId)) {
        order.addLast(record.requestId)
      }
      records[record.requestId] = record
      trimLocked()
      appendMetaLocked(record)
    }
  }

  /**
   * Mutates the record under the archive lock. [persistMeta] appends a session-jsonl line;
   * pass false for changes that line does not carry (body path, frames).
   */
  fun update(
    requestId: String,
    persistMeta: Boolean = true,
    mutator: (NetworkRecord) -> Unit,
  ): NetworkRecord? = synchronized(lock) {
    val existing = records[requestId] ?: return null
    mutator(existing)
    if (persistMeta) {
      appendMetaLocked(existing)
    }
    existing
  }

  /**
   * Append a WS frame for late-connect replay. Caps per-socket count and payload size.
   * Does not write a session-jsonl line per frame.
   */
  fun archiveWsFrame(
    requestId: String,
    outgoing: Boolean,
    text: String? = null,
    binary: ByteArray? = null,
  ) {
    val now = System.currentTimeMillis()
    val mono = android.os.SystemClock.elapsedRealtime()
    val maxChars = config.maxWsFrameChars
    val frame = if (binary != null) {
      val raw = if (binary.size > maxChars) binary.copyOf(maxChars) else binary
      WsFrame(
        timestampMs = now,
        timestampMonoMs = mono,
        outgoing = outgoing,
        opcode = 2,
        payload = Base64.encodeToString(raw, Base64.NO_WRAP),
        binary = true,
        truncated = raw.size < binary.size,
      )
    } else {
      val src = text ?: ""
      val clipped = if (src.length > maxChars) src.substring(0, maxChars) else src
      WsFrame(
        timestampMs = now,
        timestampMonoMs = mono,
        outgoing = outgoing,
        opcode = 1,
        payload = clipped,
        binary = false,
        truncated = clipped.length < src.length,
      )
    }
    val record = update(requestId, persistMeta = false) {
      it.isWebSocket = true
      it.resourceType = "WebSocket"
      it.wsFrameCount += 1
      it.encodedDataLength += if (binary != null) binary.size.toLong() else (text?.length ?: 0).toLong()
    } ?: return
    // Outside the archive lock: the frame log has its own, and eviction scans the list.
    record.wsFrames.add(frame, config.maxWsFramesPerSocket)
  }

  fun listSessions(): List<NetworkSessionInfo> {
    val files = root.listFiles { f ->
      f.isFile && f.name.startsWith("session-") && f.name.endsWith(".jsonl")
    } ?: emptyArray()
    return files.sortedBy { it.lastModified() }.map { f ->
      val stem = f.name.removeSuffix(".jsonl")
      val pid = stem.removePrefix("session-").toIntOrNull()
      NetworkSessionInfo(
        id = stem,
        pid = pid,
        path = f.absolutePath,
        sizeBytes = f.length(),
        modifiedAtMs = f.lastModified(),
        entryCount = countLines(f),
        current = f.absolutePath == metaFile.absolutePath,
      )
    }
  }

  /** Rebuild metadata-only records from a previous process session jsonl. */
  fun loadSessionSummaries(sessionId: String): List<NetworkRecord> {
    val file = File(root, "$sessionId.jsonl")
    if (!file.exists()) return emptyList()
    // A request appends one line per lifecycle update; keep the last line per id
    // (most complete) in first-seen order so exports don't contain duplicates.
    val out = LinkedHashMap<String, NetworkRecord>()
    try {
      file.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        try {
          val o = JSONObject(line)
          out[o.optString("id")] =
            NetworkRecord(
              requestId = o.optString("id"),
              url = o.optString("url"),
              method = o.optString("method", "GET"),
              requestHeaders = emptyMap(),
              requestBody = null,
              startedAtMs = o.optLong("startedAt"),
              startedAtMonotonicMs = 0L,
              statusCode = if (o.has("status") && !o.isNull("status")) o.optInt("status") else null,
              finishedAtMs = if (o.has("finishedAt") && !o.isNull("finishedAt")) o.optLong("finishedAt") else null,
              mocked = o.optBoolean("mocked", false),
              encodedDataLength = o.optLong("encodedDataLength", 0L),
              isWebSocket = o.optBoolean("ws", false),
              resourceType = o.optString("type", "Other"),
            )
        } catch (_: Exception) {
        }
      }
    } catch (e: IOException) {
      LogRedirector.w(tag, "loadSessionSummaries failed", e)
    }
    return ArrayList(out.values)
  }

  fun get(requestId: String): NetworkRecord? = synchronized(lock) { records[requestId] }

  /** Newest-last list of the current process session, capped for DevTools replay. */
  fun snapshotForReplay(limit: Int = config.networkReplayLimit): List<NetworkRecord> {
    synchronized(lock) {
      val all = order.mapNotNull { records[it] }
      return if (all.size <= limit) all else all.subList(all.size - limit, all.size)
    }
  }

  fun allRecords(): List<NetworkRecord> = synchronized(lock) { order.mapNotNull { records[it] } }

  /**
   * Sink for the response body of [requestId], written exactly as the app reads it and
   * capped at [LumenConfig.maxBodyBytes]. [contentEncoding] is the response's
   * `Content-Encoding`; a supported one is reversed by [readBody], not here.
   */
  fun openBodySink(requestId: String, contentEncoding: String?): OutputStream {
    val file = File(bodiesDir, "body-$requestId")
    update(requestId, persistMeta = false) {
      it.responseBodyPath = file.absolutePath
      it.responseBodyEncoding = BodyCodec.supportedEncoding(contentEncoding)
    }
    return object : FilterOutputStream(file.outputStream()) {
      private var written = 0L

      override fun write(b: Int) {
        if (written >= config.maxBodyBytes) return
        out.write(b)
        written++
      }

      // Write through `out` directly: FilterOutputStream.write(byte[],int,int)
      // delegates to the overridden write(int), which would double-count.
      override fun write(b: ByteArray, off: Int, len: Int) {
        val allowed = minOf(len.toLong(), config.maxBodyBytes - written).toInt()
        if (allowed <= 0) return
        out.write(b, off, allowed)
        written += allowed
      }

      override fun close() {
        super.close()
        bodyPrune.request()
      }
    }
  }

  /**
   * The body of [requestId] for DevTools: decoded, then UTF-8 text when it looks like text
   * (`second == false`) or base64 otherwise (`second == true`). Null when nothing was captured.
   */
  fun readBody(requestId: String): Pair<String, Boolean>? {
    val record = get(requestId) ?: return null
    val file = record.responseBodyPath?.let(::File)?.takeIf { it.exists() } ?: return null
    val bytes = try {
      file.inputStream().use {
        BodyCodec.decode(it, record.responseBodyEncoding, MAX_DECODED_BODY_FACTOR * config.maxBodyBytes)
      }
    } catch (e: IOException) {
      LogRedirector.w(tag, "readBody failed for $requestId", e)
      return null
    }
    return if (BodyCodec.isText(record.mimeType, bytes)) {
      Pair(String(bytes, Charsets.UTF_8), false)
    } else {
      Pair(Base64.encodeToString(bytes, Base64.NO_WRAP), true)
    }
  }

  /** HAR 1.2 document covering the current process snapshot (optionally with bodies). */
  fun exportHar(includeBodies: Boolean = true): JSONObject {
    val entries = JSONArray()
    for (record in allRecords()) {
      entries.put(toHarEntry(record, includeBodies))
    }
    return JSONObject()
      .put("log", JSONObject()
        .put("version", "1.2")
        .put("creator", JSONObject().put("name", "Lumen").put("version", BuildConfig.LUMEN_VERSION))
        .put("entries", entries))
  }

  fun exportHarToFile(includeBodies: Boolean = true, sessionId: String? = null): File {
    val json = if (sessionId.isNullOrEmpty() || sessionId == "current") {
      exportHar(includeBodies)
    } else {
      exportHarFromSummaries(loadSessionSummaries(sessionId), includeBodies)
    }
    val label = if (sessionId.isNullOrEmpty() || sessionId == "current") {
      "current"
    } else {
      sessionId
    }
    val out = File(root, "export-$label-${System.currentTimeMillis()}.har")
    out.writeText(json.toString(2))
    return out
  }

  private fun exportHarFromSummaries(
    summaries: List<NetworkRecord>,
    includeBodies: Boolean,
  ): JSONObject {
    val entries = JSONArray()
    for (record in summaries) {
      entries.put(toHarEntry(record, includeBodies))
    }
    return JSONObject()
      .put(
        "log",
        JSONObject()
          .put("version", "1.2")
          .put("creator", JSONObject().put("name", "Lumen").put("version", BuildConfig.LUMEN_VERSION))
          .put("comment", "Previous process session — metadata only, not injected into Network.enable")
          .put("entries", entries),
      )
  }

  private fun countLines(file: File): Int {
    var n = 0
    try {
      file.forEachLine { if (it.isNotBlank()) n++ }
    } catch (_: IOException) {
    }
    return n
  }

  fun pruneRetention() {
    val cutoff = System.currentTimeMillis() - config.retentionDays * 86_400_000L
    bodiesDir.listFiles()?.forEach { f ->
      if (f.lastModified() < cutoff) f.delete()
    }
    root.listFiles { f ->
      (f.name.startsWith("session-") && f.name.endsWith(".jsonl")) ||
        (f.name.startsWith("export-") && f.name.endsWith(".har"))
    }?.forEach { f ->
      if (f.lastModified() < cutoff) f.delete()
    }
  }

  private fun toHarEntry(record: NetworkRecord, includeBodies: Boolean): JSONObject {
    val request = JSONObject()
      .put("method", record.method)
      .put("url", record.url)
      .put("httpVersion", "HTTP/1.1")
      .put("headers", headersToHar(record.requestHeaders))
      .put("queryString", JSONArray())
      .put("headersSize", -1)
      .put("bodySize", record.requestBody?.toByteArray()?.size ?: 0)
    if (record.requestBody != null) {
      request.put("postData", JSONObject()
        .put("mimeType", record.requestHeaders["Content-Type"] ?: "application/octet-stream")
        .put("text", record.requestBody))
    }

    val response = JSONObject()
      .put("status", record.statusCode ?: 0)
      .put("statusText", record.statusText ?: "")
      .put("httpVersion", "HTTP/1.1")
      .put("headers", headersToHar(record.responseHeaders))
      .put("cookies", JSONArray())
      .put("redirectURL", "")
      .put("headersSize", -1)
      .put("bodySize", record.encodedDataLength)
      .put("content", JSONObject()
        .put("size", record.encodedDataLength)
        .put("mimeType", record.mimeType ?: "application/octet-stream")
        .apply {
          if (includeBodies) {
            val body = readBody(record.requestId)
            if (body != null) {
              put("text", body.first)
              if (body.second) put("encoding", "base64")
            }
          }
        })

    // UTC with a literal Z: ISO 8601 without the API 24 'XXX' pattern.
    val started = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
      .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
      .format(java.util.Date(record.startedAtMs))
    val time = ((record.finishedAtMs ?: record.startedAtMs) - record.startedAtMs).toDouble()

    return JSONObject()
      .put("startedDateTime", started)
      .put("time", time)
      .put("request", request)
      .put("response", response)
      .put("cache", JSONObject())
      .put("timings", JSONObject()
        .put("send", 0)
        .put("wait", time)
        .put("receive", 0))
      .put("_lumenRequestId", record.requestId)
      .put("_lumenMocked", record.mocked)
  }

  private fun headersToHar(headers: Map<String, String>): JSONArray {
    val arr = JSONArray()
    for ((k, v) in headers) {
      arr.put(JSONObject().put("name", k).put("value", v))
    }
    return arr
  }

  private fun trimLocked() {
    // Keep an in-memory bound so late DevTools attach stays cheap.
    val maxInMemory = maxOf(config.networkReplayLimit * 5, 500)
    while (order.size > maxInMemory) {
      val oldest = order.removeFirst()
      records.remove(oldest)
    }
  }

  private fun appendMetaLocked(record: NetworkRecord) {
    // One-line summary; full fidelity is the in-memory map + body files for this process.
    // Built under the lock (consistent snapshot), written by the appender thread.
    meta.append(
      JSONObject()
        .put("id", record.requestId)
        .put("url", record.url)
        .put("method", record.method)
        .put("status", record.statusCode)
        .put("startedAt", record.startedAtMs)
        .put("finishedAt", record.finishedAtMs)
        .put("mocked", record.mocked)
        .put("encodedDataLength", record.encodedDataLength)
        .put("ws", record.isWebSocket)
        .put("type", record.resourceType)
        .toString(),
    )
  }

  private companion object {
    /** Decoded bodies may exceed the stored (compressed) cap; bound the inflation. */
    const val MAX_DECODED_BODY_FACTOR = 4
  }
}
