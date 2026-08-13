package dev.lumen.init

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import dev.lumen.common.LogUtil

/**
 * Highest `initOrder` provider: bind the DevTools abstract socket before
 * other ContentProviders and before [LumenInitProvider] loads the agent.
 */
class LumenSocketProvider : ContentProvider() {
  override fun onCreate(): Boolean {
    // A debug agent must never crash the host, even if the socket bind fails
    // (e.g. address already in use after a bad restart).
    try {
      context?.let { DevtoolsEarlyBind.bind(it) }
    } catch (t: Throwable) {
      LogUtil.e(t, "Lumen early socket bind failed")
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
