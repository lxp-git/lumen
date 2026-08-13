/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.stetho.inspector.console

import android.content.Context
import com.facebook.stetho.common.LogRedirector
import com.facebook.stetho.inspector.helper.ChromePeerManager
import com.facebook.stetho.inspector.helper.PeerRegistrationListener
import com.facebook.stetho.inspector.jsonrpc.JsonRpcPeer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Streams the process's logcat into the DevTools Console panel via `Log.entryAdded`.
 *
 * A single background reader is started as soon as Stetho initializes (not when DevTools
 * attaches), so log output emitted *before* the frontend connects is not lost: entries
 * accumulate in a small in-memory batch that is spilled to the app sandbox
 * (`cacheDir/stetho-logcat/`, JSONL) every [FLUSH_EVERY] lines.
 *
 * The spill is an archive, not a scratch buffer: numbered segment files roll over every
 * [SEGMENT_MAX_LINES] lines, survive process restarts (each run opens a fresh segment and
 * stamps a session-start marker), and are only pruned oldest-first once the directory
 * exceeds [MAX_TOTAL_BYTES]. Pull the directory with adb if you need history beyond what
 * the Console shows.
 *
 * When a peer sends `Log.enable`, the most recent [MAX_REPLAY_LINES] archived lines plus
 * the unflushed batch are replayed to it (the full archive would choke the frontend),
 * then live lines are broadcast as they arrive. (The `logcat` command itself also replays
 * logd's system-side buffer on start, which is how entries from before [ensureStarted]
 * are captured at all.)
 */
object LogcatForwarder {
  private const val TAG = "LogcatForwarder"
  private const val FLUSH_EVERY = 500
  private const val SEGMENT_MAX_LINES = 25_000
  private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
  private const val MAX_REPLAY_LINES = 50_000
  private const val RESTART_DELAY_MS = 3000L

  /**
   * Lines emitted by the forwarding path itself. Re-forwarding them can loop: a failed
   * send is logged, the log line is read back from logcat, forwarded, fails again, ...
   */
  private val SUPPRESSED_TAGS = setOf("ChromePeerManager", TAG)

  /**
   * `logcat -v threadtime -v epoch` line shape:
   * `1751871234.123  1234  5678 D SomeTag : message`
   */
  private val LINE_PATTERN =
      Regex("""^\s*(\d+)\.(\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFS])\s+(.*?)\s*: (.*)$""")

  private val SEGMENT_NAME_PATTERN = Regex("""spill-(\d+)\.jsonl""")

  private class Entry(val timestampMs: Double, val level: String, val text: String)

  /**
   * Guards [pending], the spill directory, and the current-segment state. The pump thread
   * must never call into [peerManager] while holding it: the peer-registration listener
   * takes this lock while ChromePeerManager's monitor is held, so the reverse order would
   * deadlock.
   */
  private val lock = Any()

  private val pending = ArrayList<Entry>()
  private var spillDir: File? = null
  private var currentSegmentIndex = 0
  private var currentSegmentLines = 0

  private val peerManager = ChromePeerManager().apply {
    setListener(object : PeerRegistrationListener {
      override fun onPeerRegistered(peer: JsonRpcPeer) = replayTo(peer)
      override fun onPeerUnregistered(peer: JsonRpcPeer) {}
    })
  }

  @Volatile private var started = false

  /** Timestamp given to lines that don't parse, so they sort next to their neighbors. */
  @Volatile private var lastTimestampMs = 0.0

  /** Idempotent; call at Stetho init so buffering begins before any frontend attaches. */
  fun ensureStarted(context: Context) {
    synchronized(this) {
      if (started) {
        return
      }
      started = true
    }
    val dir = File(context.cacheDir, "stetho-logcat")
    dir.mkdirs()
    synchronized(lock) {
      spillDir = dir
      // The archive persists across runs; each run appends a fresh segment so session
      // boundaries fall on file boundaries.
      currentSegmentIndex = (listSegmentsLocked().lastOrNull()?.let(::segmentIndex) ?: 0) + 1
      currentSegmentLines = 0
      pending.add(
          Entry(
              System.currentTimeMillis().toDouble(),
              "info",
              "─────── Stetho logcat session start (pid " + android.os.Process.myPid() +
                  ") ───────"))
    }
    thread(name = "Stetho-LogcatForwarder", isDaemon = true) { pumpForever() }
  }

  fun addPeer(peer: JsonRpcPeer) {
    peerManager.addPeer(peer)
  }

  fun removePeer(peer: JsonRpcPeer) {
    peerManager.removePeer(peer)
  }

  /** DevTools' explicit `Log.clear`: the user asked for the slate to be wiped. */
  fun clearBuffer() {
    synchronized(lock) {
      pending.clear()
      listSegmentsLocked().forEach { it.delete() }
      currentSegmentIndex++
      currentSegmentLines = 0
    }
  }

  private fun segmentFile(index: Int): File? =
      // Locale.ROOT: the default locale can render %d with non-ASCII digits, which would
      // break SEGMENT_NAME_PATTERN matching.
      spillDir?.let { File(it, "spill-%06d.jsonl".format(java.util.Locale.ROOT, index)) }

