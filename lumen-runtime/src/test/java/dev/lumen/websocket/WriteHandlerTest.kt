package dev.lumen.websocket

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteHandlerTest {
  @Test
  fun offMainThreadWriteReturnsAfterFlush() {
    val gate = GatedOutputStream()
    val handler = WriteHandler(gate, isMainThread = { false })
    val success = AtomicBoolean(false)
    val callerReturned = AtomicBoolean(false)
    val t =
      Thread {
        handler.write(text("hi"), ok { success.set(true) })
        callerReturned.set(true)
      }
    t.start()
    assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
    Thread.sleep(80)
    assertFalse("caller returned before the socket write finished", callerReturned.get())
    gate.release.countDown()
    t.join(2000)
    assertTrue(callerReturned.get())
    assertTrue(success.get())
    handler.shutdown()
  }

  @Test
  fun mainThreadWriteDoesNotBlockOnSocket() {
    val gate = GatedOutputStream()
    val handler = WriteHandler(gate, isMainThread = { true })
    val success = AtomicBoolean(false)
    val started = System.nanoTime()
    handler.write(text("hi"), ok { success.set(true) })
    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    assertTrue("main-thread write blocked ${elapsedMs}ms", elapsedMs < 400)
    assertFalse(success.get())
    gate.release.countDown()
    assertTrue(gate.entered.await(2, TimeUnit.SECONDS))
    val flushed = spinUntil(1000) { success.get() }
    assertTrue(flushed)
    handler.shutdown()
  }

  @Test
  fun backgroundWaitPreservesOrderAfterQueuedMainWrite() {
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
    val handler = WriteHandler(stream, isMainThread = { Thread.currentThread().name == "fake-main" })
    val main =
      Thread(
        {
          handler.write(text("A"), ok {})
        },
        "fake-main",
      )
    main.start()
    assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
    val bgDone = AtomicBoolean(false)
    val bg =
      Thread {
        handler.write(text("B"), ok {})
        bgDone.set(true)
      }
    bg.start()
    Thread.sleep(50)
    assertFalse("background write returned before earlier main-thread frame flushed", bgDone.get())
    releaseFirst.countDown()
    bg.join(2000)
    main.join(2000)
    assertTrue(bgDone.get())
    handler.shutdown()
    val joined = order.joinToString()
    assertTrue("expected A before B in $joined", joined.indexOf('A') < joined.indexOf('B'))
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
}
