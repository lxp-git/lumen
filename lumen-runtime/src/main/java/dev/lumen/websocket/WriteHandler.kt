package dev.lumen.websocket

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes WebSocket frames on a dedicated thread so Chrome inspect's DOM
 * walker (main thread) can write loopback TCP without
 * [android.os.NetworkOnMainThreadException].
 *
 * Callers never block on the socket. Regular frames drain in batches of
 * [MAX_BATCH_FRAMES]. JSON-RPC replies and RFC6455 control frames use
 * [writeImmediate]: they skip ahead of queued regulars and are flushed as
 * soon as the current in-flight frame finishes, so they are not stuck behind
 * a large notification drain.
 */
internal class WriteHandler(
  rawSocketOutput: OutputStream,
) {
  private val bufferedOutput = BufferedOutputStream(rawSocketOutput, OUTPUT_BUFFER_BYTES)
  private val regular = LinkedBlockingQueue<QueuedWrite>()
  private val immediate = LinkedBlockingQueue<QueuedWrite>()
  private val wakeup = Semaphore(0)

  @Volatile private var closed = false

  private val writerThread = Thread(::loop, "lumen-ws-write").apply {
    isDaemon = true
    start()
  }

  fun write(frame: Frame, callback: WriteCallback) {
    enqueue(frame, callback, immediate = isControlFrame(frame))
  }

  fun writeImmediate(frame: Frame, callback: WriteCallback) {
    enqueue(frame, callback, immediate = true)
  }

  private fun enqueue(frame: Frame, callback: WriteCallback, immediate: Boolean) {
    if (closed) {
      callback.onFailure(IOException("WebSocket session is closed"))
      return
    }
    val item = QueuedWrite(frame, callback, immediate)
    if (immediate) this.immediate.offer(item) else regular.offer(item)
    wakeup.release()
    if (closed && !writerThread.isAlive) {
      val q = if (immediate) this.immediate else regular
      if (q.remove(item)) {
        item.signalFailure(IOException("WebSocket session is closed"))
      }
    }
  }

  private fun loop() {
    try {
      while (true) {
        val batch = collectBatch() ?: break
        emitBatch(batch)
        if (closed && regular.isEmpty() && immediate.isEmpty()) break
      }
    } finally {
      closed = true
      val leftover = ArrayList<QueuedWrite>()
      immediate.drainTo(leftover)
      regular.drainTo(leftover)
      leftover.removeAll { it.isPoison }
      if (leftover.isNotEmpty()) emitBatch(leftover)
    }
  }

  /**
   * @return frames to emit, or null when the writer should exit.
   */
  private fun collectBatch(): ArrayList<QueuedWrite>? {
    val first = takeNext() ?: return null
    if (first.isPoison) {
      closed = true
      val rest = ArrayList<QueuedWrite>()
      drainImmediate(rest)
      drainRegular(rest)
      stripPoison(rest)
      return if (rest.isEmpty()) null else rest
    }
    val batch = ArrayList<QueuedWrite>(64)
    batch.add(first)
    if (first.immediate) {
      drainImmediate(batch)
    } else if (!closed) {
      drainRegular(batch)
    }
    if (stripPoison(batch)) closed = true
    return if (batch.isEmpty()) null else batch
  }

  private fun takeNext(): QueuedWrite? {
    while (true) {
      immediate.poll()?.let { return it }
      regular.poll()?.let { return it }
      if (closed) return immediate.poll() ?: regular.poll()
      try {
        wakeup.acquire()
      } catch (_: InterruptedException) {
        Thread.interrupted()
        if (closed) return immediate.poll() ?: regular.poll()
      }
    }
  }

  private fun drainRegular(batch: ArrayList<QueuedWrite>) {
    while (batch.size < MAX_BATCH_FRAMES) {
      val next = regular.peek() ?: break
      if (next.isPoison) break
      batch.add(regular.poll() ?: break)
    }
  }

  private fun drainImmediate(batch: ArrayList<QueuedWrite>) {
    while (true) {
      val next = immediate.poll() ?: break
      batch.add(next)
    }
  }

  private fun stripPoison(batch: ArrayList<QueuedWrite>): Boolean {
    var seen = false
    val it = batch.iterator()
    while (it.hasNext()) {
      if (it.next().isPoison) {
        it.remove()
        seen = true
      }
    }
    return seen
  }

  private fun emitBatch(batch: List<QueuedWrite>) {
    if (batch.isEmpty()) return
    val signaled = LinkedHashSet<QueuedWrite>(batch.size + 4)
    var spliced: QueuedWrite? = null
    try {
      for (item in batch) {
        if (!item.immediate) {
          spliceImmediates(signaled) { spliced = it }
        }
        item.frame!!.writeTo(bufferedOutput)
        if (item.immediate) {
          bufferedOutput.flush()
          succeed(item, signaled)
        }
      }
      spliceImmediates(signaled) { spliced = it }
      bufferedOutput.flush()
      for (item in batch) succeed(item, signaled)
    } catch (e: IOException) {
      failSpliced(spliced, signaled, e)
      failRemaining(batch, signaled, e)
    } catch (e: RuntimeException) {
      val error = IOException(e)
      failSpliced(spliced, signaled, error)
      failRemaining(batch, signaled, error)
    }
  }

  /**
   * Flush whatever is already buffered, then emit every RPC/control frame that
   * arrived while this regular batch was on the socket.
   */
  private fun spliceImmediates(
    signaled: MutableSet<QueuedWrite>,
    inFlight: (QueuedWrite?) -> Unit,
  ) {
    var first = true
    while (true) {
      val imm = immediate.poll() ?: return
      if (imm.isPoison) {
        closed = true
        continue
      }
      inFlight(imm)
      if (first) {
        bufferedOutput.flush()
        first = false
      }
      imm.frame!!.writeTo(bufferedOutput)
      bufferedOutput.flush()
      succeed(imm, signaled)
      inFlight(null)
    }
  }

  private fun succeed(item: QueuedWrite, signaled: MutableSet<QueuedWrite>) {
    if (signaled.add(item)) item.signalSuccess()
  }

  private fun failSpliced(
    spliced: QueuedWrite?,
    signaled: MutableSet<QueuedWrite>,
    error: IOException,
  ) {
    if (spliced != null && signaled.add(spliced)) spliced.signalFailure(error)
  }

  private fun failRemaining(
    batch: List<QueuedWrite>,
    signaled: MutableSet<QueuedWrite>,
    error: IOException,
  ) {
    for (item in batch) {
      if (signaled.add(item)) item.signalFailure(error)
    }
  }

  /**
   * Drains already-queued frames (the close handshake in particular) before
   * the caller closes the raw socket, then releases the writer thread. Must
   * be called when the session ends; the writer is never reused.
   */
  fun shutdown() {
    closed = true
    regular.offer(POISON)
    wakeup.release()
    writerThread.interrupt()
    try {
      writerThread.join(SHUTDOWN_DRAIN_TIMEOUT_MS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    if (!writerThread.isAlive) failLeftovers()
  }

  private fun failLeftovers() {
    val leftover = ArrayList<QueuedWrite>()
    immediate.drainTo(leftover)
    regular.drainTo(leftover)
    val error = IOException("WebSocket session is closed")
    for (item in leftover) {
      if (!item.isPoison) item.signalFailure(error)
    }
  }

  private class QueuedWrite(
    val frame: Frame?,
    val callback: WriteCallback?,
    val immediate: Boolean,
  ) {
    private val done = AtomicBoolean(false)
    val isPoison: Boolean get() = frame == null

    fun signalSuccess() {
      if (done.compareAndSet(false, true)) callback?.onSuccess()
    }

    fun signalFailure(error: IOException) {
      if (done.compareAndSet(false, true)) callback?.onFailure(error)
    }
  }

  private companion object {
    const val SHUTDOWN_DRAIN_TIMEOUT_MS = 2000L
    const val OUTPUT_BUFFER_BYTES = 32 * 1024
    const val MAX_BATCH_FRAMES = 256

    val POISON = QueuedWrite(null, null, true)

    fun isControlFrame(frame: Frame): Boolean {
      val opcode = frame.opcode
      return opcode == Frame.OPCODE_CONNECTION_CLOSE ||
        opcode == Frame.OPCODE_CONNECTION_PING ||
        opcode == Frame.OPCODE_CONNECTION_PONG
    }
  }
}
