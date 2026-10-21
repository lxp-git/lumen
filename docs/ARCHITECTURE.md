# How Lumen works

Lumen is three published artifacts and one script:

| Artifact | Role |
|---|---|
| `io.github.lxp-git.lumen` Gradle plugin (`lumen-gradle-plugin/`) | Adds the runtime to debuggable variants, writes the `lumen { }` DSL into generated resources, and ASM-weaves OkHttp. |
| `lumen-okhttp` (AAR) | `LumenInterceptor`, the WebSocket listener/socket wrappers, and the `LumenOkHttp` hooks the weave calls. |
| `lumen-runtime` (AAR) | The agent: capture stores, mock engine, CDP server and domains. |
| `plugins/lumen/skills/lumen/scripts/lumen.py` | A CDP client over adb for terminals and AI agents. |

The app contributes nothing but the plugin line.

## Build time

`LumenPlugin` runs `onVariants` for application and library modules. For each debuggable
variant (unless `debugOnly = false`) it:

1. adds `lumen-okhttp` (which brings `lumen-runtime`) to `<variant>Implementation`;
2. registers `GenerateLumenConfigTask`, whose `values/lumen_config.xml` overrides the
   runtime's defaults, so `LumenConfig.from(context)` sees the DSL;
3. instruments `okhttp3.OkHttpClient$Builder.build()` to call `LumenOkHttp.install(builder)`
   first, and `OkHttpClient.newWebSocket(...)` to wrap the listener on entry and the socket on
   exit. The scope is `ALL`, so clients built in library modules and SDKs are covered.

Release variants are not touched at all.

## Process start

Manifest-merged providers run before `Application.onCreate`, in `initOrder`:

1. `LumenSocketProvider` binds the abstract socket `@lumen_<process>_devtools_remote`
   (`DevtoolsEarlyBind`). Chrome's adb `HttpUpgrade` has about a second to succeed, so the
   socket exists before anything heavy loads; a `DeferredEndpoint` accepts the WebSocket and
   parks messages until the CDP modules attach.
2. `LumenInitProvider` starts `LumenAgent`: the `EventStore` (logcat pump + network archive),
   the `MockEngine` (asset rules, recorded overrides), activity tracking for the Elements tree,
   and the `ChromeDevtoolsServer` with its domains. Disk housekeeping runs on background
   threads.
3. `LumenAdbProvider` is exported for `adb shell content call` (shell / root uids only).

Capture is always on from here; DevTools peers only subscribe. That is why traffic and logs
from before Chrome attached are visible.

## Capture

```
OkHttp call ──▶ LumenInterceptor (application interceptor)
                 ├─ MockEngine: asset / runtime / recorded rule? ──▶ synthetic response
                 ├─ Fetch.enable active? pause until Chrome fulfils / continues / fails
                 ├─ NetworkArchive.put / update        (metadata, in memory + session jsonl)
                 ├─ CaptureInputStream ─▶ body file    (raw bytes, capped per body + quota)
                 └─ NetworkEventReporter ─▶ live CDP Network.* events, if a peer listens

logcat -v threadtime ──▶ LogArchive (pending batch ─▶ spill-NNNNNN.jsonl segments)
                          └─▶ Log domain packs lines into Log.entryAdded for live peers
```

- **Bodies** are written once, exactly as the app read them. `BodyCodec` inflates
  app-negotiated gzip / deflate and picks text vs base64 when a body is *read*
  (`Network.getResponseBody`, HAR export, `Lumen.getRequest`), never on the HTTP thread.
- **Metadata** lines for the session file are built under the archive lock and written by
  `JsonlAppender`'s thread in batches.
- **WebSockets**: `LumenWebSocketListener` / `LumenWebSocket` report frames;
  `WsFrameLog` keeps the newest frames per socket (Engine.IO ping/pong evicted first) for late
  attach. `WsFrameNetworkRows` adds one searchable XHR row per socket, because Chrome's
  Network search skips WebSocket rows.
- **Socket.IO polling** (`EngineIoPolling`) decodes Engine.IO packets from polling requests
  and promotes them onto a synthetic WebSocket row per session.
- **Key-value stores** are read on demand (`inspector/kv`): SharedPreferences through the
  framework; DataStore by decoding its file, with edits sent through the app's live
  `DataStore` instance (found by reflection) so its in-memory state stays consistent; MMKV
  by reflection, without depending on the MMKV AAR.

## CDP

`LocalSocketServer` accepts a connection, `SecureSocketHandler` requires the peer to hold
`android.permission.DUMP` (adb shell and adbd do, other apps do not), and `LightHttpServer`
serves `/json*` discovery or upgrades to a WebSocket. `MethodDispatcher` maps
`Domain.method` to `@ChromeDevtoolsMethod` functions by class simple name and acknowledges
unknown methods with `{}` so Chrome's startup sequence never stalls.

Domains (`inspector/protocol/module`): `Network`, `Fetch` + `IO` (overrides / interception),
`Log`, `Console`, `Runtime`, `Page`, `Target`, `Browser`, `DOM` + `CSS` (Elements), `DOMStorage`
+ `IndexedDB` + `Storage` (key-value stores), `Database` (SQLite, scriptable only), and
`Lumen`, a custom domain for segment switching, export, mock rules and the compact queries the
CLI uses (`listRequests`, `getRequest`, `getLogs`).

When `scripts/lumen-proxy.sh` runs, an `app_process` sidecar (`DevtoolsProxy`) holds the
abstract socket for the whole USB session and forwards to the app's loopback port, so the
Chrome window survives app restarts.

## Where things live on the device

`files/lumen/` inside the app's private storage: `logs/spill-*.jsonl`,
`network/session-<pid>.jsonl`, `network/bodies/body-<requestId>`, `mocks/` (recorded
overrides), and `export-*` files from HAR / log export. Retention and the body quota are
enforced at startup and as bodies close.

## Code layout

```
lumen-runtime/src/main/java/dev/lumen/
  LumenAgent.kt, LumenConfig.kt   agent lifecycle and configuration
  init/      providers, early socket bind, loopback CDP for the sidecar
  store/     EventStore, NetworkArchive, LogArchive, BodyCodec, WsFrameLog
  mock/      MockEngine
  inspector/ CDP server, dispatcher, domains, Elements tree, KV access, network reporting
  server/, websocket/, json/   local socket + HTTP + WebSocket + JSON mapping (from Stetho)
  proxy/     adb sidecar
  ui/        debug notification and log-segment picker
```

Lumen grew out of [Stetho](https://github.com/facebook/stetho); the transport, JSON and
Elements layers keep its Java code. New code is Kotlin.
