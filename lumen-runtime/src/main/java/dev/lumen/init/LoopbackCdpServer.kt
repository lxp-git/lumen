package dev.lumen.init

import dev.lumen.common.LogUtil
import dev.lumen.inspector.DevtoolsSocketHandler
import dev.lumen.server.LeakyBufferedInputStream
import dev.lumen.server.SocketLike
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Loopback HTTP+WS so [dev.lumen.proxy.DevtoolsProxy] (adb shell, uid 2000)
 * can reach CDP. App-owned abstract sockets are SELinux-blocked from shell.
 * Bound on 127.0.0.1 only; started when the inspect sidecar needs it.
 */
internal object LoopbackCdpServer {
  const val PORT = 18789

  @Volatile
  private var server: ServerSocket? = null

  /**
   * @return true when the loopback listener is running (already or newly
   * bound); false when the port could not be bound, in which case callers
   * must not report CDP as reachable on [PORT].
   */
  @Synchronized
  fun start(handler: DevtoolsSocketHandler): Boolean {
    val existing = server
    if (existing != null && !existing.isClosed) return true
    val ss = try {
      ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
    } catch (e: IOException) {
      LogUtil.w(e, "Lumen loopback CDP failed to bind 127.0.0.1:%d", PORT)
      return false
    }
    server = ss
    Thread({
      while (!ss.isClosed) {
        val socket = try {
          ss.accept()
        } catch (_: Exception) {
          break
        }
        Thread({
          try {
            val leaky = LeakyBufferedInputStream(socket.getInputStream(), 1024)
            handler.onAccepted(SocketLike(socket.getOutputStream(), leaky))
          } catch (_: Exception) {
          } finally {
            try {
              socket.close()
            } catch (_: Exception) {
            }
          }
        }, "lumen-cdp-tcp").apply {
          isDaemon = true
          start()
        }
      }
    }, "lumen-cdp-listen").apply {
      isDaemon = true
      start()
    }
    return true
  }

  @Synchronized
  fun stop() {
    try {
      server?.close()
    } catch (_: Exception) {
    }
    server = null
  }

  fun isRunning(): Boolean {
    val ss = server
    return ss != null && !ss.isClosed
  }
}
