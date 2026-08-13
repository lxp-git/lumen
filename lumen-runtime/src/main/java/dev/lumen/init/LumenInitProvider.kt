package dev.lumen.init

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import dev.lumen.LumenAgent
import dev.lumen.LumenConfig
import dev.lumen.common.LogUtil

/**
 * Manifest-merged auto-init. Runs before [android.app.Application.onCreate], so logcat
 * and (once OkHttp is woven) network capture buffer from process start with zero host glue.
 *
 * Log-segment adb commands go through [LumenAdbProvider] (`*.lumen-adb`), not this one.
 */
class LumenInitProvider : ContentProvider() {
  override fun onCreate(): Boolean {
    val ctx = context ?: return false
    try {
      LumenAgent.start(ctx, LumenConfig.from(ctx))
    } catch (t: Throwable) {
      LogUtil.e(t, "LumenAgent.start failed")
    }
    return true
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0
}
