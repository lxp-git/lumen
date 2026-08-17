package dev.lumen.mock

/** Always-on Fetch diagnostics. Swallows missing android.util.Log on JVM unit tests. */
internal object FetchLog {
  private const val TAG = "LumenFetch"

  fun i(message: String) {
    try {
      android.util.Log.i(TAG, message)
    } catch (_: Throwable) {
      // android.util.Log is not mocked on the JVM.
    }
  }

  fun w(message: String) {
    try {
      android.util.Log.w(TAG, message)
    } catch (_: Throwable) {
      // android.util.Log is not mocked on the JVM.
    }
  }
}
