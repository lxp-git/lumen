package dev.lumen.sample.demo

import android.util.Log

/** Logcat is captured from process start; these show up in Console and `lumen.py logs`. */
object LogcatDemo {
  private const val TAG = "LumenSample"

  fun run(report: Report) {
    Log.v(TAG, "verbose: cache lookup key=user:1024")
    Log.d(TAG, "debug: rendering 20 rows in 4ms")
    Log.i(TAG, "info: user signed in")
    Log.w(TAG, "warning: token expires in 30s, refreshing")
    try {
      checkout(cartSize = 0)
    } catch (e: IllegalStateException) {
      Log.e(TAG, "error: checkout failed", e)
    }
    for (i in 1..200) Log.d("LumenSampleBurst", "frame $i rendered in ${(i * 7) % 17}ms")
    Log.i(TAG, "payload " + (1..80).joinToString(",", "[", "]") { """{"id":$it}""" })
    report("  wrote V/D/I/W/E lines, one stack trace and a 200-line burst")
  }

  private fun checkout(cartSize: Int) {
    check(cartSize > 0) { "cart is empty" }
  }
}
