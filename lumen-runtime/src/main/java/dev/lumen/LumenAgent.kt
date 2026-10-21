package dev.lumen

import android.app.Application
import android.content.Context
import dev.lumen.common.LogRedirector
import dev.lumen.common.LogUtil
import dev.lumen.init.DevtoolsEarlyBind
import dev.lumen.inspector.ChromeDevtoolsServer
import dev.lumen.inspector.database.SqliteDatabaseDriver
import dev.lumen.inspector.elements.Document
import dev.lumen.inspector.elements.android.ActivityTracker
import dev.lumen.inspector.elements.android.AndroidDocumentProviderFactory
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain
import dev.lumen.inspector.protocol.module.Browser
import dev.lumen.inspector.protocol.module.CSS
import dev.lumen.inspector.protocol.module.Console
import dev.lumen.inspector.protocol.module.DOM
import dev.lumen.inspector.protocol.module.DOMStorage
import dev.lumen.inspector.protocol.module.Database
import dev.lumen.inspector.protocol.module.Fetch
import dev.lumen.inspector.protocol.module.IndexedDB
import dev.lumen.inspector.protocol.module.IO
import dev.lumen.inspector.protocol.module.Log
import dev.lumen.inspector.protocol.module.Network
import dev.lumen.inspector.protocol.module.Page
import dev.lumen.inspector.protocol.module.Runtime
import dev.lumen.inspector.protocol.module.Storage
import dev.lumen.inspector.protocol.module.Target
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
  var config: LumenConfig = LumenConfig.DEFAULT
    private set

  @Volatile
  var store: EventStore? = null
    private set

  @Volatile
  var mockEngine: MockEngine? = null
    private set

  @JvmStatic
  fun start(context: Context, config: LumenConfig) {
    if (!started.compareAndSet(false, true)) {
      return
    }
    val app = context.applicationContext as Application
    this.config = config
    LogRedirector.setEnabled(config.debugLogsEnabled)

    // Socket is usually already bound by LumenSocketProvider. Attach CDP
    // after EventStore exists; 101 upgrade does not wait for this.
    val deferred = DevtoolsEarlyBind.bind(app)

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

    ActivityTracker.get().beginTrackingIfPossible(app)

    deferred.attach(ChromeDevtoolsServer(buildModules(app, eventStore, engine)))

    if (config.debugFabEnabled) {
      LumenDebugFab.install(app, eventStore)
    }

    LogUtil.i("Lumen agent started (retentionDays=%d)", config.retentionDays)
  }

  @JvmStatic
  fun isStarted(): Boolean = started.get()

  @JvmStatic
  fun requireStore(): EventStore =
    store ?: throw IllegalStateException("LumenAgent not started")

  /**
   * The CDP domains Chrome talks to. Anything not listed is stub-acknowledged by
   * [dev.lumen.inspector.MethodDispatcher], which keeps the frontend handshake moving.
   */
  private fun buildModules(
    app: Application,
    eventStore: EventStore,
    engine: MockEngine,
  ): List<ChromeDevtoolsDomain> {
    val document = Document(AndroidDocumentProviderFactory(app, Collections.emptyList()))
    return listOf(
      Browser(),
      Console(),
      DOM(document),
      CSS(document),
      DOMStorage(app),
      IndexedDB(app),
      Log(eventStore),
      Network(eventStore),
      Fetch(engine, eventStore),
      IO(engine),
      dev.lumen.inspector.protocol.module.Lumen(eventStore, engine),
      Page(),
      Runtime(),
      Storage(),
      Target(),
      Database(SqliteDatabaseDriver(app), app.packageName),
    )
  }
}
