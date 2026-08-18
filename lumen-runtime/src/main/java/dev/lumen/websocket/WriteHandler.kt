package dev.lumen.websocket

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Serializes WebSocket frames on a dedicated thread. Chrome inspect's DOM
 * walker runs on the main thread; writing loopback TCP from there is
 * [android.os.NetworkOnMainThreadException] under StrictMode.
 */
internal class WriteHandler(rawSocketOutput: OutputStream) {
  private val bufferedOutput = BufferedOutputStream(rawSocketOutput, 1024)
  private val writer = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "lumen-ws-write").apply { isDaemon = true }
  }

  fun write(frame: Frame, callback: WriteCallback) {
    try {
      writer.execute {
        try {
          frame.writeTo(bufferedOutput)
          bufferedOutput.flush()
          callback.onSuccess()
        } catch (e: IOException) {
          callback.onFailure(e)
        } catch (e: RuntimeException) {
          callback.onFailure(IOException(e))
        }
      }
    } catch (e: RejectedExecutionException) {
      callback.onFailure(IOException("WebSocket session is closed", e))
    }
  }

  /**
   * Drains already-queued frames (the close handshake in particular) before
   * the caller closes the raw socket, then releases the writer thread. Must
   * be called when the session ends; the executor is never reused.
   */
  fun shutdown() {
    writer.shutdown()
    try {
      if (!writer.awaitTermination(SHUTDOWN_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        writer.shutdownNow()
      }
    } catch (e: InterruptedException) {
      writer.shutdownNow()
      Thread.currentThread().interrupt()
    }
  }

  private companion object {
    const val SHUTDOWN_DRAIN_TIMEOUT_MS = 2000L
  }
}
