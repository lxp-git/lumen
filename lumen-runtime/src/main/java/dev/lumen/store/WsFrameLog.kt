package dev.lumen.store

/**
 * Archived frames of one WebSocket, appended from OkHttp's reader/writer threads and read
 * by CDP replay on the socket thread.
 *
 * A locked list instead of a CopyOnWriteArrayList: a streaming socket appends thousands of
 * frames, and copy-on-write paid a full array copy per frame (two once at capacity).
 */
class WsFrameLog {
  private val frames = ArrayList<WsFrame>()

  /**
   * Appends [frame] and evicts down to [max]: Engine.IO ping/pong (`2` / `3`) go before
   * chat/message frames, so a long-lived Socket.IO connection keeps the conversation
   * for late attach.
   */
  @Synchronized
  fun add(frame: WsFrame, max: Int) {
    frames.add(frame)
    while (frames.size > max) {
      val control = frames.indexOfFirst(::isEngineIoControl)
      frames.removeAt(if (control >= 0) control else 0)
    }
  }

  @Synchronized
  fun snapshot(): List<WsFrame> = ArrayList(frames)

  @Synchronized
  fun isEmpty(): Boolean = frames.isEmpty()

  @get:Synchronized
  val size: Int
    get() = frames.size

  private fun isEngineIoControl(frame: WsFrame): Boolean {
    if (frame.binary) return false
    val p = frame.payload
    if (p.isEmpty()) return false
    val c = p[0]
    return (c == '2' || c == '3') && p.length <= 16
  }
}
