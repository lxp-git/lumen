package dev.lumen.store

import android.content.Context
import dev.lumen.LumenConfig
import dev.lumen.common.LogRedirector
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Always-on logcat archive with day/size retention, segment files, and page-sized
 * replay windows for Chrome Console limits.
 *
 * Evolved from the Stetho LogcatForwarder prototype: capture starts with the agent,
 * not when DevTools attaches.
 */
class LogArchive(
  context: Context,
  private val config: LumenConfig,
) {
  fun interface Listener {
    fun onLogEntry(entry: LogEntry)
    fun onSegmentChanged(segmentId: String?) {}
  }

  private val tag = "LogArchive"
  private val root = File(context.applicationContext.filesDir, "lumen/logs").also { it.mkdirs() }

  private val lock = Any()
  private val pending = ArrayList<LogEntry>()
  private val listeners = CopyOnWriteArrayList<Listener>()

  private var currentSegmentIndex = 0
  private var currentSegmentLines = 0
  @Volatile private var started = false
  @Volatile private var lastTimestampMs = 0.0
  /** Segment id currently selected for DevTools replay (null = latest). */
  @Volatile var activeSegmentId: String? = null

  private val segmentNamePattern = Regex("""spill-(\d+)\.jsonl""")

  fun addListener(listener: Listener) = listeners.add(listener)

  fun ensureStarted() {
    synchronized(this) {
      if (started) return
      started = true
    }
    synchronized(lock) {
      currentSegmentIndex = (listSegmentFilesLocked().lastOrNull()?.let(::segmentIndex) ?: 0) + 1
      currentSegmentLines = 0
      pending.add(
        LogEntry(
          System.currentTimeMillis().toDouble(),
          "info",
          "─────── Lumen logcat session start (pid ${android.os.Process.myPid()}) ───────",
        ),
      )
    }
    thread(name = "Lumen-LogArchive", isDaemon = true) {
      pruneRetention()
      pumpForever()
    }
  }

  fun listSegments(): List<LogSegmentInfo> = synchronized(lock) {
    listSegmentFilesLocked().map { f ->
      val peek = peekSegment(f)
      LogSegmentInfo(
        id = segmentId(f),
        fileName = f.name,
        path = f.absolutePath,
        sizeBytes = f.length(),
        modifiedAtMs = f.lastModified(),
        lineCount = peek.lineCount,
        firstTimestampMs = peek.firstTs,
        lastTimestampMs = peek.lastTs,
      )
    }
  }

  /** Activate a segment for subsequent [pageForReplay] calls. Null = live/latest. */
  fun setActiveSegment(segmentId: String?) {
    activeSegmentId = segmentId
    for (l in listeners) {
      try {
        l.onSegmentChanged(segmentId)
      } catch (t: Throwable) {
        LogRedirector.w(tag, "segment listener failed", t)
      }
    }
  }

  /**
   * Page of entries for Console replay. Uses [activeSegmentId] when set; otherwise the
   * live tail (pending + newest segments) capped to [LumenConfig.logPageSize].
   */
  fun pageForReplay(pageSize: Int = config.logPageSize): List<LogEntry> {
    synchronized(lock) {
      val active = activeSegmentId
      if (active != null) {
        val file = listSegmentFilesLocked().firstOrNull { segmentId(it) == active }
        if (file != null) {
          val entries = readSegment(file)
          return entries.takeLast(pageSize)
        }
      }
      // Live tail.
      val tail = ArrayList(pending)
      val chunks = ArrayList<List<LogEntry>>()
      var count = tail.size
      for (segment in listSegmentFilesLocked().asReversed()) {
        if (count >= pageSize) break
        val chunk = readSegment(segment)
        chunks.add(chunk)
        count += chunk.size
      }
      val merged = chunks.asReversed().flatten() + tail
      return merged.takeLast(pageSize)
    }
  }

  /**
   * Newest [limit] entries matching the filters, oldest first, searched backwards from the
   * live tail across segments (ignores [activeSegmentId]). Stops after [maxScan] lines so a
   * week of logs is never read in one call. Files are read outside the lock; a line torn by a
   * concurrent flush is skipped.
   */
  fun query(
    limit: Int,
    contains: String? = null,
    excludes: String? = null,
    minLevel: String? = null,
    sinceMs: Double? = null,
    maxScan: Int = DEFAULT_MAX_SCAN,
  ): List<LogEntry> {
    val minRank = minLevel?.let(::levelRank) ?: 0
    fun matches(entry: LogEntry): Boolean =
      levelRank(entry.level) >= minRank &&
        (sinceMs == null || entry.timestampMs >= sinceMs) &&
        (contains.isNullOrEmpty() || entry.text.contains(contains, ignoreCase = true)) &&
        (excludes.isNullOrEmpty() || !entry.text.contains(excludes, ignoreCase = true))

    // The newest segment's length is pinned too: a flush during the read appends the
    // snapshotted `pending` lines to it, and they must not be counted twice.
    val tail: List<LogEntry>
    val segments: List<File>
    val newestLength: Long
    synchronized(lock) {
      tail = ArrayList(pending)
      segments = listSegmentFilesLocked()
      newestLength = segments.lastOrNull()?.length() ?: 0L
    }
    val newestFirst = ArrayList<LogEntry>()
    var scanned = 0
    fun scan(entries: List<LogEntry>): Boolean {
      for (i in entries.indices.reversed()) {
        val entry = entries[i]
        if (sinceMs != null && entry.timestampMs < sinceMs) return false
        if (matches(entry)) newestFirst.add(entry)
        if (newestFirst.size >= limit || ++scanned >= maxScan) return false
      }
      return true
    }
    if (scan(tail)) {
      for ((i, segment) in segments.asReversed().withIndex()) {
        val entries = if (i == 0) readSegment(segment, newestLength) else readSegment(segment)
        if (!scan(entries)) break
      }
    }
    return newestFirst.asReversed()
  }

  fun clearAll() {
    synchronized(lock) {
      pending.clear()
      listSegmentFilesLocked().forEach { it.delete() }
      currentSegmentIndex++
      currentSegmentLines = 0
      activeSegmentId = null
    }
  }

  fun exportBundle(days: Int = config.retentionDays): File {
    val cutoff = System.currentTimeMillis() - days * 86_400_000L
    val out = File(root, "export-${dayStamp()}.jsonl")
    out.outputStream().bufferedWriter().use { writer ->
      synchronized(lock) {
        for (segment in listSegmentFilesLocked()) {
          if (segment.lastModified() < cutoff) continue
          segment.forEachLine { writer.appendLine(it) }
        }
        for (entry in pending) {
          writer.appendLine(encode(entry))
        }
      }
    }
    return out
  }

  fun pruneRetention() {
    val cutoff = System.currentTimeMillis() - config.retentionDays * 86_400_000L
    synchronized(lock) {
      for (segment in listSegmentFilesLocked()) {
        if (segment.lastModified() < cutoff && segmentIndex(segment) != currentSegmentIndex) {
          segment.delete()
        }
      }
      // Export bundles are one-shot artifacts; don't let them pile up forever.
      root.listFiles { f -> f.name.startsWith("export-") && f.name.endsWith(".jsonl") }
        ?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
  }

  private fun pumpForever() {
    while (true) {
      try {
        val process = ProcessBuilder("logcat", "-v", "threadtime", "-v", "epoch")
          .redirectErrorStream(true)
          .start()
        try {
          process.inputStream.bufferedReader().forEachLine { handleLine(it) }
        } finally {
          process.destroy()
        }
      } catch (e: IOException) {
        LogRedirector.e(tag, "logcat pipe failed", e)
      }
      try {
        Thread.sleep(3_000L)
      } catch (_: InterruptedException) {
        return
      }
    }
  }

  private fun handleLine(line: String) {
    if (line.isEmpty() || line.startsWith("---------")) return
    val entry = parse(line) ?: return
    synchronized(lock) {
      pending.add(entry)
      if (pending.size >= FLUSH_EVERY) flushLocked()
    }
    for (l in listeners) {
      try {
        l.onLogEntry(entry)
      } catch (t: Throwable) {
        LogRedirector.w(tag, "listener failed", t)
      }
    }
  }

  private fun flushLocked() {
    root.mkdirs() // the app may have wiped filesDir
    val file = segmentFile(currentSegmentIndex) ?: return
    try {
      file.appendText(buildString {
        for (entry in pending) {
          append(encode(entry))
          append('\n')
        }
      })
      currentSegmentLines += pending.size
      pending.clear()
      if (currentSegmentLines >= SEGMENT_MAX_LINES) {
        currentSegmentIndex++
        currentSegmentLines = 0
      }
    } catch (e: IOException) {
      LogRedirector.e(tag, "spill write failed, dropping ${pending.size} lines", e)
      pending.clear()
    }
  }

  private fun parse(line: String): LogEntry? {
    val entry = LogCatLine.parse(line, lastTimestampMs) ?: return null
    lastTimestampMs = entry.timestampMs
    return entry
  }

  private fun encode(entry: LogEntry): String =
    JSONArray().put(entry.timestampMs).put(entry.level).put(entry.text).toString()

  /** Entries of [file], reading only its first [maxBytes] when given. */
  private fun readSegment(file: File, maxBytes: Long = Long.MAX_VALUE): List<LogEntry> {
    val out = ArrayList<LogEntry>()
    try {
      val reader = if (maxBytes == Long.MAX_VALUE) {
        file.bufferedReader()
      } else {
        BoundedInputStream(file.inputStream(), maxBytes).bufferedReader()
      }
      reader.forEachLine { line ->
        try {
          val arr = JSONArray(line)
          out.add(LogEntry(arr.getDouble(0), arr.getString(1), arr.getString(2)))
        } catch (_: JSONException) {
          // torn tail
        }
      }
    } catch (e: IOException) {
      LogRedirector.e(tag, "spill read failed", e)
    }
    return out
  }

  private data class SegmentPeek(val lineCount: Int, val firstTs: Long, val lastTs: Long)

  /**
   * Cache keyed by file name + (length, mtime): [listSegments] runs on the notification
   * refresh path (every activity resume) and must not re-read the whole archive each time.
   */
  /** Guarded by [lock] (only touched from [listSegments]). */
  private val peekCache = HashMap<String, Pair<Long, SegmentPeek>>()

  private fun peekSegment(file: File): SegmentPeek {
    val stamp = file.length() * 31 + file.lastModified()
    peekCache[file.name]?.let { (cachedStamp, peek) ->
      if (cachedStamp == stamp) return peek
    }
    var count = 0
    var first = 0L
    var last = 0L
    try {
      file.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        count++
        try {
          val ts = JSONArray(line).optDouble(0, 0.0).toLong()
          if (first == 0L) first = ts
          last = ts
        } catch (_: JSONException) {
        }
      }
    } catch (_: IOException) {
    }
    val peek = SegmentPeek(count, first, last)
    peekCache[file.name] = stamp to peek
    return peek
  }

  private fun segmentFile(index: Int): File =
    File(root, "spill-%06d.jsonl".format(Locale.ROOT, index))

  private fun segmentIndex(file: File): Int =
    segmentNamePattern.matchEntire(file.name)?.groupValues?.get(1)?.toInt() ?: 0

  private fun segmentId(file: File): String = "seg-${segmentIndex(file)}"

  private fun listSegmentFilesLocked(): List<File> =
    (root.listFiles { f -> segmentNamePattern.matches(f.name) } ?: emptyArray())
      .sortedBy(::segmentIndex)

  private fun dayStamp(): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

  companion object {
    private const val DEFAULT_MAX_SCAN = 200_000

    /** CDP Log levels in increasing severity; unknown levels rank as verbose. */
    fun levelRank(level: String): Int = when (level.lowercase()) {
      "info" -> 1
      "warn", "warning" -> 2
      "error" -> 3
      else -> 0
    }

    /** Spill promptly so short debug sessions still leave files on disk. */
    private const val FLUSH_EVERY = 50
    private const val SEGMENT_MAX_LINES = 25_000
  }

  /** Stops after [limit] bytes so a file appended to mid-read yields a stable prefix. */
  private class BoundedInputStream(
    input: java.io.InputStream,
    private var remaining: Long,
  ) : java.io.FilterInputStream(input) {
    override fun read(): Int {
      if (remaining <= 0) return -1
      val b = super.read()
      if (b >= 0) remaining--
      return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (remaining <= 0) return -1
      val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
      if (n > 0) remaining -= n
      return n
    }
  }
}
