package dev.lumen.init

import android.content.Context
import dev.lumen.inspector.DeferredEndpoint
import dev.lumen.inspector.DevtoolsSocketHandler
import dev.lumen.server.AddressNameHelper
import dev.lumen.server.LocalSocketServer
import dev.lumen.server.ProtocolDetectingSocketHandler

/**
 * Binds `@lumen_*_devtools_remote` before [dev.lumen.LumenAgent] class-loads.
 * Chrome inspect's ADB HttpUpgrade is ~1s; the socket must exist first.
 */
object DevtoolsEarlyBind {
  @Volatile
  private var endpoint: DeferredEndpoint? = null

  @JvmStatic
  @Synchronized
  fun bind(context: Context): DeferredEndpoint {
    endpoint?.let { return it }
    val deferred = DeferredEndpoint()
    val devtools = DevtoolsSocketHandler(context.applicationContext, deferred)
    val socketHandler = ProtocolDetectingSocketHandler(context.applicationContext)
    socketHandler.addHandler(
      ProtocolDetectingSocketHandler.AlwaysMatchMatcher(),
      devtools,
    )
    LocalSocketServer(
      "lumen",
      AddressNameHelper.createCustomAddress("_devtools_remote"),
      socketHandler,
    ).bindAndStartAccepting()
    endpoint = deferred
    return deferred
  }

  @JvmStatic
  fun get(): DeferredEndpoint? = endpoint
}
