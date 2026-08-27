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

1. Install a **debug** APK and launch the app.
2. Chrome → `chrome://inspect/#devices` → inspect the process (`lumen://<package>`).
3. Network, Console, Elements, Application work as in a web inspect session.

Traffic and logcat from **before** inspect are replayed for this process (about 200 HTTP rows; WebSocket frames up to the per-socket cap).

If you **force-stop or kill** the app, Chrome’s in-window **Reconnect** reloads an empty frontend. Close that window and inspect again — or keep the same window with the sidecar below.

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

### Keep DevTools across process restarts (optional)

`chrome://inspect` attaches to `@lumen_<process>_devtools_remote`. That socket dies with the app. An adb-shell sidecar (uid 2000, not root) can hold the socket so the DevTools window never drops. After the process is back, the sidecar replays `Network.enable` / `Log.enable`; this process’s archive is pushed into the same Network / Console panels, then live rows continue.

```bash
# rebuild the debug APK with this Lumen, launch it, then:
LUMEN_PACKAGE=com.flow.mobile ANDROID_SERIAL=<serial> ./scripts/lumen-proxy.sh start
```

Then inspect as usual. Force-stop or relaunch: the window stays. New HTTP and logcat from the new process show up in the same panels (this process’s replay window — about 200 HTTP rows, one Console page — plus live traffic). Rows already drawn from the previous process stay until you clear the panel.

`start` is a no-op if the sidecar is already listening. `restart` / `stop` / unplugging USB kill that process and Chrome drops the window.

```bash
./scripts/lumen-proxy.sh status
./scripts/lumen-proxy.sh stop
./scripts/lumen-proxy.sh restart   # drops any open inspect window
```

USB / adb must stay connected. Apps built with this Lumen hand the socket over live — no restart. Only an app built with an older Lumen (no `yieldInspectSocket`) is force-stopped once so the sidecar can bind, then relaunched.

While the sidecar holds the socket, the app serves CDP on `127.0.0.1:18789` (loopback only; adb shell cannot reach app-owned abstract sockets under SELinux). If another process owns that port, `start` aborts with `error: loopback bind failed` and the app keeps the inspect socket — plain `chrome://inspect` keeps working, just without restart survival. Commands sent while the process is down are queued and replayed once it is back.

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
| DevTools **Reconnect** after the process dies | Close the window and inspect again. Or run `./scripts/lumen-proxy.sh start` first so the same window stays up |
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
