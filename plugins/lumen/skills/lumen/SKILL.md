---
name: lumen
description: Debug a running Android debug build through Lumen over adb — list and read captured OkHttp / Retrofit / WebSocket / Socket.IO traffic, search logcat history, read and edit SharedPreferences / DataStore / MMKV, query SQLite / Room, dump the View hierarchy, and serve mock responses, all without opening Chrome. Also adds Lumen to an Android Gradle project. Use when investigating API calls, response bodies, app logs, local storage or UI state on a device or emulator, or when asked to set up Lumen.
allowed-tools: Bash(python3 ${CLAUDE_SKILL_DIR}/scripts/lumen.py *)
---

# Lumen

[Lumen](https://github.com/lxp-git/lumen) is a debug-only agent inside an Android app. It
records OkHttp traffic and logcat from process start and serves the Chrome DevTools Protocol
over adb. `scripts/lumen.py` (Python 3, standard library only) talks to it directly, so you
get the same data as Chrome's Network / Console / Application / Elements panels as text.

Run it as:

```bash
python3 ${CLAUDE_SKILL_DIR}/scripts/lumen.py <command> [options]
```

Call it exactly like that, one invocation per Bash command: no shell variables, loops or
pipes around it (use `--grep`, `--limit`, `--json` instead of `grep` / `head` / `jq`). The
skill pre-approves only that direct form, so anything wrapped needs a permission prompt.

Global options go before the command: `-p <applicationId>` picks the app when several Lumen
processes run (or set `LUMEN_PACKAGE`), `-s <serial>` picks the device (`ANDROID_SERIAL`),
`--json` prints machine-readable output — use it whenever you will parse the result.

## First: find the target

```bash
python3 ${CLAUDE_SKILL_DIR}/scripts/lumen.py targets
```

Each line is `serial  process  @socket`. If nothing is listed:

1. Is a device connected? `adb devices`.
2. Is the app running? `adb shell monkey -p <applicationId> -c android.intent.category.LAUNCHER 1`.
3. Is it a **debug** build with the Lumen plugin? Release builds never contain Lumen.
   If the project does not use Lumen yet, see [Set up](#set-up-lumen-in-a-project).

## Commands

| Goal | Command |
|---|---|
| Agent status, version, counters | `status` |
| Recent HTTP / WS exchanges (newest last) | `net [--url SUBSTR] [--method M] [--status N] [--failed] [--limit N] [--session ID]` |
| One exchange: headers, bodies, WS frames | `req <id> [--body-chars N] [--frames N]` |
| Raw decoded response body | `body <id> [-o file]` |
| Earlier launches kept on disk | `sessions` |
| Search logcat (survives restarts, 7 days) | `logs [--grep S] [--exclude S] [--level warning] [--since 10m] [--limit N]` |
| Stream new log lines | `logs --follow --seconds 30 [--grep S]` |
| Key-value stores | `kv list [--grep S]`, `kv get KEY`, `kv set KEY VALUE`, `kv rm KEY` |
| SQLite / Room | `db list`, `db tables DB`, `db sql DB "SELECT …"` |
| View hierarchy | `ui [--grep S] [--depth N]` |
| Mock rules | `mock list`, `mock add …`, `mock rm ID`, `mock clear [--source runtime]` |
| Export traffic | `har [-o file] [--no-bodies] [--session ID]` |
| Anything else in CDP | `cdp Domain.method '{"json":"params"}' [--events SECONDS]` |

`--grep` / `--exclude` are case-insensitive substrings (no regex, no `-i`). Each logcat line is
one entry, so a stack trace spans many: `logs --level error --grep Exception` finds the
headline, `--exclude "	at "` (a tab, then `at `) drops the frames.

KV keys look like `SharedPreferences / <file> / <key>`, `DataStore / <name> / <key>`,
`MMKV / <mmapId> / <key>`. `kv set` only edits existing keys and keeps the stored type
(writing `abc` to an int fails with an error). Request ids look like `<pid>.<n>`.

## Recipes

`lumen.py` below is short for `python3 ${CLAUDE_SKILL_DIR}/scripts/lumen.py`.

**A request fails or returns the wrong data**

```bash
lumen.py net --failed                 # HTTP >= 400 and transport errors
lumen.py net --url /v1/orders         # or narrow by URL
lumen.py req 4242.17                  # headers, request body, response body
lumen.py logs --since 5m --level warning --grep orders
```

Check the request headers (auth, content type), the request body, the status and the
response body before reading app code. `durationMs` and `failed` show timeouts and DNS / TLS
errors.

**The app restarted (or crashed) since**

```bash
lumen.py sessions                                  # one row per launch, newest last
lumen.py net --session session-4242-1792722723387 --failed
lumen.py req 4242.17                               # any launch; --session if the pid repeats
```

**Try a server response without changing the server**

```bash
lumen.py mock add --url-contains /v1/orders --status 500 --body '{"error":"boom"}'
# make the app repeat the call (navigate, pull to refresh, relaunch), then:
lumen.py net --url /v1/orders         # the row is marked "mock"
lumen.py mock clear --source runtime  # always clean up
```

`--url-glob` takes `*` / `?` patterns, `--delay MS` simulates latency, `--header 'K: V'`
repeats. Runtime rules live until the process dies. Rules that should persist belong in the
app's `src/debug/assets/lumen-mocks/*.json` (format in [references/setup.md](references/setup.md)).

**Inspect or change local state**

```bash
lumen.py kv list --grep onboard
lumen.py kv set "SharedPreferences / settings / onboarded" false
lumen.py db sql app.db "SELECT * FROM users ORDER BY updatedAt DESC LIMIT 5"
```

Restart the screen or app afterwards if it caches the value in memory.

**See what is on screen**

```bash
lumen.py ui --grep Button          # class, id and text per view
```

Lumen walks the classic View tree. Jetpack Compose content appears as one `ComposeView`.

**Long-lived connections**: `req <id>` on a WebSocket row lists its frames (`send` / `recv`);
Socket.IO sessions, including the polling transport, appear as one WebSocket row each.

## Rules

- Lumen only exists in debuggable variants. Never suggest it for release builds or add the
  runtime libraries as plain dependencies; the Gradle plugin handles variants.
- Mutating commands (`kv set/rm`, `db sql` with writes, `mock add`) change the user's app
  state. Say what you changed, and undo runtime mocks with `mock clear --source runtime`.
- `net` lists what memory holds of the current launch (the newest 1000 exchanges, plus
  unfinished ones). Everything else stays on disk for `retentionDays` (default 7, 512 MB
  cap): `net --session` covers a whole launch, and `req <id>` finds any exchange. Across restarts, WebSocket frame
  payloads are lost. Launches recorded by older Lumen versions keep metadata only. Logs are
  archived on disk across restarts too.
- Several apps can embed Lumen at once; pass `-p <applicationId>` rather than guessing.
- Response bodies are capped (2 MB by default) and may be truncated. Request bodies over
  64 KB, of unknown length, or one-shot / streaming are not captured.
- Not captured: HttpURLConnection, Cronet, WebView traffic, Socket.IO polling that does not go
  through OkHttp.
- Humans can see the same data in Chrome: `chrome://inspect/#devices` → `lumen://<process>`.

## Set up Lumen in a project

Add the plugin to the **application** module (Kotlin DSL shown):

```kotlin
plugins {
  id("com.android.application")
  id("io.github.lxp-git.lumen") version "0.3.0"
}
```

That is the whole integration: no `Application` code, no interceptor. Debug variants get the
agent, an OkHttp weave that adds the interceptor to every `OkHttpClient.Builder.build()` and
wraps WebSocket listeners, and the defaults below. Build and run the debug variant, then
`lumen.py targets`.

Options, mock rule files, version-catalog setup and troubleshooting are in
[references/setup.md](references/setup.md).
