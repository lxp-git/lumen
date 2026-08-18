package dev.lumen.proxy

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Base64
import androidx.annotation.Keep
import dev.lumen.init.LoopbackCdpServer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Chrome-facing `@lumen_*_devtools_remote` that outlives `am force-stop`.
 *
 * chrome://inspect Reconnect is `location.reload()` of a host connection whose
 * `agent_host_` is cleared on socket close. Holding this socket (and the
 * WebSocket) in an adb-shell process means Chrome never detaches.
 *
 *   CLASSPATH=<apk> app_process64 /system/bin dev.lumen.proxy.DevtoolsProxy \
 *     lumen_<package>_devtools_remote
 */
@Keep
object DevtoolsProxy {
  private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
  private val logFile = File("/data/local/tmp/lumen-proxy.log")

  @JvmStatic
  fun main(args: Array<String>) {
    val chromeName = args.getOrNull(0) ?: error("usage: DevtoolsProxy <chromeSocket>")
    log("start chrome=@$chromeName pid=${android.os.Process.myPid()}")
    Server(chromeName).serve()
  }

  internal fun log(msg: String) {
    val line = "${System.currentTimeMillis()} $msg\n"
    try {
      FileOutputStream(logFile, true).use { it.write(line.toByteArray()) }
    } catch (_: IOException) {
    }
    System.err.print(line)
  }

  private class Server(private val chromeName: String) {
    @Volatile private var cachedVersion: ByteArray? = null
    @Volatile private var cachedList: ByteArray? = null

    fun serve() {
      while (true) {
        try {
          LocalServerSocket(chromeName).use { server ->
            log("listening @$chromeName")
            while (true) {
              val client = server.accept()
              thread(name = "lumen-proxy-client", isDaemon = true) {
                try {
                  handleClient(client)
                } catch (t: Throwable) {
                  log("client ${t.javaClass.simpleName}: ${t.message}")
                } finally {
                  try {
                    client.close()
                  } catch (_: IOException) {
                  }
                }
              }
            }
          }
        } catch (t: Throwable) {
          log("listen ${t.javaClass.simpleName}: ${t.message}")
          Thread.sleep(400)
        }
      }
    }

    private fun handleClient(client: LocalSocket) {
      val input = BufferedInputStream(client.inputStream)
      val output = BufferedOutputStream(client.outputStream)
      val header = readHttpHead(input) ?: return
      val first = header.lineSequence().firstOrNull().orEmpty()
      val path = first.substringAfter(' ', "").substringBefore(' ').ifEmpty { "/" }
      val upgrade = header.contains("Upgrade: websocket", ignoreCase = true) ||
        header.contains("Upgrade: WebSocket", ignoreCase = true)
      if (upgrade) {
        serveWebSocket(path, header, input, output)
      } else {
        serveHttp(path, header, input, output)
      }
    }

    private fun serveHttp(path: String, header: String, input: InputStream, output: java.io.OutputStream) {
      val backend = connectCdp(timeoutMs = 400)
      if (backend != null) {
        try {
          backend.getOutputStream().write(header.toByteArray(Charsets.ISO_8859_1))
          backend.getOutputStream().flush()
          val extra = drainAvailable(input)
          if (extra.isNotEmpty()) backend.getOutputStream().write(extra)
          backend.getOutputStream().flush()
          val response = readHttpResponse(BufferedInputStream(backend.getInputStream()))
          cacheIfJson(path, response)
          output.write(response)
          output.flush()
        } catch (t: Throwable) {
          log("http-forward $path ${t.message}")
          writeCachedOrStub(path, output)
        } finally {
          try {
            backend.close()
          } catch (_: IOException) {
          }
        }
      } else {
        writeCachedOrStub(path, output)
      }
    }

    private fun cacheIfJson(path: String, response: ByteArray) {
      val body = httpBody(response) ?: return
      when {
        path.startsWith("/json/version") -> cachedVersion = body
        path == "/json" || path.startsWith("/json/list") -> cachedList = body
      }
    }

    private fun writeCachedOrStub(path: String, output: java.io.OutputStream) {
      val body = when {
        path.startsWith("/json/version") -> cachedVersion ?: DEFAULT_VERSION.toByteArray()
        path == "/json" || path.startsWith("/json/list") -> cachedList ?: DEFAULT_LIST.toByteArray()
        else -> "ok\n".toByteArray()
      }
      val head =
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
      output.write(head.toByteArray())
      output.write(body)
      output.flush()
    }

