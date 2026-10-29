package dev.lumen.store

/**
 * One captured HTTP exchange (and optional WebSocket lifecycle), held in the EventStore.
 * Bodies live in sidecar files; this record keeps metadata + paths.
 */
data class NetworkRecord(
  val requestId: String,
  val url: String,
  val method: String,
  var requestHeaders: Map<String, String>,
  val requestBody: String?,
  val startedAtMs: Long,
  val startedAtMonotonicMs: Long,
  var statusCode: Int? = null,
  var statusText: String? = null,
  var responseHeaders: Map<String, String> = emptyMap(),
  var mimeType: String? = null,
  var connectionReused: Boolean = false,
  var fromDiskCache: Boolean = false,
  var finishedAtMs: Long? = null,
  var failedReason: String? = null,
  var encodedDataLength: Long = 0,
  /** Spilled body file, exactly as the app received it (see [responseBodyEncoding]). */
  var responseBodyPath: String? = null,
  /**
   * The response's `Content-Encoding` when the app negotiated compression itself and the
   * body is still encoded (null = identity). gzip / deflate are decoded on read.
   */
  var responseBodyEncoding: String? = null,
  var mocked: Boolean = false,
  var resourceType: String = "Other",
  var isWebSocket: Boolean = false,
  var wsFrameCount: Int = 0,
  /** Non-fatal note (e.g. Fetch pause timed out and the call continued). */
  var note: String? = null,
  /**
   * Spilled request body. Set on records read back from disk; a live record keeps
   * [requestBody] in memory (see [NetworkArchive.requestBody]).
   */
  var requestBodyPath: String? = null,
) {
  /** Archived frames for late-connect replay; capped by [NetworkArchive.archiveWsFrame]. */
  val wsFrames: WsFrameLog = WsFrameLog()

  /** Header maps the session jsonl already holds; see [NetworkRecordJson.line]. */
  internal var persistedRequestHeaders: Map<String, String>? = null
  internal var persistedResponseHeaders: Map<String, String>? = null
}

/** One archived WebSocket frame for late-connect CDP replay. */
data class WsFrame(
  val timestampMs: Long,
  val timestampMonoMs: Long,
  val outgoing: Boolean,
  /** CDP opcode: 1 = text, 2 = binary. */
  val opcode: Int,
  val payload: String,
  val binary: Boolean,
  val truncated: Boolean,
)

data class NetworkSessionInfo(
  val id: String,
  val pid: Int?,
  /** Launch time; null for sessions written before launch keys existed. */
  val startedAtMs: Long?,
  val path: String,
  val sizeBytes: Long,
  val modifiedAtMs: Long,
  /** Distinct requests, counted only when asked for (it reads the whole file). */
  val requestCount: Int?,
  val current: Boolean,
)
