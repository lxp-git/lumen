package dev.lumen.okhttp

import android.os.SystemClock
import dev.lumen.LumenAgent
import dev.lumen.inspector.network.DefaultResponseHandler
import dev.lumen.inspector.network.NetworkEventReporter
import dev.lumen.inspector.network.NetworkEventReporterImpl
import dev.lumen.inspector.network.RequestBodyHelper
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
import okio.sink
import okio.source
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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
    val postData = peekRequestBody(request)

    val store = if (LumenAgent.isStarted()) LumenAgent.store else null
    val mockEngine = if (LumenAgent.isStarted()) LumenAgent.mockEngine else null
    val startedAt = System.currentTimeMillis()
    val startedMono = SystemClock.elapsedRealtime()
    val scheme = request.url.scheme
    val isWebSocketUpgrade =
      request.header("Upgrade")?.equals("websocket", ignoreCase = true) == true ||
        scheme.equals("ws", ignoreCase = true) ||
        scheme.equals("wss", ignoreCase = true)

    if (isWebSocketUpgrade) {
      // Correlate the HTTP upgrade row with LumenWebSocketListener frame events.
      LumenWebSocketListener.rememberUpgrade(request.url.toString(), requestId)
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

    val requestBodyHelper = RequestBodyHelper(eventReporter, requestId)
    // Do not emit HTTP Network.* for the upgrade: Chrome then owns two
    // NetworkRequest objects with the same id, and Messages binds to the HTTP
    // one (a single mutating row). Frames belong on webSocketCreated only.
    if (eventReporter.isEnabled && !isWebSocketUpgrade) {
      eventReporter.requestWillBeSent(
        OkHttpInspectorRequest(requestId, request, requestBodyHelper),
      )
    }

    mockEngine?.matchLocalRule(request.url.toString(), request.method)?.let { rule ->
      if (rule.delayMs > 0) {
        try {
          Thread.sleep(rule.delayMs)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
        }
      }
      val bodyBytes = rule.body.toByteArray()
      finishMocked(store, requestId, rule.status, rule.headers, bodyBytes)
      val media = (rule.headers["Content-Type"] ?: "application/json").toMediaTypeOrNull()
      val response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(rule.status)
        .message("OK")
        .body(bodyBytes.toResponseBody(media))
        .apply { rule.headers.forEach { (k, v) -> header(k, v) } }
        .build()
      pushSyntheticResponse(requestId, request, response, bodyBytes)
      return response
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
            .message("OK")
            .body(decision.body.toResponseBody(media))
            .apply { decision.responseHeaders.forEach { (k, v) -> addHeader(k, v) } }
            .build()
          pushSyntheticResponse(requestId, request, response, decision.body)
          return response
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
      android.util.Log.w(
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

    if (requestBodyHelper.hasBody()) {
      requestBodyHelper.reportDataSent()
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
            .message(response.message.ifEmpty { "OK" })
            .body(decision.body.toResponseBody(media))
            .apply { decision.responseHeaders.forEach { (k, v) -> addHeader(k, v) } }
            .build()
          pushSyntheticResponse(requestId, request, overridden, decision.body)
          return overridden
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

    val body = response.body
    var contentType: MediaType? = null
    var responseStream: InputStream? = null
    if (body != null) {
      contentType = body.contentType()
      responseStream = body.byteStream()
    }

    if (responseStream != null && store != null) {
      val sink = store.network.openBodySink(requestId, base64Encoded = false)
      responseStream = TeeInputStream(responseStream, sink) { written ->
        store.network.update(requestId) {
          it.finishedAtMs = System.currentTimeMillis()
          if (written > 0) {
            it.encodedDataLength = written
          }
        }
      }
    }

    responseStream = eventReporter.interpretResponseStream(
      requestId,
      contentType?.toString(),
      response.header("Content-Encoding"),
      responseStream,
      DefaultResponseHandler(eventReporter, requestId),
    )

    if (responseStream != null) {
      return response.newBuilder()
        .body(ForwardingResponseBody(body, responseStream))
        .build()
    }

    store?.network?.update(requestId) {
      it.finishedAtMs = System.currentTimeMillis()
    }
    return response
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
      it.statusText = "OK"
      it.responseHeaders = headers
      it.mimeType = headers["Content-Type"] ?: "application/octet-stream"
      it.finishedAtMs = System.currentTimeMillis()
      it.encodedDataLength = body.size.toLong()
      it.mocked = true
    }
    store?.network?.openBodySink(requestId, false)?.use { it.write(body) }
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
    // Use a copy so we never consume the Response body returned to the app.
    val stream = eventReporter.interpretResponseStream(
      requestId,
      response.body?.contentType()?.toString(),
      null,
      ByteArrayInputStream(bodyBytes),
      DefaultResponseHandler(eventReporter, requestId),
    )
    stream?.readBytes()
    stream?.close()
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

  private fun peekRequestBody(request: Request): String? {
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
    if (contentLength < 0 || contentLength > MAX_PEEK_BODY_BYTES) return null
    return try {
      val buffer = Buffer()
      body.writeTo(buffer)
      buffer.readString(minOf(buffer.size, MAX_PEEK_BODY_BYTES), Charsets.UTF_8)
    } catch (_: Exception) {
      null
    }
  }

  private companion object {
    const val MAX_PEEK_BODY_BYTES = 64_000L
  }

  private class OkHttpInspectorRequest(
    private val requestId: String,
    private val request: Request,
    private val requestBodyHelper: RequestBodyHelper,
  ) : NetworkEventReporter.InspectorRequest {
    override fun id(): String = requestId
    override fun friendlyName(): String = ""
    override fun friendlyNameExtra(): Int? = null
    override fun url(): String = request.url.toString()
    override fun method(): String = request.method

    @Throws(IOException::class)
    override fun body(): ByteArray? {
      val body = request.body ?: return null
      val out: OutputStream =
        requestBodyHelper.createBodySink(firstHeaderValue("Content-Encoding"))
      val bufferedSink = out.sink().buffer()
      try {
        body.writeTo(bufferedSink)
      } finally {
        bufferedSink.close()
      }
      return requestBodyHelper.displayBody
    }

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

  private class TeeInputStream(
    private val upstream: InputStream,
    private val side: OutputStream,
    private val onClose: (written: Long) -> Unit,
  ) : InputStream() {
    private var copied = 0L
    override fun read(): Int {
      val b = upstream.read()
      if (b != -1) {
        copied++
        try {
          side.write(b)
        } catch (_: IOException) {
        }
      }
      return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      val n = upstream.read(b, off, len)
      if (n > 0) {
        copied += n
        try {
          side.write(b, off, n)
        } catch (_: IOException) {
        }
      }
      return n
    }

    override fun close() {
      try {
        upstream.close()
      } finally {
        try {
          side.close()
        } catch (_: IOException) {
        }
        onClose(copied)
      }
    }
  }
}
