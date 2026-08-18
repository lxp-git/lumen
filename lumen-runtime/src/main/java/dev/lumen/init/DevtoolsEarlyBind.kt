package dev.lumen.init

import android.content.Context
import dev.lumen.common.LogUtil
import dev.lumen.inspector.DeferredEndpoint
import dev.lumen.inspector.DevtoolsSocketHandler
import dev.lumen.server.AddressNameHelper
import dev.lumen.server.LocalSocketServer
import dev.lumen.server.ProtocolDetectingSocketHandler
import java.io.IOException

/**
 * Binds `@lumen_*_devtools_remote` before [dev.lumen.LumenAgent] class-loads.
 * Chrome inspect's ADB HttpUpgrade is ~1s; the socket must exist first.
 *
 * [yieldInspectSocket] hands that name to `scripts/lumen-proxy.sh` and
 * serves CDP on 127.0.0.1:[LoopbackCdpServer.PORT] instead.
 */
object DevtoolsEarlyBind {
  private const val BIND_RETRY_COUNT = 3
  private const val BIND_RETRY_DELAY_MS = 1000L

  @Volatile
  private var endpoint: DeferredEndpoint? = null

  @Volatile
  private var appContext: Context? = null

  @Volatile
  private var inspectHandler: DevtoolsSocketHandler? = null

  @Volatile
  private var inspectServer: LocalSocketServer? = null

  /** True between [yieldInspectSocket] and [resumeInspectSocket]; blocks rebind retries. */
  @Volatile
  private var yieldedToSidecar = false

  @JvmStatic
  @Synchronized
  fun bind(context: Context): DeferredEndpoint {
    endpoint?.let { return it }
    val app = context.applicationContext
    appContext = app
    val deferred = DeferredEndpoint()
    val devtools = DevtoolsSocketHandler(app, deferred)
    inspectHandler = devtools
    try {
      // No inline retry: bind() runs on the main thread during app start and
      // must not sleep. Transient conflicts are retried on a background thread.
      val server = newInspectServer(app, devtools)
      server.bindAndStartAccepting(false)
      inspectServer = server
    } catch (e: IOException) {
      LogUtil.i("Inspect socket busy; serving CDP on 127.0.0.1:%d", LoopbackCdpServer.PORT)
      if (!LoopbackCdpServer.start(devtools)) {
        LogUtil.w("Loopback CDP bind failed too; DevTools unreachable until inspect rebind")
      }
      retryInspectBindAsync()
    }
    endpoint = deferred
    return deferred
  }

  @JvmStatic
  fun get(): DeferredEndpoint? = endpoint

  @JvmStatic
  @Synchronized
  fun yieldInspectSocket(): Boolean {
    val handler = inspectHandler ?: return false
    // Bring the loopback listener up before giving away the inspect socket so
    // a loopback bind failure leaves normal chrome://inspect working.
    if (!LoopbackCdpServer.start(handler)) {
      LogUtil.w("Refusing to yield inspect socket: loopback CDP bind failed")
      return false
    }
    yieldedToSidecar = true
    val server = inspectServer
    inspectServer = null
    if (server != null) {
      LogUtil.i("Yielding inspect socket to adb sidecar")
      server.stop()
    }
    return true
  }

  @JvmStatic
  @Synchronized
  fun resumeInspectSocket(): Boolean {
    yieldedToSidecar = false
    if (tryBindInspect()) return true
    LogUtil.w("Could not rebind inspect socket")
    return false
  }

  @JvmStatic
  fun inspectBound(): Boolean = inspectServer != null

  /** Single bind attempt; call while holding the [DevtoolsEarlyBind] lock. */
  private fun tryBindInspect(): Boolean {
    if (inspectServer != null) return true
    val context = appContext ?: return false
    val handler = inspectHandler ?: return false
    val server = newInspectServer(context, handler)
    return try {
      server.bindAndStartAccepting(false)
      inspectServer = server
      true
    } catch (e: IOException) {
      false
    }
  }

  /**
   * Reclaims the inspect socket after a transient "address in use" at startup
   * (e.g. the previous process instance is still dying) without blocking the
   * main thread. Gives up once the socket was yielded to the adb sidecar.
   */
  private fun retryInspectBindAsync() {
    Thread({
      repeat(BIND_RETRY_COUNT) {
        try {
          Thread.sleep(BIND_RETRY_DELAY_MS)
        } catch (_: InterruptedException) {
          return@Thread
        }
        synchronized(this) {
          if (yieldedToSidecar) return@Thread
          if (tryBindInspect()) {
            LogUtil.i("Inspect socket bound after retry")
            return@Thread
          }
        }
      }
      LogUtil.w("Inspect socket still busy after %d retries", BIND_RETRY_COUNT)
    }, "lumen-inspect-rebind").apply {
      isDaemon = true
      start()
    }
  }

  private fun newInspectServer(
    context: Context,
    handler: DevtoolsSocketHandler,
  ): LocalSocketServer {
    val socketHandler = ProtocolDetectingSocketHandler(context)
    socketHandler.addHandler(
      ProtocolDetectingSocketHandler.AlwaysMatchMatcher(),
      handler,
    )
    return LocalSocketServer("lumen", inspectAddress(), socketHandler)
  }

  private fun inspectAddress(): String =
    AddressNameHelper.createCustomAddress("_devtools_remote")
}
