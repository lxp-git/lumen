# Lumen

**Lumen** is an Android **debug-only** agent. The UI is Chrome DevTools (`chrome://inspect`), not a desktop app.

The debug process records OkHttp traffic and logcat from **process start**. Attach later and Network / Console still show that process’s history.

It started as a Facebook Stetho fork. New work lives in `lumen-*`. You do not call `Lumen.initialize` / `Stetho.initialize`.

| | Stetho | Lumen |
|---|---|---|
| When capture starts | After DevTools connects | From process start (EventStore) |
| Wiring | Manual `initialize` + interceptor | Gradle plugin + ContentProvider + ASM |
| Network mock | Observe only | Chrome Fetch / Local Overrides (recordable for offline replay) + `assets/lumen-mocks` |
| Console | Live only | 7-day logcat archive, paged |

Current version: **0.2.0**.

## Requirements

- Android Gradle Plugin 8.9+ / Gradle 8.14+
- JDK 17, `minSdk` 24, OkHttp 4.x (`okhttp3.OkHttpClient`; 3.x hosts are untested — recording largely shares the same ABI, but the mock/fulfill path uses 4.x-only APIs)
- USB debugging, desktop Chrome, `chrome://inspect/#devices`

## Add to an app

Do **not** add `implementation("io.github.lxp-git:…")` or a custom Maven URL. Apply the plugin:

```kotlin
plugins {
  id("com.android.application")
  id("io.github.lxp-git.lumen") version "0.2.0"
}

// optional — Groovy can assign (`retentionDays = 7`)
lumen {
  retentionDays.set(7)
  debugFab.set(true)
  debugLogs.set(false)
}
```

Multi-module: `id("io.github.lxp-git.lumen") version "0.2.0" apply false` on the root `plugins {}` block, then `id("io.github.lxp-git.lumen")` on the app module.

The plugin adds `io.github.lxp-git:lumen-okhttp` (and `lumen-runtime`) only to **debuggable** variants, weaves `OkHttpClient.Builder.build()` / `newWebSocket`, and merges the init ContentProviders into the debug manifest.

Release stays clean unless you set `debugOnly.set(false)`, mark the release type `debuggable true`, or add the libraries yourself.

`id("io.github.lxp-git.lumen")` 0.2.0 is on the Gradle Plugin Portal (first-time namespace approval can take a few days). After that, stock `gradlePluginPortal()` + `mavenCentral()` is enough. The plugin id uses the GitHub namespace because Plugin Portal requires proof of `lumen.dev` for `dev.lumen`.

## Use it

1. Install a **debug** APK (Lumen 0.1.2+) and launch the app.
2. Keep the inspect window across restarts (recommended for daily work) — see below — **or** skip to step 3 and re-click Inspect after every process death.
3. Chrome → `chrome://inspect/#devices` → inspect the process (`lumen://<package>`).
4. Network, Console, Elements, Application work as in a web inspect session.

Traffic and logcat from **before** inspect are replayed for this process (about 200 HTTP rows; WebSocket frames up to the per-socket cap).

Chrome Console keeps one page. Flip logcat pages with the in-app **Log pages** control, the notification, or:

```bash
./scripts/lumen-logs                  # list (* = active)
./scripts/lumen-logs live
./scripts/lumen-logs seg-3
# or:
adb shell content call --uri content://<pkg>.lumen-adb --method listLogSegments
adb shell am start -n <pkg>/dev.lumen.ui.LumenLogSegmentsActivity
```

`LUMEN_PACKAGE` defaults to `dev.lumen.sample`. Use `ANDROID_SERIAL` / `LUMEN_USER` on multi-device or work-profile phones.

### Keep DevTools across app restarts

Chrome’s in-window **Reconnect** is `location.reload()` of a socket that died with the process, so the frontend comes back empty. `chrome://inspect` talks to `@lumen_<process>_devtools_remote`; that name belongs to the app unless a sidecar holds it.

`scripts/lumen-proxy.sh` starts an **adb-shell** process (uid 2000, no root) that owns the inspect socket for the USB session. After a force-stop / Studio Run, the same DevTools window stays up: the sidecar replays `Network.enable` / `Log.enable`, this process’s archive is pushed into Network / Console, then live rows continue. Rows already drawn from the previous process stay until you clear the panel.

Daily loop:

```bash
# 1. debug APK installed and launched (USB debugging on)
LUMEN_PACKAGE=com.example.app ./scripts/lumen-proxy.sh start

# 2. chrome://inspect → inspect once (lumen://com.example.app)

# 3. restart the app as usual — do not click Inspect again
```

Host apps can copy `scripts/lumen-proxy.sh` and default `LUMEN_PACKAGE` to their applicationId so colleagues only run `./scripts/lumen-proxy.sh start`.

```bash
./scripts/lumen-proxy.sh start     # no-op if already listening (will not drop the window)
./scripts/lumen-proxy.sh status
./scripts/lumen-proxy.sh stop      # uninstall, or switch to an APK older than 0.1.2
./scripts/lumen-proxy.sh restart   # replaces the sidecar; drops any open inspect window
```

