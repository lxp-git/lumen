package dev.lumen.proxy

import org.json.JSONObject

/**
 * Filters CDP traffic the inspect sidecar replays after the app process
 * comes back. Chrome already thinks `*.enable` ran; we re-send those to the
 * new process and drop the matching responses so old ids never reach Chrome.
 */
internal object InspectProxyMessages {
  fun shouldKeep(method: String): Boolean {
    if (method.endsWith(".enable")) return true
    return method == "Target.setAutoAttach" || method == "Target.setDiscoverTargets"
  }

  fun record(recorded: MutableList<String>, text: String) {
    val obj = try {
      JSONObject(text)
    } catch (_: Exception) {
      return
    }
    val method = obj.optString("method")
    if (method.isEmpty() || !shouldKeep(method)) return
    recorded.removeAll { prev ->
      try {
        JSONObject(prev).optString("method") == method
      } catch (_: Exception) {
        false
      }
    }
    recorded.add(text)
  }

  fun rewriteId(text: String, id: Int): String {
    return try {
      JSONObject(text).put("id", id).toString()
    } catch (_: Exception) {
      text
    }
  }

  fun isReplayResponse(text: String, replayIds: Set<Int>): Boolean {
    return try {
      val id = JSONObject(text).optInt("id", -1)
      id in replayIds
    } catch (_: Exception) {
      false
    }
  }
}
