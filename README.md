# Lumen

Android debug agent. The UI is Chrome DevTools (`chrome://inspect`), not a desktop app — and
the same data is scriptable from a terminal or an AI coding agent.

OkHttp and logcat are recorded from **process start**. Attach later and Network / Console
still show that history.

```kotlin
plugins {
  id("com.android.application")
  id("io.github.lxp-git.lumen") version "0.3.0"
}
```

Debug variants only. No `Lumen.initialize`, no interceptor to add: the plugin wires the agent
in and weaves OkHttp. Release builds contain no Lumen code.

## Quick start

1. Debug it the same way you debug an
   [Android WebView](https://developer.chrome.com/docs/devtools/remote-debugging/webviews#open_a_webview_in_devtools):
   USB debugging on, desktop Chrome → `chrome://inspect/#devices` → inspect
   `lumen://<package>`.

2. Once per USB session, from the **app** repo root (copy `scripts/lumen-proxy.sh` from this
   repo if needed):

   ```bash
   LUMEN_PACKAGE=com.example.app ./scripts/lumen-proxy.sh start
   ```

   As long as USB adb stays connected, restarting the app does **not** drop the inspect
   window — do not click Inspect again.

3. If something looks broken, it is probably a Lumen bug — or Chrome itself: Inspect not
   clickable, `chrome://inspect` not listing the process yet. Wait a few seconds; Chrome is
   just slow to refresh.

## Supported

| Chrome | Android |
|---|---|
| Network | OkHttp HTTP (this process, including traffic from before inspect); gzip and binary bodies decoded on view |
| Network → Messages | OkHttp WebSocket; Socket.IO `transport=websocket` and OkHttp `transport=polling` |
| Console | logcat (`V`–`E`), paged; 7-day archive |
| Elements | Classic View tree |
| Network → Fetch / Local Overrides | Mock OkHttp (`src/debug/assets/lumen-mocks/*.json` also works without DevTools) |
| Application → IndexedDB | SharedPreferences / DataStore / MMKV (view + delete) |
| Application → Local Storage | Same KV, editable; keys are `Engine / store / key` |
| — (CLI / CDP only) | SQLite and Room databases (`Database.*`; Chrome no longer shows Web SQL) |

Not captured: HttpURLConnection, Cronet, WebView traffic, Socket.IO polling that does not go
through OkHttp. Page screencast is not supported. Jetpack Compose content shows up as a single
`ComposeView` in Elements.

Works with AGP 8 and 9, OkHttp 4 and 5. minSdk 24 for 0.3.0; the next release accepts 21.

### KV in Application

| Android | IndexedDB | Local Storage key |
|---|---|---|
| SharedPreferences (`shared_prefs/*.xml`) | database `SharedPreferences`, one object store per file | `SharedPreferences / <file> / <key>` |
| Preferences DataStore (`files/datastore/*.preferences_pb`) | database `DataStore` | `DataStore / <name> / <key>` |
| Proto DataStore (other `*.pb`) | DataStore object store, one `(proto)` JSON dump (read-only) | same |
| MMKV (`files/mmkv/<mmapId>`, if the host has `com.tencent.mmkv`) | database `MMKV` | `MMKV / <id> / <key>` |

IndexedDB is the grouped view (per-store clear). Local Storage is the editable table
(double-click, Delete); types are preserved (bool/int/long/float/string/string-set).
Combined-origin Clear All and IndexedDB Delete database are disabled so they cannot wipe every
engine at once — delete a row, or clear one store under IndexedDB. SharedPreferences
live-updates via the Android listener; DataStore/MMKV rows refresh on a 1s poll while Local
Storage is open. DataStore writes go through the app's live `DataStore` instance. MMKV is
opened by reflection — Lumen does not depend on the MMKV AAR.

### Mock rules

Drop JSON files in `src/debug/assets/lumen-mocks/`; matching OkHttp requests are answered
locally, with or without DevTools:

```json
{
  "urlContains": "api.example.com/v1/profile",
  "method": "GET",
  "status": 200,
  "delayMs": 300,
  "headers": { "Content-Type": "application/json" },
  "body": "{\"name\":\"Ada\"}"
}
```

Match with `urlEquals`, `urlGlob` (`*`, `?`) or `urlContains`. Chrome's *Override content*
works too, and with `lumen { mockRecordOverrides = true }` an override keeps answering after
Chrome disconnects. All options: [setup reference](plugins/lumen/skills/lumen/references/setup.md#options).

## Terminal and AI agents

[`lumen.py`](plugins/lumen/skills/lumen/scripts/lumen.py) speaks the same protocol over adb,
no Chrome needed (Python 3, standard library only):

```bash
L="python3 plugins/lumen/skills/lumen/scripts/lumen.py"
$L net --failed                          # HTTP >= 400 and transport errors
$L req 4242.17                           # headers, bodies, WebSocket frames
$L logs --level warning --since 10m
$L kv set "SharedPreferences / settings / onboarded" false
$L db sql app.db "SELECT * FROM users LIMIT 5"
$L mock add --url-contains /v1/orders --status 500 --body '{"error":"boom"}'
$L ui --grep Button
```

It ships as an Agent Skill so Claude Code can debug the running app itself. Install it as a
plugin:

```text
/plugin marketplace add lxp-git/lumen
/plugin install lumen@lumen
```

(Claude Code 2.1.275+: `/plugin install lumen --marketplace lxp-git/lumen`.) Or copy
`plugins/lumen/skills/lumen` to `~/.claude/skills/lumen`. Details:
[plugins/lumen](plugins/lumen/README.md).

## Examples

- [`lumen-sample`](lumen-sample) — one screen per feature: Retrofit/OkHttp (JSON, gzip, PNG,
  404, DNS failure), asset mocks, WebSocket, Socket.IO, logcat, SharedPreferences / DataStore
  / MMKV, Room. Every scenario also runs over adb:
  `adb shell am start -n dev.lumen.sample/.MainActivity --es scenario all`.
- [`examples/pokedex`](examples/pokedex) — Lumen added to
  [skydoves/Pokedex](https://github.com/skydoves/Pokedex) (Hilt, Room, Retrofit on OkHttp 5,
  AGP 9) with two plugin lines.

## Development

```bash
./gradlew :lumen-runtime:testDebugUnitTest :lumen-sample:installDebug
./scripts/publish-local.sh        # plugin + AARs to build/maven-repo for another project
```

How the pieces fit: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Contributing:
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT. Lumen started from [Stetho](https://github.com/facebook/stetho) (MIT, © Facebook, Inc.
and its affiliates).