`start` once per USB plug. Unplugging USB, `stop`, `restart`, or rebooting the phone kills the sidecar and Chrome drops the window — plug back in, `start`, inspect once.

A 0.1.2+ app yields the inspect socket live (no extra force-stop). An older APK is force-stopped once so the sidecar can bind, then relaunched. If `start` prints `unknown method`, rebuild with Lumen 0.1.2+.

While the sidecar holds the socket, the app serves CDP on `127.0.0.1:18789` (loopback only; SELinux blocks adb shell from app-owned abstract sockets). If that port is taken, `start` aborts with `error: loopback bind failed` and leaves plain `chrome://inspect` working, just without restart survival. CDP commands sent while the process is down are queued and replayed when it is back.

Do not leave the sidecar running after uninstall: Chrome will still list `lumen://<package>` against an empty shell. `stop` first.

### Chrome DevTools MCP (AI agents)

[chrome-devtools-mcp](https://developer.chrome.com/blog/chrome-devtools-mcp-debug-your-browser-session) `--autoConnect` attaches to **desktop Chrome** (your tabs, including `chrome://inspect`). The project's [Android recipe](https://github.com/ChromeDevTools/chrome-devtools-mcp/blob/main/docs/debugging-android.md) forwards `localabstract:chrome_devtools_remote` — that is **Chrome the app on the phone**, not Lumen.

Lumen's inspect socket (`@lumen_<pkg>_devtools_remote`) is what `chrome://inspect` uses. Pointing the MCP at it (`adb forward` + `--browserUrl` / `--wsEndpoint=ws://127.0.0.1:<port>/devtools/browser`) completes the WebSocket upgrade, then Puppeteer dies: Lumen stub-acks `Target.getBrowserContexts` with `{}`, and Puppeteer throws `contextIds is not iterable`. MCP tools also have no Fetch / Local Overrides / `Lumen.addMockRule` surface.

To drive Lumen without clicking Inspect, speak CDP on the forwarded socket (or the sidecar loopback `127.0.0.1:18789`): `Network.enable`, `Log.enable`, `Fetch.*`, `Lumen.addMockRule`. There is no dedicated Lumen MCP yet.

## What v1 covers

**Logcat** — every priority (`V`/`D`/`I`/`W`/`E`) is written under `filesDir/lumen/logs/` (default 7-day retention) and replayed into Console one page at a time. Android `D`/`V` show as Console Info because Chrome hides CDP `verbose` by default. Lumen’s own tags (`LumenFetch`, `LumenCDP`, `LumenWS`, `lumen`, …) stay out of logcat unless `debugLogs.set(true)`.

**Network** — OkHttp requests/responses go to `filesDir/lumen/network/`. `Network.enable` replays the **current process**. Older processes stay in `session-*.jsonl` and come out via HAR export, not mixed into the live panel.

**WebSocket** — OkHttp `newWebSocket` (including Socket.IO with `transports=websocket`). Frames are archived and replayed on late attach. Default 2500 frames/socket, 16 384 chars/frame. Over the cap, Engine.IO ping/pong (`2` / `3`) are evicted first. Live Messages are not filtered.

**Mock**

- Chrome Network → Local Overrides / Fetch (CDP `Fetch.*`)
- `src/debug/assets/lumen-mocks/*.json` (or `src/main/assets/…` on a debug-only sample):

```json
{
  "urlContains": "httpbin.org/uuid",
  "status": 200,
  "headers": { "Content-Type": "application/json" },
  "body": "{\"mock\":true}"
}
```

`urlGlob`, `method`, and `delayMs` are also accepted. Rules apply without DevTools attached.

**Recorded overrides** (`mockRecordOverrides.set(true)`, or `Lumen.setMockRecording` at runtime) — while DevTools is attached, every override Chrome fulfils (Local Overrides or manual `Fetch.fulfillRequest` with a body) is persisted under `filesDir/lumen/mocks/` and replayed on exact URL + method. Mocks authored in the Chrome UI keep working after DevTools disconnects and across process restarts. Off by default so an override can't silently freeze an API; inspect with `Lumen.listMockRules`, delete one with `Lumen.removeMockRule` (also removes the files).

Done mocking? Clear everything on-device without Chrome:

```bash
adb shell content call --uri content://<pkg>.lumen-adb --method listMockRules
adb shell content call --uri content://<pkg>.lumen-adb --method clearMockRules --arg recorded   # or omit the arg for all
```

`Lumen.clearMockRules {"source":"recorded"}` does the same over CDP.

**Export** — HAR and a log bundle from the notification / FAB. `exportHar` can target a past `session-*`. Files land in the app’s private files dir (the toast / `Lumen.exportHar` result shows the full path); on a debug build fetch them with:

```bash
adb exec-out run-as <pkg> cat files/lumen/network/export-….har > export.har
```

**Custom CDP** (`Lumen.*`) — `getStatus`, log segments, `listNetworkSessions`, `exportHar`, `exportLogs`, mock-rule add/list/remove, `setMockRecording`. Stock Chrome panels do not call these.

Only **OkHttp** is woven. HttpURLConnection, Cronet, and Socket.IO still on HTTP polling do not show as first-class Network / WebSocket rows.

## What v1 does not do

| Topic | What happens |
|---|---|
| DevTools **Reconnect** after the process dies | Empty frontend. Run `./scripts/lumen-proxy.sh start` **before** the first Inspect so the same window survives restarts |
| Chrome DevTools MCP | Desktop Chrome (or Chrome-on-Android). Not Lumen's inspect socket; no mock-override tool |
| Network panel **Clear** | Same as inspecting a web page: cleared rows (including a still-open socket) do not come back. New HTTP / a **new** WebSocket will. On-disk archive is unchanged |
| Messages looks like one row overwritten by `3` | Short grid + autoscroll to the latest Engine.IO pong. Scroll up for history |
| Page screencast | `Page.startScreencast` is a no-op |
| Chrome extension | Not shipped |
| Non-OkHttp stacks | Not captured |
| Release / non-debuggable variants | Not packaged (default `debugOnly`) |

## Roadmap

Feasible with the current architecture, not built yet:

- **HttpURLConnection / Cronet capture** — the EventStore + CDP replay path is transport-agnostic; only the OkHttp interceptor exists today.
- **Live screencast** — implement `Page.startScreencast` with `PixelCopy` frames so `chrome://inspect` shows the device screen next to Network / Console.
- **Socket.IO long-polling decode** — polling traffic already lands in HTTP rows; Engine.IO payloads could be decoded into WebSocket-style Messages like the websocket transport.
- **Compose-aware Elements** — Elements walks the classic View tree; mapping Compose semantics nodes would make it useful on Compose-only screens.
- **Mock rule editor in DevTools** — rules already have add/list/remove CDP methods (`Lumen.*`); a small DevTools-side panel or extension could manage them without adb.

## `lumen { }`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch |
| `debugOnly` | `true` | Only `debuggable` variants |
| `injectOkHttp` | `true` | ASM-weave OkHttp |
| `retentionDays` | `7` | Log + network file retention |
| `logPageSize` | `1000` | Console replay page |
| `networkBodyQuotaMb` | `512` | Soft cap on stored bodies |
| `wsMaxFrames` | `2500` | Archived frames per socket |
| `wsMaxFrameChars` | `16384` | Max stored chars per frame |
| `mockEnabled` | `true` | Fetch + asset mocks |
| `mockRecordOverrides` | `false` | Persist DevTools overrides for offline replay |
| `debugFab` | `true` | In-app / notification picker |
| `debugLogs` | `false` | Agent diagnostics in logcat |

## Sample

```bash
export ANDROID_HOME=…
./gradlew :lumen-sample:assembleDebug
```

APK: `lumen-sample/build/outputs/apk/debug/lumen-sample-debug.apk`

1. Install and launch — status reads `Lumen agent started=true`.
2. Fire HTTP / spam logcat / open the echo WebSocket **before** Chrome.
3. Inspect — Network and Console show that history.
4. **Request mock target URL** — body comes from `assets/lumen-mocks/uuid.json`.
5. Notification **Export HAR** → `adb pull` → Chrome → Import HAR.

The sample Application class has no Lumen calls.

## Modules

| Module | Role |
|---|---|
| `lumen-gradle-plugin` | `id("io.github.lxp-git.lumen")` — debug deps, generated `lumen_*` resources, ASM |
| `lumen-runtime` | Agent, EventStore, CDP (`lumen_<process>[_<userId>]_devtools_remote`) |
| `lumen-okhttp` | Interceptor + WebSocket wrap (`api` → runtime) |
| `lumen-sample` | Zero-glue demo |
| `stetho` / `stetho-okhttp3` | Legacy coordinates for existing `includeBuild` consumers |

Java/Kotlin package: `dev.lumen.*` · Maven: `io.github.lxp-git:lumen-*` · plugin id: `io.github.lxp-git.lumen`

## Legacy Stetho

`:stetho` and `:stetho-okhttp3` stay so older `includeBuild` hosts keep compiling. New work goes in `lumen-*` only.

## Publishing

Plugin → Gradle Plugin Portal (`id("io.github.lxp-git.lumen")`). Libraries → Maven Central (`io.github.lxp-git:lumen-okhttp` / `lumen-runtime`). Credentials stay in `~/.gradle/gradle.properties`, not in git.

```bash
export ANDROID_HOME=…
./gradlew :lumen-runtime:publishAndReleaseToMavenCentral \
          :lumen-okhttp:publishAndReleaseToMavenCentral \
          --no-configuration-cache
./gradlew -p lumen-gradle-plugin publishPlugins
```

## License

MIT (same as upstream Stetho).
