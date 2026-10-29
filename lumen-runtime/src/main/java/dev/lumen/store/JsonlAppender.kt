package dev.lumen.store

import dev.lumen.common.LogRedirector
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue

/**
 * Appends lines to [file] on a daemon thread. Callers on the HTTP path only enqueue; the
 * writer drains whatever is queued, writes it in one go and flushes, so a burst of requests
 * costs one write instead of an open/append/close per lifecycle update.
 */
internal class JsonlAppender(
  private val file: File,
  threadName: String,
  /** Runs on the writer thread once a file deleted under it has started over. */
  private val onRestart: () -> Unit = {},
) {
  private val queue = LinkedBlockingQueue<String>()

  /** Bumped when the file is deleted and started over, so readers drop offsets into the old one. */
  @Volatile
  var generation = 0
    private set

  init {
    Thread({ drainForever() }, threadName).apply {
      isDaemon = true
      start()
    }
  }

  fun append(line: String) {
    queue.offer(line)
  }

  private fun drainForever() {
    val batch = ArrayList<String>()
    var writer: BufferedWriter? = null
    var opened = false
    while (true) {
      try {
        batch.add(queue.take())
      } catch (_: InterruptedException) {
        return
      }
      queue.drainTo(batch)
      if (writer != null && !file.exists()) {
        // The app wiped filesDir: writes would land in the deleted file and be lost.
        try {
          writer?.close()
        } catch (_: IOException) {
        }
        writer = null
      }
      var restarted = false
      try {
        val w = writer ?: run {
          restarted = opened && !file.exists()
          if (restarted) generation++
          file.parentFile?.mkdirs() // the app may have wiped filesDir since the last batch
          BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
        }.also {
          writer = it
          opened = true
        }
        for (line in batch) {
          w.write(line)
          w.write('\n'.code)
        }
        w.flush()
        if (restarted) onRestart()
      } catch (e: IOException) {
        LogRedirector.w(TAG, "append to ${file.name} failed, dropping ${batch.size} lines", e)
        try {
          writer?.close()
        } catch (_: IOException) {
        }
        writer = null
      }
      batch.clear()
    }
  }

  private companion object {
    const val TAG = "NetworkArchive"
  }
}
