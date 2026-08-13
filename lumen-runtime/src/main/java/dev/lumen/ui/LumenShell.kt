package dev.lumen.ui

import dev.lumen.LumenAgent
import dev.lumen.store.LogSegmentInfo

/**
 * Shared log-segment commands for the in-app picker and
 * `adb shell content call … lumen-init`.
 */
object LumenShell {

  fun resolveSegmentId(raw: String?): String? {
    val id = raw?.trim().orEmpty()
    return if (id.isEmpty() || id.equals("live", true) || id.equals("latest", true) || id == "null") {
      null
    } else {
      id
    }
  }

  fun setActiveSegment(raw: String?): String {
    val store = LumenAgent.store ?: return "error: LumenAgent not started"
    val id = resolveSegmentId(raw)
    if (id != null && store.logs.listSegments().none { it.id == id }) {
      return "error: unknown segment $id"
    }
    store.logs.setActiveSegment(id)
    return "ok active=${id ?: "live"}"
  }

  fun listText(): String {
    val store = LumenAgent.store ?: return "error: LumenAgent not started"
    val active = store.logs.activeSegmentId
    val lines = ArrayList<String>()
    lines.add("active=${active ?: "live"}")
    lines.add(formatLive(active == null))
    for (seg in store.logs.listSegments()) {
      lines.add(formatSeg(seg, seg.id == active))
    }
    return lines.joinToString("\n")
  }

  fun formatLive(selected: Boolean): String {
    val mark = if (selected) "*" else " "
    return "$mark live  |  current logcat page (Chrome Console window)"
  }

  fun formatSeg(seg: LogSegmentInfo, selected: Boolean): String {
    val mark = if (selected) "*" else " "
    return "$mark ${seg.id}  |  ${seg.lineCount} lines  |  ${seg.sizeBytes} B  |  ${seg.fileName}"
  }

}
