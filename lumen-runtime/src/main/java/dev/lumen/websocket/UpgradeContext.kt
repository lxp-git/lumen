package dev.lumen.websocket

/** Upgrade URL path for the in-flight WebSocket handshake (same thread as `onOpen`). */
object UpgradeContext {
  private val path = ThreadLocal<String>()

  @JvmStatic
  fun setPath(value: String?) {
    if (value == null) path.remove() else path.set(value)
  }

  @JvmStatic
  fun getPath(): String? = path.get()
}
