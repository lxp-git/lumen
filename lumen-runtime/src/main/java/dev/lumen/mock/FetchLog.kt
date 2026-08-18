package dev.lumen.mock

import dev.lumen.common.LogRedirector

/** Fetch diagnostics. Silent unless `lumen { debugLogs = true }`. */
internal object FetchLog {
  private const val TAG = "LumenFetch"

  fun i(message: String) = LogRedirector.i(TAG, message)

  fun w(message: String) = LogRedirector.w(TAG, message)
}
