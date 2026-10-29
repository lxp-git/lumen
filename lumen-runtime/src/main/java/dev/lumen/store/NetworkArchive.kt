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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Network exchanges, kept in memory and under `filesDir/lumen/network/`:
 * - memory: the newest [IN_MEMORY_RECORDS] records of this launch plus unfinished ones, with
 *   request bodies and WebSocket frames;
 * - `session-<launch>.jsonl`: every record of each launch, one line per lifecycle update, headers
 *   included, bodies not ([NetworkRecordJson]);
 * - `bodies/`: response bodies `body-<launch>-<id>` and request bodies `req-<launch>-<id>`.
 *
 * [locate], [replay] and [forEachInSession] read records memory no longer holds back from a
 * session file, one at a time through its [NetworkSessionIndex], so history outlives eviction
 * and restarts without the whole launch ever being in memory. WebSocket frame payloads stay in
 * memory only. Capture is always on; DevTools peers only subscribe.
 */
class NetworkArchive(
  context: Context,
  private val config: LumenConfig,
) {
  private val tag = "NetworkArchive"
  private val root = File(context.applicationContext.filesDir, "lumen/network").also { it.mkdirs() }
  private val bodiesDir = File(root, "bodies").also { it.mkdirs() }
  private val launchKey =
    NetworkSessionFiles.launchKey(android.os.Process.myPid(), System.currentTimeMillis())
  private val metaFile = File(root, NetworkSessionFiles.sessionStem(launchKey) + ".jsonl")

  private val lock = Any()
  /** Disk work kept off the HTTP path (request-body spills, quota passes), in order. */
  private val io = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "lumen-net-io").apply { isDaemon = true }
  }
  private val bodyPrune = BodyPruneScheduler(io) {
    try {
      BodyQuota.prune(quotaFiles(), config.networkBodyQuotaBytes, pinnedBytes = metaFile.length())
    } catch (t: Throwable) {
      LogRedirector.w(tag, "body prune failed", t)
    }
  }
  /** Capture order (a LinkedHashMap keeps first-insertion order); [trimLocked] drops from the front. */
  private val records = LinkedHashMap<String, NetworkRecord>()
  /** Highest request sequence number dropped from memory; -1 while memory holds the whole launch. */
  private var evictedMaxSeq = -1L
  private val meta = JsonlAppender(metaFile, "lumen-net-meta") { persistMemoryAgain() }
  private val currentIndex = NetworkSessionIndex(metaFile)
  /** Indexes of earlier launches' files, which no longer change. */
  private val indexes = object : LinkedHashMap<String, NetworkSessionIndex>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NetworkSessionIndex>?) =
      size > CACHED_INDEXES
  }

  /** This launch's session id, as [listSessions] names it. */
  val currentSessionId: String = metaFile.name.removeSuffix(".jsonl")

  /** A record and the session it was found in. */
  class Located(val record: NetworkRecord, val sessionId: String, val fromDisk: Boolean)

  fun put(record: NetworkRecord) {
    synchronized(lock) {
      records[record.requestId] = record
      trimLocked()
      appendMetaLocked(record)
    }
    record.requestBody?.let { spillRequestBody(record.requestId, it) }
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

  /** Session files, oldest first. [countRequests] indexes each file to count its requests. */
  fun listSessions(countRequests: Boolean = false): List<NetworkSessionInfo> =
    sessionFiles().sortedBy { it.lastModified() }.map { f ->
      val stem = NetworkSessionFiles.parseStem(f.name.removeSuffix(".jsonl"))
      NetworkSessionInfo(
        id = f.name.removeSuffix(".jsonl"),
        pid = stem?.pid,
        startedAtMs = stem?.startMs,
        path = f.absolutePath,
        sizeBytes = f.length(),
        modifiedAtMs = f.lastModified(),
        requestCount = if (countRequests) indexOf(f).size() else null,
        current = f == metaFile,
      )
    }

  fun get(requestId: String): NetworkRecord? = synchronized(lock) { records[requestId] }

  /**
   * The record [requestId] from memory or, once memory dropped it or its launch ended, from
   * disk. [sessionId] limits the search to one session; otherwise the launches of the id's pid
   * are tried newest first (Android reuses pids, so an id can exist in several launches).
   */
  fun locate(requestId: String, sessionId: String? = null): Located? {
    val only = sessionId?.let { sessionFile(it) ?: return null }
    if (only == null || only == metaFile) {
      get(requestId)?.let { return Located(it, currentSessionId, fromDisk = false) }
    }
    // Synthetic rows (`<id>.lumen-ws`) never reach disk.
    val pid = NetworkSessionFiles.requestPid(requestId) ?: return null
    val candidates = if (only != null) {
      listOf(only)
    } else {
      sessionFiles()
        .mapNotNull { f -> NetworkSessionFiles.parseStem(f.name.removeSuffix(".jsonl"))?.let { f to it } }
        .filter { (_, stem) -> stem.pid == pid }
        .sortedByDescending { (f, stem) -> stem.startMs ?: f.lastModified() }
        .map { it.first }
    }
    for (file in candidates) {
      val index = indexOf(file)
      if (index.offsetsOf(requestId) == null) continue
      RecordSource(file, index).use { it.load(requestId) }
        ?.let { return Located(it, file.name.removeSuffix(".jsonl"), fromDisk = true) }
    }
    return null
  }

  /** Whether [sessionId] names this launch or a session file on disk. */
  fun hasSession(sessionId: String): Boolean =
    sessionFile(sessionId)?.let { it == metaFile || it.exists() } == true

  fun find(requestId: String, sessionId: String? = null): NetworkRecord? =
    locate(requestId, sessionId)?.record

  /** In-memory records of this launch, oldest first. */
  fun allRecords(): List<NetworkRecord> = synchronized(lock) { ArrayList(records.values) }

  /**
   * Calls [action] with every record of [sessionId] (a [listSessions] id, `session-` prefix
   * optional; null or `current` for this launch), oldest first. Records memory no longer holds
   * are read from disk one at a time, without WebSocket frames. False for an unknown session.
   */
  fun forEachInSession(sessionId: String?, action: (NetworkRecord) -> Unit): Boolean {
    val file = if (sessionId == null) metaFile else sessionFile(sessionId) ?: return false
    if (file == metaFile) {
      replay(limit = 0, action)
      return true
    }
    if (!file.exists()) return false
    val index = indexOf(file)
    RecordSource(file, index).use { source ->
      for (id in index.ids().sortedBy(::seqOf)) source.load(id)?.let(action)
    }
    return true
  }

  /**
   * Calls [action] with this launch for `Network.enable`, oldest first: records memory dropped
   * come back from the session file, then the ones memory holds. [limit] above 0 keeps only the
   * newest [limit], plus open WebSockets, whose live frames need a row to land on.
   */
  fun replay(limit: Int = config.networkReplayLimit, action: (NetworkRecord) -> Unit) {
    val memory: List<NetworkRecord>
    val evictedUpTo: Long
    synchronized(lock) {
      memory = ArrayList(records.values)
      evictedUpTo = evictedMaxSeq
    }
    val dropped = if (evictedUpTo < 0) {
      emptyList()
    } else {
      val inMemory = memory.mapTo(HashSet()) { it.requestId }
      // Only records dropped before the snapshot: one captured since may already be in the
      // file, and reaches DevTools live.
      // Sorted: after the app wiped the file, a newer record can be the first one rewritten.
      indexOf(metaFile).ids().filter { id -> id !in inMemory && seqOf(id) <= evictedUpTo }
        .sortedBy(::seqOf)
    }
    val skip = if (limit > 0) (dropped.size + memory.size - limit).coerceAtLeast(0) else 0
    // Both lists are in capture order. Merge them by sequence number, so a record kept in
    // memory because it is unfinished comes out where it started, not after the disk ones.
    RecordSource(metaFile, currentIndex).use { disk ->
      var d = 0
      var m = 0
      var position = 0
      while (d < dropped.size || m < memory.size) {
        val fromDisk = m == memory.size ||
          (d < dropped.size && seqOf(dropped[d]) < seqOf(memory[m].requestId))
        if (fromDisk) {
          val id = dropped[d++]
          if (position++ >= skip) disk.load(id)?.let(action)
        } else {
          val record = memory[m++]
          if (position++ >= skip || (record.isWebSocket && record.isUnfinished())) action(record)
        }
      }
    }
  }

  /**
   * Sink for the response body of [requestId], written exactly as the app reads it and
   * capped at [LumenConfig.maxBodyBytes]. [contentEncoding] is the response's
   * `Content-Encoding`; [readBody] reverses it when it can. Null when the file cannot be
   * created: capture is best effort and must never fail the app's request.
   */
  fun openBodySink(requestId: String, contentEncoding: String?): OutputStream? {
    val file = File(bodiesDir, NetworkSessionFiles.responseBodyName(launchKey, requestId))
    val raw = try {
      file.outputStream()
    } catch (_: IOException) {
      // The app may have wiped filesDir (logout, "clear cache"): recreate once.
      try {
        bodiesDir.mkdirs()
        file.outputStream()
      } catch (e: IOException) {
        LogRedirector.w(tag, "cannot spill body for $requestId", e)
        return null
      }
    }
    val encoding = BodyCodec.normalizeEncoding(contentEncoding)
    // Reading the file back from disk needs the encoding, so the session line must carry it.
    update(requestId, persistMeta = encoding != null) {
      it.responseBodyPath = file.absolutePath
      it.responseBodyEncoding = encoding
    }
    return object : FilterOutputStream(raw) {
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
   * The response body of [record] for DevTools: decoded, then UTF-8 text when it looks like text
   * (`second == false`) or base64 otherwise (`second == true`). A body in an encoding Lumen
   * cannot reverse (e.g. brotli) is always base64. Null when nothing was captured or kept.
   */
  fun readBody(record: NetworkRecord): Pair<String, Boolean>? {
    val file = record.responseBodyPath?.let(::File)?.takeIf { it.exists() } ?: return null
    val encoding = record.responseBodyEncoding
    val decodable = encoding == null || BodyCodec.supportedEncoding(encoding) != null
    val bytes = try {
      file.inputStream().use {
        BodyCodec.decode(it, encoding, MAX_DECODED_BODY_FACTOR * config.maxBodyBytes)
      }
    } catch (e: IOException) {
      LogRedirector.w(tag, "readBody failed for ${record.requestId}", e)
      return null
    }
    return if (decodable && BodyCodec.isText(record.mimeType, bytes)) {
      Pair(String(bytes, Charsets.UTF_8), false)
    } else {
      Pair(Base64.encodeToString(bytes, Base64.NO_WRAP), true)
    }
  }

  /** The request body of [record]: in memory while it is live, else its spilled file. */
  fun requestBody(record: NetworkRecord): String? {
    record.requestBody?.let { return it }
    // Most requests have no body: check before paying for a FileNotFoundException.
    val file = record.requestBodyPath?.let(::File)?.takeIf { it.exists() } ?: return null
    return try {
      file.readText(Charsets.UTF_8)
    } catch (_: IOException) {
      null
    }
  }

  /**
   * HAR 1.2 of [sessionId] (default: this whole launch), written entry by entry under the
   * network dir so a long launch never sits in memory at once. Null for an unknown session.
   */
  fun exportHarToFile(includeBodies: Boolean = true, sessionId: String? = null): File? {
    val file = if (sessionId.isNullOrEmpty()) metaFile else sessionFile(sessionId) ?: return null
    val current = file == metaFile
    if (!current && !file.exists()) return null
    val label = if (current) "current" else file.name.removeSuffix(".jsonl")
    // Keep one export: with bodies, a whole launch can be large, and no quota covers exports.
    root.listFiles { f -> f.name.startsWith("export-") && f.name.endsWith(".har") }
      ?.forEach { it.delete() }
    val out = File(root, "export-$label-${System.currentTimeMillis()}.har")
    val creator = JSONObject().put("name", "Lumen").put("version", BuildConfig.LUMEN_VERSION)
    out.bufferedWriter().use { w ->
      w.write("{\"log\":{\"version\":\"1.2\",\"creator\":$creator,")
      if (!current) {
        val comment = "Earlier launch, read back from disk: WebSocket frames are not kept"
        w.write("\"comment\":${JSONObject.quote(comment)},")
      }
      w.write("\"entries\":[\n")
      var first = true
      forEachInSession(file.name.removeSuffix(".jsonl")) { record ->
        if (!first) w.write(",\n")
        first = false
        w.write(toHarEntry(record, includeBodies).toString())
      }
      w.write("\n]}}\n")
    }
    return out
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

  private fun sessionFile(sessionId: String): File? {
    if (sessionId == "current") return metaFile
    val stem = if (sessionId.startsWith("session-")) sessionId else "session-$sessionId"
    NetworkSessionFiles.parseStem(stem) ?: return null
    return File(root, "$stem.jsonl")
  }

  private fun sessionFiles(): List<File> =
    root.listFiles { f ->
      f.name.endsWith(".jsonl") && NetworkSessionFiles.parseStem(f.name.removeSuffix(".jsonl")) != null
    }?.asList() ?: emptyList()

  /** Everything the quota may delete: spilled bodies and earlier launches' session files. */
  private fun quotaFiles(): List<File> {
    val out = ArrayList<File>()
    bodiesDir.listFiles()?.let { out.addAll(it) }
    sessionFiles().filterTo(out) { it != metaFile }
    return out
  }

  /** The index of [file], caught up with what has been appended since it was last used. */
  private fun indexOf(file: File): NetworkSessionIndex {
    if (file == metaFile) {
      currentIndex.refresh(meta.generation)
      return currentIndex
    }
    val index = synchronized(indexes) { indexes.getOrPut(file.path) { NetworkSessionIndex(file) } }
    index.refresh()
    return index
  }

  /** Loads records of one session file by id through its index; opens the file on first use. */
  private inner class RecordSource(
    private val file: File,
    private val index: NetworkSessionIndex,
  ) : java.io.Closeable {
    // Pid-only sessions predate launch keys: their body names may belong to another launch.
    private val launch = NetworkSessionFiles.parseStem(file.name.removeSuffix(".jsonl"))?.launchKey
    private var reader: NetworkSessionIndex.Reader? = null
    private var failed = false

    fun load(id: String): NetworkRecord? {
      if (failed) return null
      val offsets = index.offsetsOf(id) ?: return null
      val merged = try {
        (reader ?: NetworkSessionIndex.Reader(file).also { reader = it }).merged(offsets)
      } catch (e: IOException) {
        LogRedirector.w(tag, "reading ${file.name} failed", e)
        failed = true
        return null
      } ?: return null
      // An offset into a file replaced under the index lands on some other record's line.
      if (merged.optString("id") != id) return null
      val record = NetworkRecordJson.record(merged)
      if (launch != null) {
        record.responseBodyPath = File(bodiesDir, NetworkSessionFiles.responseBodyName(launch, id)).absolutePath
        record.requestBodyPath = File(bodiesDir, NetworkSessionFiles.requestBodyName(launch, id)).absolutePath
      }
      return record
    }

    override fun close() {
      reader?.close()
    }
  }

  private fun spillRequestBody(requestId: String, body: String) {
    val file = File(bodiesDir, NetworkSessionFiles.requestBodyName(launchKey, requestId))
    try {
      io.execute {
        try {
          try {
            file.writeText(body, Charsets.UTF_8)
          } catch (_: IOException) {
            // The app may have wiped filesDir (logout, "clear cache"): recreate once.
            bodiesDir.mkdirs()
            file.writeText(body, Charsets.UTF_8)
          }
        } catch (e: IOException) {
          LogRedirector.w(tag, "cannot spill request body for $requestId", e)
        }
      }
    } catch (_: RejectedExecutionException) {
    }
  }

  private fun toHarEntry(record: NetworkRecord, includeBodies: Boolean): JSONObject {
    val requestBody = requestBody(record)
    val request = JSONObject()
      .put("method", record.method)
      .put("url", record.url)
      .put("httpVersion", "HTTP/1.1")
      .put("headers", headersToHar(record.requestHeaders))
      .put("queryString", JSONArray())
      .put("headersSize", -1)
      .put("bodySize", requestBody?.toByteArray()?.size ?: 0)
    if (requestBody != null) {
      request.put("postData", JSONObject()
        .put("mimeType", record.requestHeaders["Content-Type"] ?: "application/octet-stream")
        .put("text", requestBody))
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
            val body = readBody(record)
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
    // Memory holds the newest IN_MEMORY_RECORDS plus up to MAX_UNFINISHED_KEPT unfinished
    // records on top: their later updates could not be recorded otherwise, and an open
    // WebSocket's frames live only here. What it drops stays readable from the session file.
    if (records.size <= IN_MEMORY_RECORDS) return
    val unfinished = records.values.count { it.isUnfinished() }
    val kept = minOf(unfinished, MAX_UNFINISHED_KEPT)
    var excess = records.size - IN_MEMORY_RECORDS - kept
    if (excess <= 0) return
    // Past the cap the oldest unfinished go first (e.g. responses the app never closes).
    var unfinishedToDrop = unfinished - kept
    val it = records.values.iterator()
    while (excess > 0 && it.hasNext()) {
      val record = it.next()
      if (record.isUnfinished()) {
        if (unfinishedToDrop == 0) continue
        unfinishedToDrop--
      }
      it.remove()
      excess--
      val seq = NetworkSessionFiles.requestSeq(record.requestId) ?: continue
      if (seq > evictedMaxSeq) evictedMaxSeq = seq
    }
  }

  private fun NetworkRecord.isUnfinished() = finishedAtMs == null && failedReason == null

  private fun seqOf(requestId: String) = NetworkSessionFiles.requestSeq(requestId) ?: Long.MAX_VALUE

  /** The app deleted the session file: write what memory still holds into the new one. */
  private fun persistMemoryAgain() {
    synchronized(lock) {
      for (record in records.values) {
        record.persistedRequestHeaders = null
        record.persistedResponseHeaders = null
        appendMetaLocked(record)
      }
    }
  }

  private fun appendMetaLocked(record: NetworkRecord) {
    // Built under the lock (consistent snapshot), written by the appender thread.
    meta.append(NetworkRecordJson.line(record).toString())
  }

  private companion object {
    /** Decoded bodies may exceed the stored (compressed) cap; bound the inflation. */
    const val MAX_DECODED_BODY_FACTOR = 4

    /** Records of this launch kept in memory; older ones are read back from disk. */
    const val IN_MEMORY_RECORDS = 1_000

    /** Unfinished records kept on top of [IN_MEMORY_RECORDS]. */
    const val MAX_UNFINISHED_KEPT = 1_000

    /** Earlier launches whose index stays loaded, e.g. for rows a sidecar-kept window shows. */
    const val CACHED_INDEXES = 4
  }
}
