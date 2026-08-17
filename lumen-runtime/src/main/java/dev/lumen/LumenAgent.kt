package dev.lumen

import android.app.Application
import android.content.Context
import android.os.Build
import dev.lumen.common.LogRedirector
import dev.lumen.common.LogUtil
import dev.lumen.inspector.ChromeDevtoolsServer
import dev.lumen.inspector.database.DefaultDatabaseConnectionProvider
import dev.lumen.inspector.database.DefaultDatabaseFilesProvider
import dev.lumen.inspector.database.SqliteDatabaseDriver
import dev.lumen.inspector.elements.Document
import dev.lumen.inspector.elements.android.ActivityTracker
import dev.lumen.inspector.elements.android.AndroidDocumentConstants
import dev.lumen.inspector.elements.android.AndroidDocumentProviderFactory
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain
import dev.lumen.inspector.protocol.module.Browser
import dev.lumen.inspector.protocol.module.CSS
import dev.lumen.inspector.protocol.module.Console
import dev.lumen.inspector.protocol.module.DOM
import dev.lumen.inspector.protocol.module.DOMStorage
import dev.lumen.inspector.protocol.module.Database
import dev.lumen.inspector.protocol.module.DatabaseConstants
import dev.lumen.inspector.protocol.module.Debugger
import dev.lumen.inspector.protocol.module.Fetch
import dev.lumen.inspector.protocol.module.HeapProfiler
import dev.lumen.inspector.protocol.module.IO
import dev.lumen.inspector.protocol.module.Inspector
import dev.lumen.inspector.protocol.module.Log
import dev.lumen.inspector.protocol.module.Network
import dev.lumen.inspector.protocol.module.Page
import dev.lumen.inspector.protocol.module.Profiler
import dev.lumen.inspector.protocol.module.Runtime
import dev.lumen.inspector.protocol.module.Storage
import dev.lumen.inspector.protocol.module.Target
import dev.lumen.inspector.protocol.module.Worker
import dev.lumen.inspector.runtime.RhinoDetectingRuntimeReplFactory
import dev.lumen.mock.MockEngine
import dev.lumen.store.EventStore
import dev.lumen.ui.LumenDebugFab
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process singleton that owns the EventStore, mock engine, and CDP local-socket server.
 * Started automatically by [dev.lumen.init.LumenInitProvider] — host apps need no glue.
 */
object LumenAgent {
  private val started = AtomicBoolean(false)

  @Volatile
  private var appContext: Context? = null

  @Volatile
  var config: LumenConfig = LumenConfig.DEFAULT
    private set

  @Volatile
  var store: EventStore? = null
    private set

  @Volatile
  var mockEngine: MockEngine? = null
    private set

  @JvmStatic
  @JvmOverloads
  fun start(context: Context, config: LumenConfig = LumenConfig.DEFAULT) {
    if (!started.compareAndSet(false, true)) {
      return
    }
    val app = context.applicationContext
    appContext = app
    this.config = config
    LogRedirector.setEnabled(config.debugLogsEnabled)

    // Socket is usually already bound by LumenSocketProvider. Attach CDP
    // after EventStore exists; 101 upgrade does not wait for this.
    val deferred = dev.lumen.init.DevtoolsEarlyBind.bind(app)

    val eventStore = EventStore(app, config)
    eventStore.start()
    store = eventStore

    val engine = MockEngine(config)
    engine.loadAssetRules(app)
    engine.initRecordedRules(
      File(app.filesDir, "lumen/mocks"),
      recordByDefault = config.mockRecordOverrides,
    )
    mockEngine = engine

    val application = app as? Application
    if (application != null) {
      val tracking = ActivityTracker.get().beginTrackingIfPossible(application)
      if (!tracking) {
        LogUtil.w("Automatic activity tracking not available on this API level")
      }
    }

    deferred.attach(ChromeDevtoolsServer(buildModules(app, eventStore, engine)))

    if (config.debugFabEnabled && application != null) {
      LumenDebugFab.install(application, eventStore)
    }

    LogUtil.i("Lumen agent started (retentionDays=%d)", config.retentionDays)
  }

  @JvmStatic
  fun isStarted(): Boolean = started.get()

  @JvmStatic
  fun requireStore(): EventStore =
    store ?: throw IllegalStateException("LumenAgent not started")

  @JvmStatic
  fun requireMockEngine(): MockEngine =
    mockEngine ?: throw IllegalStateException("LumenAgent not started")

  private fun buildModules(
    context: Context,
    eventStore: EventStore,
    engine: MockEngine,
  ): Iterable<ChromeDevtoolsDomain> {
    val modules = ArrayList<ChromeDevtoolsDomain>()
    modules.add(Browser())
    modules.add(Console())
    modules.add(Debugger())

    if (Build.VERSION.SDK_INT >= AndroidDocumentConstants.MIN_API_LEVEL) {
      val app = context.applicationContext as Application
      val document = Document(
        AndroidDocumentProviderFactory(app, Collections.emptyList()),
      )
      modules.add(DOM(document))
      modules.add(CSS(document))
    }

    modules.add(DOMStorage(context))
    modules.add(HeapProfiler())
    modules.add(Inspector())
    modules.add(Log(eventStore))
    modules.add(Network(context, eventStore))
    modules.add(Fetch(engine, eventStore))
    modules.add(IO(engine))
    modules.add(dev.lumen.inspector.protocol.module.Lumen(eventStore, engine))
    modules.add(Page(context))
    modules.add(Profiler())
    modules.add(Runtime(RhinoDetectingRuntimeReplFactory(context)))
    modules.add(Storage())
    modules.add(Target())
    modules.add(Worker())

    if (Build.VERSION.SDK_INT >= DatabaseConstants.MIN_API_LEVEL) {
      val database = Database()
      database.add(
        SqliteDatabaseDriver(
          context,
          DefaultDatabaseFilesProvider(context),
          DefaultDatabaseConnectionProvider(),
        ),
      )
      modules.add(database)
    }
    return modules
  }
}
