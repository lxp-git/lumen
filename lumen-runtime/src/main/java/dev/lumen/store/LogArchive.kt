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

  private val linePattern =
    Regex("""^\s*(\d+)\.(\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFS])\s+(.*?)\s*: (.*)$""")
  private val segmentNamePattern = Regex("""spill-(\d+)\.jsonl""")

  private val suppressedTags = setOf(
    "ChromePeerManager",
    "LogArchive",
    "NetworkArchive",
    "ChromeDevtoolsServer",
    "MethodDispatcher",
    "DumpappSocketLikeHandler",
    "LightHttpServer",
    "JsonRpcPeer",
    "WebSocketSession",
  )

  fun addListener(listener: Listener) = listeners.add(listener)
  fun removeListener(listener: Listener) = listeners.remove(listener)

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
    pruneRetention()
    thread(name = "Lumen-LogArchive", isDaemon = true) { pumpForever() }
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
    val match = linePattern.matchEntire(line)
      ?: return LogEntry(lastTimestampMs, "info", line)
    val (secs, fraction, pid, _, priority, logTag, message) = match.destructured
    if (logTag in suppressedTags) return null
    val timestampMs = secs.toDouble() * 1000 + "0.$fraction".toDouble() * 1000
    lastTimestampMs = timestampMs
    val level = when (priority) {
      "W" -> "warning"
      "E", "F" -> "error"
      "I" -> "info"
      else -> "verbose"
    }
    return LogEntry(timestampMs, level, "$logTag($pid): $message")
  }

  private fun encode(entry: LogEntry): String =
    JSONArray().put(entry.timestampMs).put(entry.level).put(entry.text).toString()

  private fun readSegment(file: File): List<LogEntry> {
    val out = ArrayList<LogEntry>()
    try {
      file.forEachLine { line ->
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
    /** Spill promptly so short debug sessions still leave files on disk. */
    private const val FLUSH_EVERY = 50
    private const val SEGMENT_MAX_LINES = 25_000
  }
}
