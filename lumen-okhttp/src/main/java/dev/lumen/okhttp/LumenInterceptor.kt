package dev.lumen.okhttp

import android.os.SystemClock
import dev.lumen.LumenAgent
import dev.lumen.inspector.network.EngineIoPolling
import dev.lumen.inspector.network.NetworkEventReporter
import dev.lumen.inspector.network.NetworkEventReporterImpl
import dev.lumen.mock.MockEngine
import dev.lumen.store.EventStore
import dev.lumen.store.NetworkRecord
import okhttp3.Connection
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Always-on OkHttp **application** interceptor: records every exchange into EventStore,
 * bridges CDP Fetch pauses into real call suspension, and applies local asset mock rules.
 *
 * Must be an application interceptor (not network): mock/fulfill short-circuits without
 * calling [Interceptor.Chain.proceed], which OkHttp forbids on network interceptors.
 *
 * The Gradle plugin injects this via [OkHttpClient.Builder.addInterceptor].
 */
class LumenInterceptor : Interceptor {
  private val eventReporter: NetworkEventReporter = NetworkEventReporterImpl.get()

  @Throws(IOException::class)
  override fun intercept(chain: Interceptor.Chain): Response {
    val requestId = eventReporter.nextRequestId()
    var request = chain.request()

    val headers = headersToMap(request.headers)
    val url = request.url.toString()
    val isPollingUrl = EngineIoPolling.isPollingUrl(url)
    val postData = peekRequestBody(
      request,
      if (isPollingUrl) MAX_POLLING_PEEK_BODY_BYTES else MAX_PEEK_BODY_BYTES,
    )

    val store = if (LumenAgent.isStarted()) LumenAgent.store else null
    val mockEngine = if (LumenAgent.isStarted()) LumenAgent.mockEngine else null
    val startedAt = System.currentTimeMillis()
    val startedMono = SystemClock.elapsedRealtime()
    val scheme = request.url.scheme
    val isWebSocketUpgrade =
      request.header("Upgrade")?.equals("websocket", ignoreCase = true) == true ||
        scheme.equals("ws", ignoreCase = true) ||
        scheme.equals("wss", ignoreCase = true)
    val isPolling = !isWebSocketUpgrade && isPollingUrl

    if (isWebSocketUpgrade) {
      // Correlate the HTTP upgrade row with LumenWebSocketListener frame events.
      LumenWebSocketListener.rememberUpgrade(request.url.toString(), requestId)
    }
    if (isPolling) {
      EngineIoPolling.captureOutgoing(url, postData)
    }

    store?.network?.put(
      NetworkRecord(
        requestId = requestId,
        url = request.url.toString(),
        method = request.method,
        requestHeaders = headers,
        requestBody = postData,
        startedAtMs = startedAt,
        startedAtMonotonicMs = startedMono,
        resourceType = if (isWebSocketUpgrade) "WebSocket" else "Other",
        isWebSocket = isWebSocketUpgrade,
      ),
    )

    // Do not emit HTTP Network.* for the upgrade: Chrome then owns two
    // NetworkRequest objects with the same id, and Messages binds to the HTTP
    // one (a single mutating row). Frames belong on webSocketCreated only.
    if (eventReporter.isEnabled && !isWebSocketUpgrade) {
      eventReporter.requestWillBeSent(OkHttpInspectorRequest(requestId, request, postData))
    }

    mockEngine?.matchLocalRule(request.url.toString(), request.method)?.let { rule ->
      if (rule.delayMs > 0) {
        try {
          Thread.sleep(rule.delayMs)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
        }
      }
      val bodyBytes = rule.bodyBytes()
      finishMocked(store, requestId, rule.status, rule.headers, bodyBytes)
      val media = (rule.headers["Content-Type"] ?: "application/json").toMediaTypeOrNull()
      val response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(rule.status)
        .message(reasonPhrase(rule.status))
        .body(bodyBytes.toResponseBody(media))
        .apply { rule.headers.forEach { (k, v) -> header(k, v) } }
        .build()
      pushSyntheticResponse(requestId, request, response, bodyBytes)
      return watchPollingBody(isPolling, request.url.toString(), response)
    }

    if (mockEngine != null &&
      !isWebSocketUpgrade &&
      mockEngine.shouldPauseForFetch(request.url.toString(), "Request")
    ) {
      when (
        val decision = mockEngine.pause(
          networkId = requestId,
          url = request.url.toString(),
          method = request.method,
          headers = headers,
          postData = postData,
          requestStage = "Request",
          resourceType = "XHR",
        )
      ) {
        is MockEngine.Decision.Fulfill -> {
          val headerMap = decision.responseHeaders.toMap()
          finishMocked(store, requestId, decision.responseCode, headerMap, decision.body)
          val media = (headerMap["Content-Type"] ?: "application/octet-stream").toMediaTypeOrNull()
          val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(decision.responseCode)
            .message(reasonPhrase(decision.responseCode))
            .body(decision.body.toResponseBody(media))
            .apply { decision.responseHeaders.forEach { (k, v) -> addHeader(k, v) } }
            .build()
          pushSyntheticResponse(requestId, request, response, decision.body)
          return watchPollingBody(isPolling, request.url.toString(), response)
        }
        is MockEngine.Decision.Fail -> {
          store?.network?.update(requestId) {
            it.failedReason = decision.errorReason
            it.finishedAtMs = System.currentTimeMillis()
          }
          if (eventReporter.isEnabled) {
            eventReporter.httpExchangeFailed(requestId, decision.errorReason)
          }
          throw IOException("Lumen Fetch failed: ${decision.errorReason}")
        }
        is MockEngine.Decision.TimedOut -> {
          store?.network?.update(requestId) {
            it.note = "Fetch pause timed out after ${decision.waitedMs}ms — continued"
          }
        }
        is MockEngine.Decision.Continue -> {
          val builder = request.newBuilder()
          decision.url?.let { builder.url(it) }
          decision.method?.let { method -> builder.method(method, request.body) }
          decision.headers?.forEach { (k, v) -> builder.header(k, v) }
          request = builder.build()
        }
      }
    }

    var response: Response
    try {
      response = chain.proceed(request)
    } catch (e: IOException) {
      dev.lumen.common.LogRedirector.w(
        "LumenFetch",
        "chain.proceed failed ${e.javaClass.simpleName}: ${e.message} $requestId ${request.url}",
      )
      store?.network?.update(requestId) {
        it.failedReason = e.toString()
        it.finishedAtMs = System.currentTimeMillis()
      }
      if (eventReporter.isEnabled) {
        eventReporter.httpExchangeFailed(requestId, e.toString())
      }
      throw e
    }

    if (isWebSocketUpgrade) {
      // 101 is a handshake, not a finished HTTP exchange. Chrome hides the
      // Messages tab (and drops later webSocketFrame* events) if we emit
      // Network.loadingFinished here.
      val wireRequest = response.networkResponse?.request ?: response.request
      store?.network?.update(requestId) {
        it.statusCode = response.code
        it.statusText = response.message
        it.responseHeaders = headersToMap(response.headers)
        if (wireRequest.headers.size > 0) {
          it.requestHeaders = headersToMap(wireRequest.headers)
        }
        it.resourceType = "WebSocket"
        it.isWebSocket = true
      }
      return response
    }

    if (mockEngine != null &&
      !isWebSocketUpgrade &&
      mockEngine.shouldPauseForFetch(request.url.toString(), "Response")
    ) {
      val bodyBytes = try {
        response.body?.bytes() ?: ByteArray(0)
      } catch (e: IOException) {
        throw e
      }
      val responseHeaders = headersToPairs(response.headers)
      when (
        val decision = mockEngine.pause(
          networkId = requestId,
          url = request.url.toString(),
          method = request.method,
          headers = headers,
          postData = postData,
          requestStage = "Response",
          responseStatusCode = response.code,
          responseStatusText = response.message,
          responseHeaders = responseHeaders,
          responseBody = bodyBytes,
        )
      ) {
        is MockEngine.Decision.Fulfill -> {
          val headerMap = decision.responseHeaders.toMap()
          finishMocked(store, requestId, decision.responseCode, headerMap, decision.body)
          val media = (headerMap["Content-Type"]
            ?: response.body?.contentType()?.toString()
            ?: "application/octet-stream").toMediaTypeOrNull()
          val overridden = Response.Builder()
            .request(request)
            .protocol(response.protocol)
            .code(decision.responseCode)
            .message(
              if (decision.responseCode == response.code) {
                response.message.ifEmpty { reasonPhrase(response.code) }
              } else {
                reasonPhrase(decision.responseCode)
              },
            )
            .body(decision.body.toResponseBody(media))
            .apply { decision.responseHeaders.forEach { (k, v) -> addHeader(k, v) } }
            .build()
          pushSyntheticResponse(requestId, request, overridden, decision.body)
          return watchPollingBody(isPolling, request.url.toString(), overridden)
        }
        is MockEngine.Decision.Fail -> {
          store?.network?.update(requestId) {
            it.failedReason = decision.errorReason
            it.finishedAtMs = System.currentTimeMillis()
          }
          if (eventReporter.isEnabled) {
            eventReporter.httpExchangeFailed(requestId, decision.errorReason)
          }
          throw IOException("Lumen Fetch failed: ${decision.errorReason}")
        }
        is MockEngine.Decision.Continue -> {
          val rebuilt = response.newBuilder()
            .code(decision.responseCode ?: response.code)
            .body(bodyBytes.toResponseBody(response.body?.contentType()))
          val overrideHeaders = decision.responseHeaders
          if (overrideHeaders != null) {
            rebuilt.headers(Headers.Builder().apply {
              overrideHeaders.forEach { (k, v) -> add(k, v) }
            }.build())
          }
          response = rebuilt.build()
        }
        is MockEngine.Decision.TimedOut -> {
          store?.network?.update(requestId) {
            it.note = "Fetch pause timed out after ${decision.waitedMs}ms — continued"
          }
          response = response.newBuilder()
            .body(bodyBytes.toResponseBody(response.body?.contentType()))
            .build()
        }
      }
    }

    val connection = chain.connection()
    val declaredLength = response.body?.contentLength() ?: -1L
    // Application interceptors see the app Request (often header-less). The
    // request that actually went on the wire lives on networkResponse.
    val wireRequest = response.networkResponse?.request
      ?: response.cacheResponse?.request
      ?: response.request
    val finalRequestHeaders = headersToMap(wireRequest.headers)
    store?.network?.update(requestId) {
      it.statusCode = response.code
      it.statusText = response.message
      it.responseHeaders = headersToMap(response.headers)
      if (finalRequestHeaders.isNotEmpty()) {
        it.requestHeaders = finalRequestHeaders
      }
      it.mimeType = response.body?.contentType()?.toString()
      it.fromDiskCache = response.cacheResponse != null
      if (declaredLength >= 0) {
        it.encodedDataLength = declaredLength
      }
    }

    if (eventReporter.isEnabled) {
      eventReporter.responseHeadersReceived(
        OkHttpInspectorResponse(requestId, request, response, connection),
      )
    }

    val body = response.body ?: run {
      store?.network?.update(requestId) { it.finishedAtMs = System.currentTimeMillis() }
      reportFinished(requestId, 0L)
      return response
    }
    val sink = store?.network?.openBodySink(requestId, response.header("Content-Encoding"))
    val captured = CaptureInputStream(body.byteStream(), sink) { read, error ->
      store?.network?.update(requestId) {
        it.finishedAtMs = System.currentTimeMillis()
        if (read > 0) it.encodedDataLength = read
        if (error != null) it.failedReason = error.toString()
      }
      if (error != null) {
        if (eventReporter.isEnabled) eventReporter.responseReadFailed(requestId, error.toString())
      } else {
        reportFinished(requestId, read)
      }
    }
    val appStream = if (isPolling) {
      EngineIoPolling.wrapIncoming(request.url.toString(), captured)
    } else {
      captured
    }
    return response.newBuilder()
      .body(ForwardingResponseBody(body, appStream))
      .build()
  }

