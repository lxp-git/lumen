package dev.lumen.store

import android.content.Context
import dev.lumen.LumenConfig
import kotlin.concurrent.thread

/**
 * App-local source of truth. Capture pipelines only append here; CDP domains subscribe
 * and replay. Constructed once by [dev.lumen.LumenAgent].
 */
class EventStore(
  context: Context,
  val config: LumenConfig,
) {
  val logs = LogArchive(context, config)
  val network = NetworkArchive(context, config)

  /** Called on the main thread during app start: anything that walks the disk runs async. */
  fun start() {
    logs.ensureStarted()
    thread(name = "lumen-retention", isDaemon = true) { network.pruneRetention() }
  }
}
