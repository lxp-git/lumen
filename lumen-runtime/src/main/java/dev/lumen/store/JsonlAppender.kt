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
) {
  private val queue = LinkedBlockingQueue<String>()

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
    while (true) {
      try {
        batch.add(queue.take())
      } catch (_: InterruptedException) {
        return
      }
      queue.drainTo(batch)
      try {
        val w = writer ?: run {
          file.parentFile?.mkdirs() // the app may have wiped filesDir since the last batch
          BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
        }.also { writer = it }
        for (line in batch) {
          w.write(line)
          w.write('\n'.code)
        }
        w.flush()
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
