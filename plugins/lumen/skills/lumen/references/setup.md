# Setting up Lumen

## Requirements

- An Android **application** module using the Android Gradle Plugin's new variant API
  (AGP 8.x and 9.x are known to work), JDK 17+.
- OkHttp 4.x or 5.x for network capture (Retrofit, Coil, Socket.IO-Java and most SDKs ride
  on it). minSdk 21+ (Lumen 0.3.0 itself needs 24; later versions accept 21).
- A device or emulator reachable with `adb`.

## Gradle

Plugin id `io.github.lxp-git.lumen`, published to the Gradle Plugin Portal. The plugin pulls
`io.github.lxp-git:lumen-runtime` and `lumen-okhttp` from Maven Central, so both
`gradlePluginPortal()` (plugins) and `mavenCentral()` (dependencies) must be reachable — most
projects already have them.

Version catalog:

```toml
[versions]
lumen = "0.3.0"

[plugins]
lumen = { id = "io.github.lxp-git.lumen", version.ref = "lumen" }
```

```kotlin
// app/build.gradle.kts
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.lumen)
}
```

Groovy: `id 'io.github.lxp-git.lumen' version '0.3.0'`.

Apply it to the app module only. It instruments OkHttp wherever the client is built,
including library modules and third-party SDKs. Do **not** add `lumen-runtime` /
`lumen-okhttp` as dependencies yourself, and do not call any Lumen API: there is none to call.

What happens per variant:

- Debuggable variants: the agent is added and starts from a ContentProvider before
  `Application.onCreate`; `OkHttpClient.Builder.build()` gets the Lumen interceptor;
  `OkHttpClient.newWebSocket` listeners are wrapped.
- Non-debuggable variants: nothing. The release APK contains no Lumen classes or providers.

## Options

All optional. Defaults shown.

```kotlin
lumen {
  enabled = true             // false: skip Lumen entirely (e.g. for a CI flavor)
  debugOnly = true           // false: also non-debuggable variants (not recommended)
  injectOkHttp = true        // weave the interceptor into OkHttpClient.Builder.build()
  retentionDays = 7          // logcat archive and network history on disk
  networkBodyQuotaMb = 512   // network history on disk (bodies, earlier launches), oldest dropped first
  networkReplayLimit = 0     // exchanges replayed when DevTools attaches, newest kept; 0 = the whole launch
  logPageSize = 1000         // log lines replayed when DevTools attaches
  wsMaxFrames = 2500         // frames kept per WebSocket for late attach
  wsMaxFrameChars = 16384    // characters kept per frame
  mockEnabled = true         // serve rules from assets/lumen-mocks and runtime rules
  mockRecordOverrides = false // persist DevTools "Override content" as offline rules
  debugFab = true            // notification with log-segment / export actions
  debugLogs = false          // Lumen's own diagnostics in logcat (tags Lumen*)
}
```

When DevTools attaches, the whole launch streams into the Network panel at about 1,400
exchanges per second (measured on a 2019 phone) while the other panels load. Set
`networkReplayLimit` for apps that make tens of thousands of requests per launch;
`lumen.py net --session` still reads the whole launch.

## Mock rules

Rules are JSON files in `src/debug/assets/lumen-mocks/` (debug-only, so they never ship).
The first matching rule answers the request without touching the network:

```json
{
  "urlContains": "api.example.com/v1/profile",
  "method": "GET",
  "status": 200,
  "delayMs": 300,
  "headers": { "Content-Type": "application/json" },
  "body": "{\"name\":\"Ada\",\"plan\":\"pro\"}"
}
```

Match on exactly one of `urlEquals`, `urlGlob` (`*` any run, `?` one character, `\` escapes)
or `urlContains`. `method` is optional (any method when absent). Runtime rules added with
`lumen.py mock add` or `Lumen.addMockRule` take precedence and live until the process dies.
Recorded DevTools overrides (with `mockRecordOverrides = true`) are stored under the app's
`files/lumen/mocks/` and survive restarts; `lumen.py mock clear --source recorded` removes them.

## Chrome DevTools

`chrome://inspect/#devices` → inspect `lumen://<process>`. Network, Console and
Application → Local Storage / IndexedDB work like for a web page. To keep the inspect window
across app restarts, run the adb sidecar once per USB session (from the Lumen repo):

```bash
LUMEN_PACKAGE=com.example.app ./scripts/lumen-proxy.sh start
```

## adb without Chrome or Python

The agent also answers `adb shell content call` (shell and root only):

```bash
adb shell content call --uri content://<applicationId>.lumen-adb --method getStatus
adb shell content call --uri content://<applicationId>.lumen-adb --method listMockRules
adb shell content call --uri content://<applicationId>.lumen-adb --method clearMockRules --arg runtime
adb shell content call --uri content://<applicationId>.lumen-adb --method listLogSegments
```

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `lumen.py targets` lists nothing | App not running, release variant, or plugin not applied to the app module. Check `adb shell cat /proc/net/unix \| grep lumen_`. |
| Several targets | Another app or process also embeds Lumen; pass `-p <applicationId>` (process names like `pkg:remote` match their package). |
| Requests missing from `net` | Not OkHttp (HttpURLConnection, Cronet, WebView), or a different process. `net` shows what memory holds (the newest 1000 exchanges, plus unfinished ones); after a restart, or for older ones, use `sessions` then `net --session ID`. |
| `kv set` fails | Only existing keys can be edited and the value must parse as the stored type. Proto DataStore is read-only. |
| DataStore edit seems ignored | The app caches the value in memory; reopen the screen or restart. |
| Mock not used | Rule order: runtime and recorded rules first, then assets in file order. `lumen.py mock list`; check `method` and the URL form actually requested (`net`). |
| Manifest merge error about minSdk | Lumen 0.3.0 requires minSdk 24; releases after it accept 21. |
| `Inspect` in Chrome does nothing | Chrome refreshes `chrome://inspect` slowly; wait a few seconds or reconnect USB. |
