package dev.lumen.store

import android.content.Context
import dev.lumen.LumenConfig

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

  fun start() {
    logs.ensureStarted()
    network.pruneRetention()
  }
}
