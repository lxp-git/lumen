# Lumen

Android debug agent. UI is Chrome DevTools (`chrome://inspect`), not a desktop app.

OkHttp and logcat are recorded from **process start**. Attach later and Network / Console still show that history.

```kotlin
plugins {
  id("com.android.application")
  id("io.github.lxp-git.lumen") version "0.2.1"
}
```

Debug variants only. No `Lumen.initialize`.

## Quick start

1. Debug it the same way you debug an [Android WebView](https://developer.chrome.com/docs/devtools/remote-debugging/webviews#open_a_webview_in_devtools): USB debugging on, desktop Chrome → `chrome://inspect/#devices` → inspect `lumen://<package>`.

2. Once per USB session, from the **app** repo root (copy `scripts/lumen-proxy.sh` from this repo if needed):

   ```bash
   LUMEN_PACKAGE=com.example.app ./scripts/lumen-proxy.sh start
   ```

   As long as USB adb stays connected, restarting the app does **not** drop the inspect window — do not click Inspect again.

3. If something looks broken, it is probably a Lumen bug — or Chrome itself: Inspect not clickable, `chrome://inspect` not listing the process yet. Wait a few seconds; Chrome is just slow to refresh.

## Supported

| Chrome | Android |
|---|---|
| Network | OkHttp HTTP (this process, including traffic from before inspect) |
| Network → Messages | OkHttp WebSocket; Socket.IO `transport=websocket` and OkHttp `transport=polling` |
| Console | logcat (`V`–`E`), paged; 7-day archive |
| Elements | Classic View tree |
| Network → Fetch / Local Overrides | Mock OkHttp (`src/debug/assets/lumen-mocks/*.json` also works without DevTools) |
| Application → IndexedDB | SharedPreferences / DataStore / MMKV (view + delete) |
| Application → Local Storage | Same KV, editable; keys are `Engine / store / key` |

Not captured: HttpURLConnection, Cronet, Socket.IO polling that does not go through OkHttp. Page screencast is a no-op. Release / non-debuggable variants are not packaged.

### KV in Application

| Android | IndexedDB | Local Storage key |
|---|---|---|
| SharedPreferences (`shared_prefs/*.xml`) | database `SharedPreferences`, one object store per file | `SharedPreferences / <file> / <key>` |
| Preferences DataStore (`files/datastore/*.preferences_pb`) | database `DataStore` | `DataStore / <name> / <key>` |
| Proto DataStore (other `*.pb`) | DataStore object store, one `(proto)` JSON dump (read-only) | same |
| MMKV (`files/mmkv/<mmapId>`, if the host has `com.tencent.mmkv`) | database `MMKV` | `MMKV / <id> / <key>` |

IndexedDB is the grouped view (per-store clear). Local Storage is the editable table (double-click, Delete); types are preserved (bool/int/long/float/string/string-set). Combined-origin Clear All and IndexedDB Delete database are disabled so they cannot wipe every engine at once — delete a row, or clear one store under IndexedDB. SharedPreferences live-updates via the Android listener; DataStore/MMKV rows refresh on a 1s poll while Local Storage is open. DataStore writes go through the live actor when found. MMKV is opened by reflection — Lumen does not depend on the MMKV AAR.

## License

MIT
