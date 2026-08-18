package dev.lumen.store

import android.content.Context
import android.util.Base64
import dev.lumen.LumenConfig
import dev.lumen.common.LogRedirector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-lifetime ring of network exchanges plus durable metadata/body spill under
 * `filesDir/lumen/network/`. Capture is always on; DevTools peers only subscribe.
 */
class NetworkArchive(
  context: Context,
  private val config: LumenConfig,
) {
  fun interface Listener {
    fun onRecordUpdated(record: NetworkRecord)
  }

  private val tag = "NetworkArchive"
  private val root = File(context.applicationContext.filesDir, "lumen/network").also { it.mkdirs() }
  private val bodiesDir = File(root, "bodies").also { it.mkdirs() }
  private val metaFile = File(root, "session-${android.os.Process.myPid()}.jsonl")

  private val lock = Any()
  private val records = LinkedHashMap<String, NetworkRecord>()
  private val order = ArrayDeque<String>()
  private val listeners = CopyOnWriteArrayList<Listener>()

  val processStartedAtMs: Long = System.currentTimeMillis()

  fun addListener(listener: Listener) {
    listeners.add(listener)
  }

  fun removeListener(listener: Listener) {
    listeners.remove(listener)
  }

  fun put(record: NetworkRecord) {
    synchronized(lock) {
      if (!records.containsKey(record.requestId)) {
        order.addLast(record.requestId)
      }
      records[record.requestId] = record
      trimLocked()
      appendMetaLocked(record)
    }
    notify(record)
  }

  fun update(
    requestId: String,
    persistMeta: Boolean = true,
    mutator: (NetworkRecord) -> Unit,
  ): NetworkRecord? {
    val updated = synchronized(lock) {
      val existing = records[requestId] ?: return null
      mutator(existing)
      if (persistMeta) {
        appendMetaLocked(existing)
      }
      existing
    }
    notify(updated)
    return updated
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
    update(requestId, persistMeta = false) {
      it.isWebSocket = true
      it.resourceType = "WebSocket"
      it.wsFrameCount += 1
      it.encodedDataLength += if (binary != null) binary.size.toLong() else (text?.length ?: 0).toLong()
      it.wsLastFrameHint = if (outgoing) "send" else "recv"
      it.wsFrames.add(frame)
      trimWsFrames(it.wsFrames, config.maxWsFramesPerSocket)
    }
  }

  /**
   * Drop Engine.IO ping/pong (`2` / `3`) before dropping chat/message frames so a
   * long-lived Socket.IO LLM connection still has the conversation on late attach.
   */
  private fun trimWsFrames(frames: MutableList<WsFrame>, max: Int) {
    while (frames.size > max) {
      val control = frames.indexOfFirst(::isEngineIoControl)
      if (control >= 0) {
        frames.removeAt(control)
      } else {
        frames.removeAt(0)
      }
    }
  }

  private fun isEngineIoControl(frame: WsFrame): Boolean {
    if (frame.binary) return false
    val p = frame.payload
    if (p.isEmpty()) return false
    val c = p[0]
    return (c == '2' || c == '3') && p.length <= 16
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
   * Tee sink for response bodies. Returns an [java.io.OutputStream] that writes to a
   * capped body file associated with [requestId]. First byte mirrors Stetho convention
   * (0 = utf8 text, 1 = base64 payload follows) when [base64Encode] is used by callers
   * that pre-encode; for raw tee we store plain bytes and set the flag on the record.
   */
  fun openBodySink(requestId: String, base64Encoded: Boolean): java.io.OutputStream {
    val file = File(bodiesDir, "body-$requestId")
    update(requestId) {
      it.responseBodyPath = file.absolutePath
      it.responseBodyBase64 = base64Encoded
    }
    // Prefix byte keeps compatibility with ResponseBodyFileManager readers.
    val raw = file.outputStream()
    raw.write(if (base64Encoded) 1 else 0)
    return object : java.io.FilterOutputStream(raw) {
      private var written = 0L
      private var truncated = false

      // Write through `out` directly: FilterOutputStream.write(byte[],int,int)
      // delegates to the overridden write(int), which would double-count.
      override fun write(b: Int) {
        if (truncated || written >= config.maxBodyBytes) {
          truncated = true
          return
        }
        out.write(b)
        written++
      }

      override fun write(b: ByteArray, off: Int, len: Int) {
        if (truncated || len <= 0) return
        val allowed = minOf(len.toLong(), config.maxBodyBytes - written).toInt()
        if (allowed <= 0) {
          truncated = true
          return
        }
        out.write(b, off, allowed)
        written += allowed
        if (allowed < len) truncated = true
      }

      override fun close() {
        super.close()
        update(requestId) { /* path already set */ }
        pruneBodiesIfNeeded()
      }
    }
  }

  fun readBody(requestId: String): Pair<String, Boolean>? {
    val record = get(requestId) ?: return null
    val path = record.responseBodyPath ?: return null
    val file = File(path)
    if (!file.exists()) return null
    return try {
      file.inputStream().use { input ->
        val flag = input.read()
        if (flag < 0) return null
        val base64 = flag != 0
        val bytes = input.readBytes()
        if (base64) {
          Pair(String(bytes, Charsets.UTF_8), true)
        } else {
          Pair(String(bytes, Charsets.UTF_8), false)
        }
      }
    } catch (e: IOException) {
      LogRedirector.w(tag, "readBody failed for $requestId", e)
      null
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
        .put("creator", JSONObject().put("name", "Lumen").put("version", "0.1.2"))
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
          .put("creator", JSONObject().put("name", "Lumen").put("version", "0.1.2"))
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

    val started = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US)
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
    try {
      // One-line summary; full fidelity is the in-memory map + body files for this process.
      val line = JSONObject()
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
        .toString()
      metaFile.appendText(line + "\n")
    } catch (e: IOException) {
      LogRedirector.w(tag, "meta append failed", e)
    }
  }

  private fun pruneBodiesIfNeeded() {
    val files = bodiesDir.listFiles()?.sortedBy { it.lastModified() } ?: return
    var total = files.sumOf { it.length() }
    for (f in files) {
      if (total <= config.networkBodyQuotaBytes) break
      total -= f.length()
      f.delete()
    }
  }

  private fun notify(record: NetworkRecord) {
    for (l in listeners) {
      try {
        l.onRecordUpdated(record)
      } catch (t: Throwable) {
        LogRedirector.w(tag, "listener failed", t)
      }
    }
  }
}
