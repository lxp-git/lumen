package dev.lumen.websocket

import android.os.Looper
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Serializes WebSocket frames on a dedicated thread so Chrome inspect's DOM
 * walker (main thread) can write loopback TCP without
 * [android.os.NetworkOnMainThreadException].
 *
 * Off the main thread the caller waits until the frame is flushed. OkHttp's
 * interceptor then emits [Network.requestWillBeSent] on the wire before
 * [okhttp3.Interceptor.Chain.proceed], so Chrome Network shows the row while
 * the request is still in flight. Fire-and-forget from every thread bundled
 * sent/received/finished into one burst after the call completed.
 */
internal class WriteHandler @JvmOverloads constructor(
  rawSocketOutput: OutputStream,
  private val isMainThread: () -> Boolean = Companion::onMainLooper,
) {
  private val bufferedOutput = BufferedOutputStream(rawSocketOutput, 1024)

  @Volatile private var writerThread: Thread? = null

  private val writer = Executors.newSingleThreadExecutor { runnable ->
    Thread(
      {
        writerThread = Thread.currentThread()
        runnable.run()
      },
      "lumen-ws-write",
    ).apply { isDaemon = true }
  }

  fun write(frame: Frame, callback: WriteCallback) {
    if (Thread.currentThread() === writerThread) {
      emit(frame, callback)
      return
    }
    val done = CountDownLatch(1)
    try {
      writer.execute {
        try {
          emit(frame, callback)
        } finally {
          done.countDown()
        }
      }
    } catch (e: RejectedExecutionException) {
      callback.onFailure(IOException("WebSocket session is closed", e))
      return
    }
    if (isMainThread()) return
    try {
      done.await()
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private fun emit(frame: Frame, callback: WriteCallback) {
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

    fun onMainLooper(): Boolean {
      return try {
        val main = Looper.getMainLooper() ?: return false
        main === Looper.myLooper()
      } catch (_: Throwable) {
        false
      }
    }
  }
}
