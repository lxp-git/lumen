package dev.lumen.websocket

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteHandlerTest {
  @Test
  fun callerDoesNotBlockOnSocket() {
    val gate = GatedOutputStream()
    val handler = WriteHandler(gate)
    val success = AtomicBoolean(false)
    val started = System.nanoTime()
    handler.write(text("hi"), ok { success.set(true) })
    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    assertTrue("caller blocked ${elapsedMs}ms on the socket write", elapsedMs < 200)
    assertFalse(success.get())
    gate.release.countDown()
    assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
    assertTrue(spinUntil(1000) { success.get() })
    handler.shutdown()
  }

  @Test
  fun queuedBurstIsNotLost() {
    val stream = CountingStream()
    val handler = WriteHandler(stream)
    val done = CountDownLatch(5)
    for (i in 1..5) {
      handler.write(text("m$i"), ok { done.countDown() })
    }
    assertTrue(done.await(2, TimeUnit.SECONDS))
    handler.shutdown()
    val payload = stream.payload()
    for (i in 1..5) {
      assertTrue("missing m$i in $payload", payload.contains("m$i"))
    }
  }

  @Test
  fun immediateWriteDoesNotWaitForDrain() {
    val stream = CountingStream()
    val handler = WriteHandler(stream)
    val done = CountDownLatch(1)
    val started = System.nanoTime()
    handler.writeImmediate(text("rpc"), ok { done.countDown() })
    assertTrue(done.await(2, TimeUnit.SECONDS))
    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    assertTrue("immediate write waited ${elapsedMs}ms", elapsedMs < 250)
    handler.shutdown()
    assertTrue(stream.payload().contains("rpc"))
  }

  @Test
  fun controlFrameFlushesPromptly() {
    val stream = CountingStream()
    val handler = WriteHandler(stream)
    val done = CountDownLatch(1)
    val started = System.nanoTime()
    handler.write(FrameHelper.createCloseFrame(1000, "bye"), ok { done.countDown() })
    assertTrue(done.await(2, TimeUnit.SECONDS))
    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    assertTrue("control frame waited ${elapsedMs}ms", elapsedMs < 250)
    handler.shutdown()
  }

  @Test
  fun immediateFramesPreserveQueueOrder() {
    val stream = FirstWriteGate()
    val handler = WriteHandler(stream)
    val done = CountDownLatch(2)
    handler.writeImmediate(text("first"), ok { done.countDown() })
    assertTrue(stream.entered.await(2, TimeUnit.SECONDS))
    handler.writeImmediate(text("second"), ok { done.countDown() })
    stream.release.countDown()
    assertTrue(done.await(2, TimeUnit.SECONDS))
    handler.shutdown()
    val payload = stream.payload()
    val first = payload.indexOf("first")
    val second = payload.indexOf("second")
    assertTrue("expected first before second in $payload", first >= 0 && first < second)
  }

  @Test
  fun batchPreservesOrder() {
    val stream = CountingStream()
    val handler = WriteHandler(stream)
    val done = CountDownLatch(3)
    handler.write(text("A"), ok { done.countDown() })
    handler.write(text("B"), ok { done.countDown() })
    handler.write(text("C"), ok { done.countDown() })
    assertTrue(done.await(2, TimeUnit.SECONDS))
    handler.shutdown()
    val payload = stream.payload()
    val a = payload.indexOf('A')
    val b = payload.indexOf('B')
    val c = payload.indexOf('C')
    assertTrue("expected A before B before C in $payload", a >= 0 && a < b && b < c)
  }

  @Test
  fun shutdownDrainsQueuedFrames() {
    val stream = ByteArrayOutputStream()
    val handler = WriteHandler(stream)
    val done = CountDownLatch(2)
    handler.write(text("keep"), ok { done.countDown() })
    handler.write(text("me"), ok { done.countDown() })
    handler.shutdown()
    assertTrue(done.await(2, TimeUnit.SECONDS))
    val payload = stream.toString("UTF-8")
    assertTrue(payload.contains("keep"))
    assertTrue(payload.contains("me"))
  }

  @Test
  fun immediateWriteJumpsAheadOfQueuedRegulars() {
    val order = CopyOnWriteArrayList<String>()
    val firstEntered = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val stream =
      object : OutputStream() {
        override fun write(b: Int) {
          write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
          val payload = String(b, off, len)
          if (order.isEmpty()) {
            firstEntered.countDown()
            check(releaseFirst.await(5, TimeUnit.SECONDS))
          }
          order.add(payload)
        }
      }
    val handler = WriteHandler(stream)
    handler.write(text("SLOW"), ok {})
    assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
    for (i in 1..40) {
      handler.write(text("n$i"), ok {})
    }
    val rpcDone = CountDownLatch(1)
    handler.writeImmediate(text("RPC"), ok { rpcDone.countDown() })
    releaseFirst.countDown()
    assertTrue(rpcDone.await(2, TimeUnit.SECONDS))
    assertTrue(spinUntil(2000) { order.any { it.contains("n40") } && order.any { it.contains("RPC") } })
    handler.shutdown()
    val joined = order.joinToString()
    val rpcAt = joined.indexOf("RPC")
    val lastRegular = joined.indexOf("n40")
    assertTrue("RPC should not wait for n40 in $joined", rpcAt >= 0 && rpcAt < lastRegular)
  }

  @Test
  fun backgroundAndMainWritesStayOrdered() {
    val order = CopyOnWriteArrayList<String>()
    val firstEntered = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val stream =
      object : OutputStream() {
        override fun write(b: Int) {
          write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
          val payload = String(b, off, len)
          if (order.isEmpty()) {
            firstEntered.countDown()
            check(releaseFirst.await(5, TimeUnit.SECONDS))
          }
          order.add(payload)
        }
      }
    val handler = WriteHandler(stream)
    handler.write(text("A"), ok {})
    assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
    handler.write(text("B"), ok {})
    releaseFirst.countDown()
    assertTrue(spinUntil(2000) { order.joinToString().let { it.contains('A') && it.contains('B') } })
    handler.shutdown()
    val joined = order.joinToString()
    assertTrue("expected A before B in $joined", joined.indexOf('A') < joined.indexOf('B'))
  }

  @Test
  fun spliceFailureNotifiesCallback() {
    val firstEntered = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val firstWrite = AtomicBoolean(true)
    val stream =
      object : OutputStream() {
        override fun write(b: Int) {
          write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
          if (firstWrite.compareAndSet(true, false)) {
            firstEntered.countDown()
            check(releaseFirst.await(5, TimeUnit.SECONDS))
          }
          if (String(b, off, len).contains("RPC")) throw IOException("splice boom")
        }
      }
    val handler = WriteHandler(stream)
    val regularFailed = AtomicBoolean(false)
    handler.write(
      text("SLOW" + "x".repeat(40_000)),
      object : WriteCallback {
        override fun onSuccess() {}
        override fun onFailure(e: IOException) {
          regularFailed.set(true)
        }
      },
    )
    assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
    val rpcFailed = CountDownLatch(1)
    handler.writeImmediate(
      text("RPC"),
      object : WriteCallback {
        override fun onSuccess() {}
        override fun onFailure(e: IOException) {
          rpcFailed.countDown()
        }
      },
    )
    releaseFirst.countDown()
    assertTrue(rpcFailed.await(2, TimeUnit.SECONDS))
    handler.shutdown()
    assertTrue(spinUntil(1000) { regularFailed.get() })
  }

  @Test
  fun writeAfterShutdownFailsCallback() {
    val handler = WriteHandler(ByteArrayOutputStream())
    handler.shutdown()
    val failed = AtomicBoolean(false)
    handler.write(
      text("late"),
      object : WriteCallback {
        override fun onSuccess() {}
        override fun onFailure(e: IOException) {
          failed.set(true)
        }
      },
    )
    assertTrue(failed.get() || spinUntil(1000) { failed.get() })
  }

  private fun text(payload: String): Frame = FrameHelper.createTextFrame(payload)

  private fun ok(onSuccess: () -> Unit): WriteCallback {
    return object : WriteCallback {
      override fun onSuccess() = onSuccess()
      override fun onFailure(e: IOException) {
        throw AssertionError(e)
      }
    }
  }

  private fun spinUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (cond()) return true
      Thread.sleep(10)
    }
    return cond()
  }

  private class FirstWriteGate : OutputStream() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val buf = ByteArrayOutputStream()
    private var gated = true

    override fun write(b: Int) {
      write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      synchronized(this) {
        if (gated) {
          gated = false
          entered.countDown()
          check(release.await(5, TimeUnit.SECONDS))
        }
        buf.write(b, off, len)
      }
    }

    @Synchronized
    fun payload(): String = buf.toString(Charsets.UTF_8.name())
  }

  private class GatedOutputStream : OutputStream() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun write(b: Int) {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
    }
  }

  private class CountingStream : OutputStream() {
    private val buf = ByteArrayOutputStream()
    val flushCount = AtomicInteger()

    @Synchronized
    override fun write(b: Int) {
      buf.write(b)
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
      buf.write(b, off, len)
    }

    override fun flush() {
      flushCount.incrementAndGet()
    }

    @Synchronized
    fun payload(): String = buf.toString("UTF-8")
  }
}
