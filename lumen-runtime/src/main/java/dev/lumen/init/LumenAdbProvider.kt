package dev.lumen.init

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import dev.lumen.LumenAgent
import dev.lumen.ui.LumenShell

/**
 * Exported so `adb shell content` (uid 2000) can page logcat without any in-app UI.
 * Debug-only surface: the Gradle plugin only adds this library to debuggable variants.
 * Calls are additionally gated to shell/root/self so other apps on the device
 * cannot poke the agent.
 *
 * ```
 * adb shell content call --uri content://&lt;pkg&gt;.lumen-adb --method listLogSegments
 * adb shell content call --uri content://&lt;pkg&gt;.lumen-adb --method setActiveLogSegment --arg live
 * adb shell content call --uri content://&lt;pkg&gt;.lumen-adb --method setActiveLogSegment --arg seg-3
 * ```
 */
class LumenAdbProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  private fun callerAllowed(): Boolean {
    val uid = Binder.getCallingUid()
    return uid == Process.myUid() || uid == SHELL_UID || uid == ROOT_UID
  }

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    val out = Bundle()
    if (!callerAllowed()) {
      out.putString("result", "error: denied")
      return out
    }
    when (method) {
      "listLogSegments" -> {
        out.putString("result", LumenShell.listText())
        out.putString("active", LumenAgent.store?.logs?.activeSegmentId ?: "live")
      }
      "setActiveLogSegment" -> {
        val raw = arg ?: extras?.getString("segmentId")
        out.putString("result", LumenShell.setActiveSegment(raw))
        out.putString("active", LumenAgent.store?.logs?.activeSegmentId ?: "live")
      }
      "getStatus" -> {
        val store = LumenAgent.store
        out.putString("result", if (store == null) "error: not started" else "ok")
        if (store != null) {
          out.putInt("retentionDays", store.config.retentionDays)
          out.putInt("networkCount", store.network.allRecords().size)
          out.putInt("segments", store.logs.listSegments().size)
          out.putString("active", store.logs.activeSegmentId ?: "live")
        }
      }
      else -> out.putString("result", "error: unknown method $method")
    }
    return out
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor {
    val cursor = MatrixCursor(arrayOf("id", "fileName", "lineCount", "sizeBytes", "active", "path"))
    if (!callerAllowed()) return cursor
    val store = LumenAgent.store ?: return cursor
    val active = store.logs.activeSegmentId
    cursor.addRow(arrayOf<Any>("live", "live", -1, 0, if (active == null) 1 else 0, ""))
    for (seg in store.logs.listSegments()) {
      cursor.addRow(
        arrayOf<Any>(
          seg.id,
          seg.fileName,
          seg.lineCount,
          seg.sizeBytes,
          if (seg.id == active) 1 else 0,
          seg.path,
        ),
      )
    }
    return cursor
  }

  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0

  private companion object {
    const val SHELL_UID = 2000
    const val ROOT_UID = 0
  }
}
