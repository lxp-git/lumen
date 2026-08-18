package dev.lumen.inspector.network

import android.os.Process
import java.util.concurrent.atomic.AtomicInteger

/**
 * Chrome keys Network rows by [requestId]. A new process used to restart at
 * `"0"`, so post-restart HTTP collided with the previous process's WebSocket
 * rows and never drew as XHR. Prefix with pid so ids stay unique for the
 * lifetime of a chrome://inspect window (and the sidecar).
 */
object NetworkRequestIds {
  private val prefix = Process.myPid().toString() + "."
  private val seq = AtomicInteger(0)

  @JvmStatic
  fun next(): String = prefix + seq.getAndIncrement()
}
