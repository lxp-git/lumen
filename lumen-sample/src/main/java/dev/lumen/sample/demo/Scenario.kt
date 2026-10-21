package dev.lumen.sample.demo

/**
 * One card per Lumen feature. Each can also be fired over adb, which is how scripts, CI and
 * AI agents drive the sample without touching the screen:
 *
 * ```
 * adb shell am start -n dev.lumen.sample/.MainActivity --es scenario http
 * adb shell am start -n dev.lumen.sample/.MainActivity --es scenario all
 * ```
 */
enum class Scenario(val title: String, val summary: String, val where: String) {
  HTTP(
    "HTTP — Retrofit + OkHttp",
    "PokeAPI JSON, a POST body, app-negotiated gzip, a PNG, a 404 and a DNS failure.",
    "Network panel · lumen.py net / req",
  ),
  MOCK(
    "Mock rules",
    "Requests answered by src/debug/assets/lumen-mocks — no server, no DevTools needed.",
    "Network (mocked rows) · lumen.py mock list",
  ),
  WEBSOCKET(
    "WebSocket — OkHttp",
    "Streams chat deltas plus one binary frame to an echo server.",
    "Network → Messages · lumen.py req <id>",
  ),
  SOCKET_IO(
    "Socket.IO",
    "Connects over the websocket and polling transports and emits events.",
    "Network → Messages (one row per session)",
  ),
  LOGCAT(
    "Logcat",
    "Every priority, a caught exception with stack trace, a 200-line burst.",
    "Console · lumen.py logs",
  ),
  STORAGE(
    "SharedPreferences · DataStore · MMKV",
    "Seeds all three engines with every supported value type.",
    "Application → Local Storage / IndexedDB · lumen.py kv",
  ),
  DATABASE(
    "Room (SQLite)",
    "Saves PokeAPI results as favourites in sample.db.",
    "lumen.py db sql sample.db \"SELECT * FROM favorites\"",
  ),
  ALL("Run everything", "Every scenario above, one after another.", "All of the above");

  val key: String get() = name.lowercase()

  companion object {
    fun parse(value: String): Scenario? =
      entries.firstOrNull { it.key == value.trim().lowercase().replace('-', '_') }
  }
}
