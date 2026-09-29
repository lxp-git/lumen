package dev.lumen.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BodyQuotaTest {
  @get:Rule
  val temp = TemporaryFolder()

  private fun body(dir: File, name: String, bytes: Int, modifiedAt: Long): File =
    File(dir, name).apply {
      writeBytes(ByteArray(bytes))
      setLastModified(modifiedAt)
    }

  @Test
  fun keepsEveryBodyWhileUnderOrAtQuota() {
    val dir = temp.newFolder("bodies")
    val a = body(dir, "a", 40, 1_000_000L)
    val b = body(dir, "b", 60, 2_000_000L)

    BodyQuota.prune(dir, quotaBytes = 100)

    assertTrue(a.exists())
    assertTrue(b.exists())
  }

  @Test
  fun deletesOldestFirstUntilUnderQuota() {
    val dir = temp.newFolder("bodies")
    val newest = body(dir, "newest", 50, 3_000_000L)
    val oldest = body(dir, "oldest", 50, 1_000_000L)
    val middle = body(dir, "middle", 50, 2_000_000L)

    BodyQuota.prune(dir, quotaBytes = 100)

    assertFalse(oldest.exists())
    assertTrue(middle.exists())
    assertTrue(newest.exists())
  }

  @Test
  fun deletesSeveralOldBodiesWhenOneIsNotEnough() {
    val dir = temp.newFolder("bodies")
    val old1 = body(dir, "old1", 30, 1_000_000L)
    val old2 = body(dir, "old2", 30, 2_000_000L)
    val big = body(dir, "big", 90, 3_000_000L)

    BodyQuota.prune(dir, quotaBytes = 100)

    assertFalse(old1.exists())
    assertFalse(old2.exists())
    assertTrue(big.exists())
  }

  @Test
  fun missingDirectoryIsANoOp() {
    BodyQuota.prune(File(temp.root, "absent"), quotaBytes = 0)
  }

  @Test
  fun burstOfRequestsQueuesOnePassOffTheCallerThread() {
    val queue = ArrayDeque<Runnable>()
    var passes = 0
    val scheduler = BodyPruneScheduler({ queue.addLast(it) }) { passes++ }

    repeat(5) { scheduler.request() }

    assertEquals(0, passes)
    assertEquals(1, queue.size)
    queue.removeFirst().run()
    assertEquals(1, passes)
    assertTrue(queue.isEmpty())
  }

  @Test
  fun requestDuringAPassQueuesExactlyOneFollowUp() {
    val queue = ArrayDeque<Runnable>()
    lateinit var scheduler: BodyPruneScheduler
    var passes = 0
    scheduler = BodyPruneScheduler({ queue.addLast(it) }) {
      passes++
      if (passes == 1) {
        scheduler.request()
        scheduler.request()
      }
    }

    scheduler.request()
    queue.removeFirst().run()

    assertEquals(1, queue.size)
    queue.removeFirst().run()
    assertEquals(2, passes)
    assertTrue(queue.isEmpty())
  }
}
