package dev.lumen.store

/**
 * Pure logcat line parser used by [LogArchive]. Kept free of Android so JVM
 * unit tests can cover priority mapping and Lumen-tag suppression.
 */
internal object LogCatLine {
  /**
   * `logcat -v threadtime -v epoch`:
   * `1755502036.330  5194  6109 D FGAppsFlyer: Conversion data success: …`
   */
  private val linePattern =
    Regex("""^\s*(\d+)\.(\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFS])\s+(.*?)\s*: (.*)$""")

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
    "CLog",
    "lumen",
    "stetho",
    "MockEngine",
    "NetworkEventReporter",
  )

  /**
   * Chrome's Console "Default levels" hide CDP `verbose`. Promote it to `info`
   * on the wire so `Log.d` / `Log.v` are visible and searchable.
   */
  fun chromeLevel(level: String): String = if (level == "verbose") "info" else level

  fun isSuppressedTag(tag: String): Boolean =
    tag in suppressedTags || tag.startsWith("Lumen")

  fun parse(line: String, lastTimestampMs: Double): LogEntry? {
    if (line.isEmpty() || line.startsWith("---------")) return null
    val match = linePattern.matchEntire(line)
      ?: return LogEntry(lastTimestampMs, "info", line)
    val (secs, fraction, pid, _, priority, logTag, message) = match.destructured
    if (isSuppressedTag(logTag)) return null
    val timestampMs = secs.toDouble() * 1000 + "0.$fraction".toDouble() * 1000
    return LogEntry(timestampMs, cdpLevel(priority), "$logTag($pid): $message")
  }

  private fun cdpLevel(priority: String): String = when (priority) {
    "W" -> "warning"
    "E", "F" -> "error"
    "I" -> "info"
    else -> "verbose"
  }
}
