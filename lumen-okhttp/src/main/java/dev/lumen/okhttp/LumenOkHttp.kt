package dev.lumen.okhttp

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Idempotent install hooks used by the Gradle ASM weave and by manual setups.
 *
 * - [install] adds [LumenInterceptor] once on [OkHttpClient.Builder]
 * - [wrapWebSocketListener] decorates host listeners so inbound frames reach Chrome Network
 * - [wrapWebSocket] decorates the returned socket so outbound frames are reported too
 */
object LumenOkHttp {
  @JvmStatic
  fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder {
    val already = builder.interceptors().any { it is LumenInterceptor }
    if (!already) {
      builder.addInterceptor(LumenInterceptor())
    }
    return builder
  }

  /**
   * ASM injects this at the start of `OkHttpClient.newWebSocket(Request, WebSocketListener)`:
   * the listener argument is replaced with a [LumenWebSocketListener] wrapper (idempotent).
   */
  @JvmStatic
  fun wrapWebSocketListener(request: Request, listener: WebSocketListener): WebSocketListener {
    if (listener is LumenWebSocketListener) {
      return listener
    }
    return LumenWebSocketListener(request.url.toString(), listener)
  }

  /**
   * ASM injects this at the end of `OkHttpClient.newWebSocket` to wrap the returned socket
   * so [WebSocket.send] frames appear in the Network panel.
   */
  @JvmStatic
  fun wrapWebSocket(socket: WebSocket, listener: WebSocketListener): WebSocket {
    if (socket is LumenWebSocket) {
      return socket
    }
    val lumenListener = listener as? LumenWebSocketListener
    return LumenWebSocket(socket) {
      lumenListener?.requestIdForSend()
    }
  }
}
