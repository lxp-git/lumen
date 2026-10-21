package dev.lumen.inspector.elements.android.window

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import java.util.Collections

/**
 * Every attached window root (activities, dialogs, popups) via the hidden
 * `WindowManagerGlobal.mViews` list, present on every supported API level.
 */
class WindowRootViewCompat private constructor() {
  private val views: List<View>

  init {
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    val global = Class.forName("android.view.WindowManagerGlobal")
    val instance = global.getDeclaredMethod("getInstance").invoke(null)
    val field = global.getDeclaredField("mViews").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    views = field.get(instance) as List<View>
  }

  val rootViews: List<View>
    get() = Collections.unmodifiableList(views)

  companion object {
    @Volatile private var instance: WindowRootViewCompat? = null

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun get(context: Context): WindowRootViewCompat =
      instance ?: synchronized(this) { instance ?: WindowRootViewCompat().also { instance = it } }
  }
}
