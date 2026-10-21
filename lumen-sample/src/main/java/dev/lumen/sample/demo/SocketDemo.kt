package dev.lumen.sample.demo

import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.Polling
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import io.socket.engineio.client.transports.WebSocket as EngineIoWebSocket

/** Long-lived connections: a raw OkHttp WebSocket and Socket.IO on top of the same client. */
class SocketDemo(private val client: OkHttpClient) {

  /** An LLM-style stream: many small text deltas, echoed back by the server. */
  suspend fun webSocket(report: Report) {
    val echoes = AtomicInteger()
    val closed = CompletableDeferred<String>()
    val socket = client.newWebSocket(
      Request.Builder().url(ECHO_WS).build(),
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          webSocket.send("""{"type":"start","model":"sample"}""")
          for (i in 1..30) webSocket.send("""{"type":"delta","seq":$i,"text":"token-$i "}""")
          webSocket.send("binary frame from Lumen sample".encodeUtf8())
          webSocket.send("""{"type":"done"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
          echoes.incrementAndGet()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
          closed.complete("closed $code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          closed.complete("failed: ${t.javaClass.simpleName} ${t.message}")
        }
      },
    )
    delay(3_000)
    socket.close(1000, "sample done")
    val outcome = withTimeoutOrNull(5_000) { closed.await() } ?: "close timed out"
    report("  $ECHO_WS → ${echoes.get()} echoes, $outcome")
  }

  /**
   * Socket.IO over both Engine.IO transports. Lumen shows each session as one WebSocket
   * row; polling packets are promoted onto it too. The public echo server is load balanced
   * without sticky sessions, so the polling session may be refused after the handshake.
   */
  suspend fun socketIo(report: Report) {
    report("  websocket transport: " + socketIoSession(EngineIoWebSocket.NAME))
    report("  polling transport: " + socketIoSession(Polling.NAME))
  }

  private suspend fun socketIoSession(transport: String): String {
    val options = IO.Options.builder()
      .setForceNew(true)
      .setReconnection(false)
      .setTransports(arrayOf(transport))
      .build()
    options.callFactory = client
    options.webSocketFactory = client
    val socket = IO.socket(URI.create(SOCKET_IO), options)
    val connected = CompletableDeferred<String>()
    socket.on(Socket.EVENT_CONNECT) { connected.complete("connected sid=${socket.id()}") }
    socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
      connected.complete("connect error ${args.firstOrNull()}")
    }
    socket.connect()
    val outcome = withTimeoutOrNull(10_000) { connected.await() } ?: "connect timed out"
    if (socket.connected()) {
      for (i in 1..5) {
        socket.emit("chat message", JSONObject().put("seq", i).put("text", "hello #$i"))
      }
      delay(1_500)
    }
    socket.disconnect()
    return outcome
  }

  private companion object {
    const val ECHO_WS = "wss://ws.postman-echo.com/raw"
    const val SOCKET_IO = "https://ws.postman-echo.com"
  }
}
