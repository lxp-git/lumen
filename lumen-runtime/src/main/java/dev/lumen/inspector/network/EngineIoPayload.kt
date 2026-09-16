package dev.lumen.inspector.network

import org.json.JSONObject

/**
 * Engine.IO HTTP long-polling payload codec (EIO 3 length-prefix and EIO 4
 * record-separator). Used to lift OkHttp `transport=polling` bodies onto a
 * synthetic WebSocket Messages row.
 *
 * Does not cover JSONP or HttpURLConnection-based clients.
 */
object EngineIoPayload {
  const val RECORD_SEPARATOR = '\u001e'

  @JvmStatic
  fun isPollingUrl(url: String): Boolean {
    val transport = queryParam(url, "transport") ?: return false
    if (!transport.equals("polling", ignoreCase = true)) return false
    if (queryParam(url, "EIO") != null) return true
    val path = originKey(url)
    return path.contains("socket.io", ignoreCase = true) ||
      path.contains("engine.io", ignoreCase = true)
  }

  @JvmStatic
  fun sidFromUrl(url: String): String? = queryParam(url, "sid")?.takeIf { it.isNotEmpty() }

  /** Scheme + host + path, no query/fragment. Handshake and sid polls share this. */
  @JvmStatic
  fun originKey(url: String): String {
    val noFrag = url.substringBefore('#')
    return noFrag.substringBefore('?')
  }

  @JvmStatic
  fun queryParam(url: String, name: String): String? {
    val q = url.substringAfter('?', missingDelimiterValue = "")
      .substringBefore('#')
    if (q.isEmpty()) return null
    for (part in q.split('&')) {
      val eq = part.indexOf('=')
      val key = if (eq < 0) part else part.substring(0, eq)
      if (key == name) {
        return if (eq < 0) "" else part.substring(eq + 1)
      }
    }
    return null
  }

  /**
   * Split a polling body into Engine.IO packets. Unknown encodings are returned
   * as a single element so the Messages tab still shows something.
   */
  @JvmStatic
  fun decode(payload: String): List<String> {
    if (payload.isEmpty()) return emptyList()
    if (payload == "ok") return emptyList()
    if (payload.indexOf(RECORD_SEPARATOR) >= 0) {
      return payload.split(RECORD_SEPARATOR).filter { it.isNotEmpty() }
    }
    if (looksLikeLengthPrefixed(payload)) {
      return decodeLengthPrefixed(payload) ?: listOf(payload)
    }
    return listOf(payload)
  }

  @JvmStatic
  fun isEngineIoPacket(packet: String): Boolean {
    if (packet.isEmpty()) return false
    val c = packet[0]
    return c in '0'..'6' || c == 'b'
  }

  @JvmStatic
  fun isClosePacket(packet: String): Boolean = packet.isNotEmpty() && packet[0] == '1'

  @JvmStatic
  fun sidFromOpenPacket(packet: String): String? {
    if (packet.length < 2 || packet[0] != '0') return null
    return try {
      JSONObject(packet.substring(1)).optString("sid").takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
      null
    }
  }

  private fun looksLikeLengthPrefixed(payload: String): Boolean {
    var i = 0
    while (i < payload.length && payload[i] in '0'..'9') i++
    return i > 0 && i < payload.length && payload[i] == ':'
  }

  private fun decodeLengthPrefixed(payload: String): List<String>? {
    val out = ArrayList<String>()
    var i = 0
    while (i < payload.length) {
      var j = i
      while (j < payload.length && payload[j] in '0'..'9') j++
      if (j == i || j >= payload.length || payload[j] != ':') return null
      val len = payload.substring(i, j).toLongOrNull() ?: return null
      if (len < 0) return null
      val start = j + 1
      val remaining = (payload.length - start).toLong()
      if (len > remaining) return null
      val end = start + len.toInt()
      out.add(payload.substring(start, end))
      i = end
    }
    return out
  }
}
