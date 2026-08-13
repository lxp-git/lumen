package dev.lumen.store

data class LogEntry(
  val timestampMs: Double,
  /** CDP Log level: verbose | info | warning | error */
  val level: String,
  val text: String,
)

data class LogSegmentInfo(
  val id: String,
  val fileName: String,
  val path: String,
  val sizeBytes: Long,
  val modifiedAtMs: Long,
  val lineCount: Int = 0,
  val firstTimestampMs: Long = 0,
  val lastTimestampMs: Long = 0,
)