  private fun reportFinished(requestId: String, bytes: Long) {
    if (!eventReporter.isEnabled) return
    val size = bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    eventReporter.dataReceived(requestId, size, size)
    eventReporter.responseReadFinished(requestId)
  }

  private fun watchPollingBody(
    isPolling: Boolean,
    url: String,
    response: Response,
  ): Response {
    if (!isPolling) return response
    val body = response.body ?: return response
    val watched = EngineIoPolling.wrapIncoming(url, body.byteStream())
    return response.newBuilder()
      .body(ForwardingResponseBody(body, watched))
      .build()
  }

  private fun finishMocked(
    store: EventStore?,
    requestId: String,
    status: Int,
    headers: Map<String, String>,
    body: ByteArray,
  ) {
    store?.network?.update(requestId) {
      it.statusCode = status
      it.statusText = reasonPhrase(status)
      it.responseHeaders = headers
      it.mimeType = headers["Content-Type"] ?: "application/octet-stream"
      it.finishedAtMs = System.currentTimeMillis()
      it.encodedDataLength = body.size.toLong()
      it.mocked = true
    }
    store?.network?.openBodySink(requestId, contentEncoding = null)?.use { it.write(body) }
  }

  private fun pushSyntheticResponse(
    requestId: String,
    request: Request,
    response: Response,
    bodyBytes: ByteArray,
  ) {
    if (!eventReporter.isEnabled) return
    eventReporter.responseHeadersReceived(
      OkHttpInspectorResponse(requestId, request, response, null),
    )
    reportFinished(requestId, bodyBytes.size.toLong())
  }