    private fun serveWebSocket(
      path: String,
      header: String,
      chromeIn: BufferedInputStream,
      chromeOut: BufferedOutputStream,
    ) {
      val key = header.lineSequence()
        .firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?: return
      chromeOut.write(
        (
          "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n"
          ).toByteArray(Charsets.ISO_8859_1),
      )
      chromeOut.flush()
      log("101 $path")

      val recorded = CopyOnWriteArrayList<String>()
      val pending = ConcurrentLinkedDeque<ByteArray>()
      val backendHolder = AtomicReference<Backend?>(null)
      val chromeOpen = AtomicBoolean(true)
      // chromeOut is shared by the PONG reply below and the backend loop.
      val chromeLock = Any()

      val reader = thread(name = "lumen-proxy-chrome-rd", isDaemon = true) {
        try {
          while (chromeOpen.get()) {
            val frame = Ws.read(chromeIn) ?: break
            if (frame.opcode == Ws.CLOSE) break
            if (frame.opcode == Ws.PING) {
              synchronized(chromeLock) {
                Ws.write(chromeOut, Ws.PONG, frame.payload, mask = false)
              }
              continue
            }
            if (frame.opcode == Ws.PONG) continue
            val text = frame.payload.toString(Charsets.UTF_8)
            InspectProxyMessages.record(recorded, text)
            val backend = backendHolder.get()
            if (backend != null) {
              try {
                backend.write(frame.opcode, frame.payload)
              } catch (t: Throwable) {
                log("to-backend ${t.message}")
                pending.add(frame.payload)
                // Tear the backend down so the main loop reconnects and
                // replays `pending`; otherwise the frame would sit queued for
                // as long as this half-broken connection stays up.
                backend.close()
              }
            } else {
              pending.add(frame.payload)
              // The backend may have been published after the holder check
              // above but past its own drain point; drain here so the frame
              // does not wait for the next reconnect.
              val published = backendHolder.get()
              if (published != null) {
                try {
                  drainPending(pending, published)
                } catch (t: Throwable) {
                  log("to-backend ${t.message}")
                  published.close()
                }
              }
            }
          }
        } catch (t: Throwable) {
          log("chrome-rd ${t.message}")
        } finally {
          chromeOpen.set(false)
        }
      }

      try {
        while (chromeOpen.get()) {
          val backend = openBackendWs(path)
          if (backend == null) {
            Thread.sleep(200)
            continue
          }
          val replayIds = HashSet<Int>()
          log("backend up, replay ${recorded.size}")
          try {
            var rid = 990001
            for (msg in recorded) {
              val rewritten = InspectProxyMessages.rewriteId(msg, rid)
              replayIds.add(rid)
              rid++
              backend.write(Ws.TEXT, rewritten.toByteArray(Charsets.UTF_8))
            }
            drainPending(pending, backend)
            backendHolder.set(backend)
            // Catch frames the chrome reader queued between the drain above
            // and publishing the backend; it writes directly from here on.
            drainPending(pending, backend)
            while (chromeOpen.get()) {
              val frame = Ws.read(backend.input) ?: break
              if (frame.opcode == Ws.CLOSE) break
              if (frame.opcode == Ws.PING) {
                backend.write(Ws.PONG, frame.payload)
                continue
              }
              if (frame.opcode == Ws.PONG) continue
              val text = frame.payload.toString(Charsets.UTF_8)
              if (InspectProxyMessages.isReplayResponse(text, replayIds)) continue
              synchronized(chromeLock) {
                Ws.write(chromeOut, frame.opcode, frame.payload, mask = false)
              }
            }
          } catch (t: Throwable) {
            log("backend-rd ${t.message}")
          } finally {
            backendHolder.set(null)
            backend.close()
            log("backend down")
          }
        }
      } finally {
        chromeOpen.set(false)
        reader.join(1000)
      }
    }

    private fun drainPending(pending: ConcurrentLinkedDeque<ByteArray>, backend: Backend) {
      while (true) {
        val queued = pending.poll() ?: break
        try {
          backend.write(Ws.TEXT, queued)
        } catch (t: Throwable) {
          // Keep the frame (in order) for the next backend instead of losing it.
          pending.addFirst(queued)
          throw t
        }
      }
    }

    private fun openBackendWs(path: String): Backend? {
      val conn = connectCdp(timeoutMs = 400) ?: return null
      return try {
        val key = Base64.encodeToString(ByteArray(16) { 1 }, Base64.NO_WRAP)
        val req =
          "GET $path HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
        conn.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
        conn.getOutputStream().flush()
        val backendIn = BufferedInputStream(conn.getInputStream())
        val head = readHttpHead(backendIn)
        if (head == null || !head.contains(" 101 ")) {
          conn.close()
          return null
        }
        Backend(conn, backendIn, BufferedOutputStream(conn.getOutputStream()))
      } catch (_: Throwable) {
        conn.close()
        null
      }
    }

    private fun connectCdp(timeoutMs: Int): Socket? {
      return try {
        val tcp = Socket()
        tcp.connect(InetSocketAddress("127.0.0.1", LoopbackCdpServer.PORT), timeoutMs)
        tcp.tcpNoDelay = true
        tcp
      } catch (t: Exception) {
        log("cdp-connect ${t.javaClass.simpleName}: ${t.message}")
        null
      }
    }
  }

  private class Backend(
    val conn: Socket,
    val input: InputStream,
    private val out: BufferedOutputStream,
  ) {
    private val writeLock = Any()

    /** Serializes writes: chrome-rd and the backend loop share this stream. */
    fun write(opcode: Int, payload: ByteArray) {
      synchronized(writeLock) {
        Ws.write(out, opcode, payload, mask = true)
      }
    }

    fun close() {
      try {
        conn.close()
      } catch (_: IOException) {
      }
    }
  }

