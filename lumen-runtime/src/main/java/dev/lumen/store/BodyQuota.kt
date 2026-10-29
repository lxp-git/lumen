package dev.lumen.store

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the network history on disk (spilled bodies and earlier launches' session files) under
 * [dev.lumen.LumenConfig.networkBodyQuotaBytes].
 *
 * The directory can hold thousands of files, so every size and timestamp is read once per pass
 * (a comparator calling [File.lastModified] costs n·log n stat calls) and the oldest-first sort
 * only happens when the quota is actually exceeded.
 */
internal object BodyQuota {
  fun prune(dir: File, quotaBytes: Long) {
    prune(dir.listFiles()?.asList() ?: return, quotaBytes)
  }

  /**
   * [pinnedBytes] (the live session file) count toward the quota but are never deleted. They
   * count for at most half of it, so a huge live file cannot make every pass delete every body.
   */
  fun prune(files: List<File>, quotaBytes: Long, pinnedBytes: Long = 0) {
    val lengths = LongArray(files.size) { files[it].length() }
    var total = lengths.sum() + minOf(pinnedBytes, quotaBytes / 2)
    if (total <= quotaBytes) return
    val modified = LongArray(files.size) { files[it].lastModified() }
    for (i in files.indices.sortedBy { modified[it] }) {
      if (total <= quotaBytes) break
      total -= lengths[i]
      files[i].delete()
    }
  }
}

/**
 * Runs [prune] off the caller's thread, coalescing bursts into at most one queued pass.
 *
 * Body sinks close on whichever thread the app reads the response on (often the HTTP client's
 * worker); a directory scan there would delay the inspected app. The flag is cleared before a
 * pass starts so that a body closed during that pass still gets its own follow-up pass.
 */
internal class BodyPruneScheduler(
  private val executor: Executor,
  private val prune: () -> Unit,
) {
  private val queued = AtomicBoolean(false)

  fun request() {
    if (!queued.compareAndSet(false, true)) return
    executor.execute {
      queued.set(false)
      prune()
    }
  }
}