  private fun headersToMap(headers: Headers): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    for (i in 0 until headers.size) {
      map[headers.name(i)] = headers.value(i)
    }
    return map
  }

  private fun headersToPairs(headers: Headers): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>(headers.size)
    for (i in 0 until headers.size) {
      out.add(headers.name(i) to headers.value(i))
    }
    return out
  }

  private fun peekRequestBody(request: Request, maxBytes: Long = MAX_PEEK_BODY_BYTES): String? {
    val body = request.body ?: return null
    // One-shot/duplex bodies may only be consumed once — peeking here would
    // corrupt the real send. (Reflection-free probe; the methods are missing on
    // very old OkHttp versions, hence the catch.)
    val streaming = try {
      body.isDuplex() || body.isOneShot()
    } catch (_: Throwable) {
      false
    }
    if (streaming) return null
    // Unknown or huge Content-Length: don't materialize the whole body in memory.
    val contentLength = try {
      body.contentLength()
    } catch (_: IOException) {
      return null
    }
    if (contentLength < 0 || contentLength > maxBytes) return null
    return try {
      val buffer = Buffer()
      body.writeTo(buffer)
      // Apps that gzip their own uploads: show the payload, not the compressed bytes.
      val encoding = request.header("Content-Encoding")?.trim()?.lowercase()
      val source = when (encoding) {
        "gzip" -> GZIPInputStream(buffer.inputStream())
        "deflate" -> InflaterInputStream(buffer.inputStream())
        else -> return buffer.readString(minOf(buffer.size, maxBytes), Charsets.UTF_8)
      }
      source.use { String(it.readBytesCapped(maxBytes), Charsets.UTF_8) }
    } catch (_: Exception) {
      null
    }
  }

  private fun InputStream.readBytesCapped(max: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    while (out.size() < max) {
      val n = read(chunk, 0, minOf(chunk.size.toLong(), max - out.size()).toInt())
      if (n < 0) break
      out.write(chunk, 0, n)
    }
    return out.toByteArray()
  }

  /** Mocked and overridden responses get the standard phrase for their code, not "OK". */
  private fun reasonPhrase(code: Int): String = when (code) {
    200 -> "OK"
    201 -> "Created"
    202 -> "Accepted"
    204 -> "No Content"
    301 -> "Moved Permanently"
    302 -> "Found"
    304 -> "Not Modified"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    409 -> "Conflict"
    418 -> "I'm a teapot"
    422 -> "Unprocessable Entity"
    429 -> "Too Many Requests"
    500 -> "Internal Server Error"
    502 -> "Bad Gateway"
    503 -> "Service Unavailable"
    504 -> "Gateway Timeout"
    else -> ""
  }

  private companion object {
    const val MAX_PEEK_BODY_BYTES = 64_000L
    /** Match EngineIoPolling.wrapIncoming so batched polling POSTs are not dropped. */
    const val MAX_POLLING_PEEK_BODY_BYTES = 512L * 1024L
  }

  private class OkHttpInspectorRequest(
    private val requestId: String,
    private val request: Request,
    private val postData: String?,
  ) : NetworkEventReporter.InspectorRequest {
    override fun id(): String = requestId
    override fun friendlyName(): String = ""
    override fun friendlyNameExtra(): Int? = null
    override fun url(): String = request.url.toString()
    override fun method(): String = request.method

    /** The body already peeked for the archive; never re-serialize a one-shot body. */
    override fun body(): ByteArray? = postData?.toByteArray()

    override fun headerCount(): Int = request.headers.size
    override fun headerName(index: Int): String = request.headers.name(index)
    override fun headerValue(index: Int): String = request.headers.value(index)
    override fun firstHeaderValue(name: String): String? = request.header(name)
  }

  private class OkHttpInspectorResponse(
    private val requestId: String,
    private val request: Request,
    private val response: Response,
    private val connection: Connection?,
  ) : NetworkEventReporter.InspectorResponse {
    override fun requestId(): String = requestId
    override fun url(): String = request.url.toString()
    override fun statusCode(): Int = response.code
    override fun reasonPhrase(): String = response.message
    override fun connectionReused(): Boolean = false
    override fun connectionId(): Int = connection?.hashCode() ?: 0
    override fun fromDiskCache(): Boolean = response.cacheResponse != null
    override fun headerCount(): Int = response.headers.size
    override fun headerName(index: Int): String = response.headers.name(index)
    override fun headerValue(index: Int): String = response.headers.value(index)
    override fun firstHeaderValue(name: String): String? = response.header(name)
  }

  private class ForwardingResponseBody(
    private val body: ResponseBody?,
    interceptedStream: InputStream,
  ) : ResponseBody() {
    private val interceptedSource: BufferedSource = interceptedStream.source().buffer()
    override fun contentType(): MediaType? = body?.contentType()
    override fun contentLength(): Long = body?.contentLength() ?: -1
    override fun source(): BufferedSource = interceptedSource
  }

  /**
   * Hands the body to the app while copying it into [sink]. [onDone] runs once, with the
   * byte count, on EOF, on a read error, or when the app closes early (no draining: an app
   * that stops reading should not cause extra network reads).
   */
  private class CaptureInputStream(
    private val upstream: InputStream,
    private val sink: OutputStream?,
    private val onDone: (read: Long, error: IOException?) -> Unit,
  ) : InputStream() {
    private var read = 0L
    private var sinkOpen = sink != null
    private var done = false

    override fun read(): Int {
      val b = guarded { upstream.read() }
      if (b == -1) {
        finish(null)
      } else {
        read++
        copy { it.write(b) }
      }
      return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      val n = guarded { upstream.read(b, off, len) }
      if (n == -1) {
        finish(null)
      } else if (n > 0) {
        read += n
        copy { it.write(b, off, n) }
      }
      return n
    }

    override fun available(): Int = upstream.available()

    override fun close() {
      try {
        upstream.close()
      } finally {
        finish(null)
      }
    }

    private inline fun guarded(block: () -> Int): Int = try {
      block()
    } catch (e: IOException) {
      finish(e)
      throw e
    }

    private inline fun copy(block: (OutputStream) -> Unit) {
      if (!sinkOpen) return
      try {
        block(sink!!)
      } catch (_: IOException) {
        closeSink()
      }
    }

    private fun closeSink() {
      if (!sinkOpen) return
      sinkOpen = false
      try {
        sink?.close()
      } catch (_: IOException) {
      }
    }

    private fun finish(error: IOException?) {
      if (done) return
      done = true
      closeSink()
      onDone(read, error)
    }
  }
}