  private fun segmentIndex(file: File): Int =
      SEGMENT_NAME_PATTERN.matchEntire(file.name)?.groupValues?.get(1)?.toInt() ?: 0

  private fun listSegmentsLocked(): List<File> =
      (spillDir?.listFiles { f -> SEGMENT_NAME_PATTERN.matches(f.name) } ?: emptyArray())
          .sortedBy(::segmentIndex)

  private fun pumpForever() {
    while (true) {
      try {
        val process = ProcessBuilder("logcat", "-v", "threadtime", "-v", "epoch")
            .redirectErrorStream(true)
            .start()
        try {
          process.inputStream.bufferedReader().forEachLine { handleLine(it) }
        } finally {
          process.destroy()
        }
      } catch (e: IOException) {
        LogRedirector.e(TAG, "logcat pipe failed", e)
      }
      // EOF or failure (logd hiccup); back off and reattach.
      try {
        Thread.sleep(RESTART_DELAY_MS)
      } catch (e: InterruptedException) {
        return
      }
    }
  }

  private fun handleLine(line: String) {
    if (line.isEmpty() || line.startsWith("---------")) {
      // logd buffer separators ("--------- beginning of main")
      return
    }
    val entry = parse(line) ?: return
    synchronized(lock) {
      pending.add(entry)
      if (pending.size >= FLUSH_EVERY) {
        flushLocked()
      }
    }
    // Sent outside the lock — see the lock-ordering note on [lock].
    if (peerManager.hasRegisteredPeers()) {
      peerManager.sendNotificationToPeers("Log.entryAdded", entryAddedParams(entry))
    }
  }

  private fun flushLocked() {
    val file = segmentFile(currentSegmentIndex) ?: return
    try {
      file.appendText(buildString {
        for (entry in pending) {
          append(
              JSONArray()
                  .put(entry.timestampMs)
                  .put(entry.level)
                  .put(entry.text))
          append('\n')
        }
      })
      currentSegmentLines += pending.size
      pending.clear()
      if (currentSegmentLines >= SEGMENT_MAX_LINES) {
        currentSegmentIndex++
        currentSegmentLines = 0
        pruneLocked()
      }
    } catch (e: IOException) {
      // Disk unhappy (full?); drop the batch rather than grow the heap unboundedly.
      LogRedirector.e(TAG, "spill write failed, dropping " + pending.size + " lines", e)
      pending.clear()
    }
  }

  /** Oldest-first deletion once the archive exceeds [MAX_TOTAL_BYTES]. */
  private fun pruneLocked() {
    val segments = listSegmentsLocked()
    var total = segments.sumOf { it.length() }
    for (segment in segments) {
      if (total <= MAX_TOTAL_BYTES || segmentIndex(segment) == currentSegmentIndex) {
        break
      }
      total -= segment.length()
      segment.delete()
    }
  }

  private fun parse(line: String): Entry? {
    val match = LINE_PATTERN.matchEntire(line)
        ?: return Entry(lastTimestampMs, "info", line) // unknown shape — keep raw
    val (secs, fraction, pid, _, priority, tag, message) = match.destructured
    if (tag in SUPPRESSED_TAGS) {
      return null
    }
    val timestampMs = secs.toDouble() * 1000 + "0.$fraction".toDouble() * 1000
    lastTimestampMs = timestampMs
    val level = when (priority) {
      "W" -> "warning"
      "E", "F" -> "error"
      "I" -> "info"
      else -> "verbose" // V, D
    }
    return Entry(timestampMs, level, "$tag($pid): $message")
  }

  private fun entryAddedParams(entry: Entry): JSONObject =
      JSONObject().put(
          "entry",
          JSONObject()
              .put("source", "other")
              .put("level", entry.level)
              .put("text", entry.text)
              .put("timestamp", entry.timestampMs))

  private fun replayTo(peer: JsonRpcPeer) {
    // Snapshot under the lock, send outside it: pushing tens of thousands of frames can
    // take a while and must not stall the pump (logcat's pipe has finite buffering).
    val snapshot = synchronized(lock) {
      val tail = ArrayList<Entry>(pending)
      // Walk segments newest-first, prepending, until the replay quota is met; the rest
      // of the archive stays on disk.
      val chunks = ArrayList<List<Entry>>()
      var count = tail.size
      for (segment in listSegmentsLocked().asReversed()) {
        if (count >= MAX_REPLAY_LINES) {
          break
        }
        val chunk = readSegment(segment)
        chunks.add(chunk)
        count += chunk.size
      }
      chunks.asReversed().flatten() + tail
    }
    for (entry in snapshot.takeLast(MAX_REPLAY_LINES)) {
      peer.invokeMethod("Log.entryAdded", entryAddedParams(entry), null)
    }
  }

  private fun readSegment(file: File): List<Entry> {
    val out = ArrayList<Entry>()
    try {
      file.forEachLine { line ->
        try {
          val arr = JSONArray(line)
          out.add(Entry(arr.getDouble(0), arr.getString(1), arr.getString(2)))
        } catch (e: JSONException) {
          // Torn tail line from a mid-write crash; skip.
        }
      }
    } catch (e: IOException) {
      LogRedirector.e(TAG, "spill read failed", e)
    }
    return out
  }
}