  private object Ws {
    const val TEXT: Int = 0x1
    const val CLOSE: Int = 0x8
    const val PING: Int = 0x9
    const val PONG: Int = 0xA

    class Frame(val opcode: Int, val payload: ByteArray)

    fun read(input: InputStream): Frame? {
      val b0 = input.read()
      if (b0 < 0) return null
      val b1 = input.read()
      if (b1 < 0) return null
      val opcode = b0 and 0x0f
      val masked = b1 and 0x80 != 0
      var len = (b1 and 0x7f).toLong()
      when (len) {
        126L -> len = readU16(input).toLong()
        127L -> len = readU64(input)
      }
      val mask = if (masked) {
        val m = ByteArray(4)
        readFully(input, m)
        m
      } else {
        null
      }
      val payload = ByteArray(len.toInt())
      readFully(input, payload)
      if (mask != null) {
        for (i in payload.indices) {
          payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        }
      }
      return Frame(opcode, payload)
    }

    fun write(output: BufferedOutputStream, opcode: Int, payload: ByteArray, mask: Boolean) {
      output.write(0x80 or (opcode and 0x0f))
      val n = payload.size
      val maskBit = if (mask) 0x80 else 0
      when {
        n < 126 -> output.write(maskBit or n)
        n < 65536 -> {
          output.write(maskBit or 126)
          output.write((n shr 8) and 0xff)
          output.write(n and 0xff)
        }
        else -> {
          output.write(maskBit or 127)
          var v = n.toLong()
          for (i in 7 downTo 0) {
            output.write(((v shr (i * 8)) and 0xffL).toInt())
          }
        }
      }
      val data: ByteArray
      if (mask) {
        val key = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        output.write(key)
        data = ByteArray(n) { i -> (payload[i].toInt() xor key[i and 3].toInt()).toByte() }
      } else {
        data = payload
      }
      output.write(data)
      output.flush()
    }

    private fun readU16(input: InputStream): Int {
      val a = input.read()
      val b = input.read()
      if (a < 0 || b < 0) throw SocketException("eof")
      return (a shl 8) or b
    }

    private fun readU64(input: InputStream): Long {
      var v = 0L
      repeat(8) {
        val b = input.read()
        if (b < 0) throw SocketException("eof")
        v = (v shl 8) or b.toLong()
      }
      return v
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
      var o = 0
      while (o < buf.size) {
        val n = input.read(buf, o, buf.size - o)
        if (n < 0) throw SocketException("eof")
        o += n
      }
    }
  }

  private fun acceptKey(clientKey: String): String {
    val md = MessageDigest.getInstance("SHA-1")
    val digest = md.digest((clientKey + GUID).toByteArray(Charsets.US_ASCII))
    return Base64.encodeToString(digest, Base64.NO_WRAP)
  }

  private fun readHttpHead(input: InputStream): String? {
    val buf = ByteArrayOutputStream()
    var last4 = 0
    while (true) {
      val b = input.read()
      if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
      buf.write(b)
      last4 = (last4 shl 8) or (b and 0xff)
      if (last4 == 0x0d0a0d0a) return buf.toString("ISO-8859-1")
      if (buf.size() > 65536) return buf.toString("ISO-8859-1")
    }
  }

  private fun drainAvailable(input: InputStream): ByteArray {
    val n = input.available()
    if (n <= 0) return ByteArray(0)
    val buf = ByteArray(n)
    val got = input.read(buf)
    return if (got <= 0) ByteArray(0) else buf.copyOf(got)
  }

  private fun readHttpResponse(input: BufferedInputStream): ByteArray {
    val head = readHttpHead(input) ?: throw SocketException("empty http response")
    val length = head.lineSequence()
      .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
      ?.substringAfter(':')
      ?.trim()
      ?.toIntOrNull()
      ?: 0
    val body = ByteArray(length)
    var o = 0
    while (o < length) {
      val n = input.read(body, o, length - o)
      if (n < 0) break
      o += n
    }
    return head.toByteArray(Charsets.ISO_8859_1) + body.copyOf(o)
  }

  private fun httpBody(response: ByteArray): ByteArray? {
    val text = response.toString(Charsets.ISO_8859_1)
    val idx = text.indexOf("\r\n\r\n")
    if (idx < 0) return null
    return response.copyOfRange(idx + 4, response.size)
  }

  private const val DEFAULT_VERSION =
    """{"Browser":"Chrome/151.0.7922.71","Protocol-Version":"1.3","User-Agent":"Lumen","V8-Version":"13.5.0.0","WebKit-Version":"537.36","webSocketDebuggerUrl":"ws://localhost/devtools/page/1"}"""
  private const val DEFAULT_LIST =
    """[{"id":"1","type":"page","title":"Lumen","url":"lumen://local","webSocketDebuggerUrl":"ws://localhost/devtools/page/1","devtoolsFrontendUrl":"/devtools/inspector.html?ws=localhost/devtools/page/1"}]"""
}
