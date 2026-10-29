# Changelog

## Unreleased

**Compatibility**
- minSdk 21 (was 24): apps with minSdk 21–23 could not apply the plugin at all.
- `Lumen.listNetworkSessions`: `entryCount` (lines) is now `requestCount`, plus `startedAtMs`.
- The network body quota (`networkBodyQuotaMb`) now also covers earlier launches' session
  files.
- Verified with AGP 9.4 / Gradle 9.8 / OkHttp 5.5 (skydoves/Pokedex) besides AGP 8.9.

**Fixes**
- Preferences DataStore edits from DevTools work on DataStore 1.1+ and with the common
  `by preferencesDataStore(...)` delegate; they always failed before.
- App log tags that start with `Lumen` are no longer hidden from Console.
- Images and app-negotiated gzip/deflate bodies display correctly in Network and HAR.
- Lumen no longer prints stack traces to `System.err` when debug logs are off.
- Failed Local Storage deletes are reported; databases created after DevTools attached appear.
- Two launches that got the same pid no longer share a session file or overwrite each other's
  bodies.
- Failed requests replayed to DevTools keep their own duration instead of stretching to the
  moment DevTools attached.
- Requests still in flight (long downloads, streams, open WebSockets) are no longer dropped
  from memory by later traffic. That lost their completion and, for a WebSocket, its frames
  for late attach.
- After the app clears its files, network metadata goes to a new session file instead of the
  deleted one.

**Performance**
- Each response body is written once (the DevTools-only second copy and its gunzip thread are
  gone), and unread bodies are no longer drained on close.
- WebSocket frames: no full transcript rebuild per frame while DevTools is attached; frame
  archive without copy-on-write.
- Session metadata is batched on a background thread instead of open/append/close per
  update on the OkHttp thread; mock globs are compiled once; startup pruning is off the main
  thread.

**Additions**
- Agent Skill + Claude Code plugin marketplace (`/plugin marketplace add lxp-git/lumen`) with
  `lumen.py`, a dependency-free CDP client over adb.
- `Lumen.listRequests`, `Lumen.getRequest`, `Lumen.getLogs` CDP methods.
- Network history on disk: request headers and bodies, response headers and the rest of each
  record now outlive memory and app restarts (response bodies already did, but nothing could
  read them back). `lumen.py sessions`, `--session` on `net` / `req` / `body` / `har`;
  `req <id>`, Chrome's Response tab and HAR export reach earlier launches.
- DevTools replays the whole launch when it attaches (was the newest 200 exchanges);
  `lumen { networkReplayLimit = N }` caps it.
- `scripts/publish-local.sh` for testing unreleased builds in other projects.
- New `lumen-sample` covering every feature, drivable over adb; `examples/pokedex`.

**Removed**
- Stetho leftovers: dumpapp, the `Lumen.initialize*` builder API, the database driver plugin
  API, Rhino console, screencast, and the upstream `stetho*` modules and scripts.

## 0.3.0 — 2026-09-16
- Application panel: SharedPreferences, Preferences/Proto DataStore and MMKV in IndexedDB and
  Local Storage (editable, type-preserving).
- Socket.IO polling packets promoted onto a synthetic WebSocket row.
- Consecutive logcat lines packed into fewer Console events.
- JSON-RPC replies flushed ahead of coalesced writes.
- Response-body pruning moved off the HTTP worker thread (after release, #2).

## 0.2.1 — 2026-08-28
- Replayed JSON typed as XHR so Chrome Search and Local Overrides apply.

## 0.2.0 — 2026-08-27
- Inspect writes flushed before OkHttp proceeds; WebSocket frames indexed for Network search.

## 0.1.2 — 2026-08-18
- `scripts/lumen-proxy.sh`: adb sidecar keeps `chrome://inspect` attached across app restarts.
- Every logcat priority archived; D/V shown in Console.
- WebSocket frame writes on a dedicated thread; pid-prefixed Network and Fetch ids.
- DevTools overrides recordable as persistent offline rules; bulk mock-rule clearing.

## 0.1.1 — 2026-08-17
- Gradle plugin published as `io.github.lxp-git.lumen`.
- Response-stage Fetch interception with `IO.read` body streaming.

## 0.1.0 — 2026-08-13
- First release: debug-only agent whose UI is Chrome DevTools, auto-started from a
  ContentProvider, with OkHttp and logcat recorded from process start.
