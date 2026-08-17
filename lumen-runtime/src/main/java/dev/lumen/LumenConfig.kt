package dev.lumen

import android.content.Context

/**
 * Runtime knobs for the Lumen agent.
 *
 * The Gradle plugin writes the same resource names into the host app so a
 * `lumen { retentionDays = 2 }` DSL actually reaches [from].
 */
data class LumenConfig(
  /** How long log / network metadata survive on disk. */
  val retentionDays: Int = 7,
  /** Console-safe page size when replaying logcat to DevTools. */
  val logPageSize: Int = 1_000,
  /** Soft cap on retained response bodies (bytes). Oldest bodies pruned first. */
  val networkBodyQuotaBytes: Long = 512L * 1024 * 1024,
  /** Max bytes stored per individual response body. */
  val maxBodyBytes: Long = 2L * 1024 * 1024,
  /** How many network exchanges to replay on Network.enable (current process). */
  val networkReplayLimit: Int = 200,
  /**
   * Max archived WebSocket frames kept per connection for late-connect replay.
   * Sized for Socket.IO LLM streams (many deltas + Engine.IO ping/pong).
   */
  val maxWsFramesPerSocket: Int = 2_500,
  /** Max characters stored per WS frame payload (text or base64). */
  val maxWsFrameChars: Int = 16_384,
  /** Enable CDP Fetch bridging (Chrome Network Override / block). */
  val mockEnabled: Boolean = true,
  /**
   * Persist DevTools overrides (`Fetch.fulfillRequest` with a body) under
   * `filesDir/lumen/mocks/` so they replay with no DevTools attached and
   * across restarts. Off by default so an override can't silently freeze an
   * API after the session ends.
   */
  val mockRecordOverrides: Boolean = false,
  /** Show the in-app debug FAB for segment switch / HAR export. */
  val debugFabEnabled: Boolean = true,
  /**
   * Write Lumen's own diagnostics to logcat (`LumenCDP`, `LumenWS`, `stetho`, …).
   * Off by default — a debug agent should not spam the host's logcat.
   */
  val debugLogsEnabled: Boolean = false,
  /** Local mock rules directory under assets (optional). */
  val mockAssetsDir: String = "lumen-mocks",
) {
  companion object {
    @JvmField
    val DEFAULT = LumenConfig()

    @JvmStatic
    fun from(context: Context): LumenConfig {
      val res = context.applicationContext.resources
      val pkg = context.packageName
      fun intRes(name: String, default: Int): Int {
        val id = res.getIdentifier(name, "integer", pkg)
        return if (id != 0) res.getInteger(id) else default
      }
      fun boolRes(name: String, default: Boolean): Boolean {
        val id = res.getIdentifier(name, "bool", pkg)
        return if (id != 0) res.getBoolean(id) else default
      }
      val quotaMb = intRes("lumen_network_body_quota_mb", 512).coerceAtLeast(1)
      return LumenConfig(
        retentionDays = intRes("lumen_retention_days", 7).coerceAtLeast(1),
        logPageSize = intRes("lumen_log_page_size", 1_000).coerceAtLeast(100),
        networkBodyQuotaBytes = quotaMb * 1024L * 1024L,
        maxWsFramesPerSocket = intRes("lumen_ws_max_frames", 2_500).coerceAtLeast(50),
        maxWsFrameChars = intRes("lumen_ws_max_frame_chars", 16_384).coerceAtLeast(256),
        mockEnabled = boolRes("lumen_mock_enabled", true),
        mockRecordOverrides = boolRes("lumen_mock_record_overrides", false),
        debugFabEnabled = boolRes("lumen_debug_fab", true),
        debugLogsEnabled = boolRes("lumen_debug_logs", false),
      )
    }
  }
}
