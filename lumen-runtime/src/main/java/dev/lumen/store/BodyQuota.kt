package dev.lumen.store

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the spilled response bodies under [dev.lumen.LumenConfig.networkBodyQuotaBytes].
 *
 * The directory can hold thousands of files, so every size and timestamp is read once per pass
 * (a comparator calling [File.lastModified] costs n·log n stat calls) and the oldest-first sort
 * only happens when the quota is actually exceeded.
 */
internal object BodyQuota {
  fun prune(dir: File, quotaBytes: Long) {
    val files = dir.listFiles() ?: return
    val lengths = LongArray(files.size) { files[it].length() }
    var total = lengths.sum()
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
 * Body sinks close on the HTTP client's worker thread, before the response reaches the app; a
 * directory scan there delays every callback of the inspected app. The flag is cleared before a
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
