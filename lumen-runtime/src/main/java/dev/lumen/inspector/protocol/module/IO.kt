package dev.lumen.inspector.protocol.module

import android.util.Base64
import android.util.Log
import dev.lumen.inspector.jsonrpc.JsonRpcPeer
import dev.lumen.inspector.jsonrpc.JsonRpcResult
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod
import dev.lumen.json.annotation.JsonProperty
import dev.lumen.mock.MockEngine
import org.json.JSONObject

/**
 * Minimal CDP IO domain so [Fetch.takeResponseBodyAsStream] can actually deliver bytes.
 * Chrome Local Overrides read the paused body through `IO.read` after opening the stream.
 */
class IO(
  private val engine: MockEngine,
) : ChromeDevtoolsDomain {

  private val logTag = "LumenFetch"

  @ChromeDevtoolsMethod
  fun read(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val handle = params?.optString("handle").orEmpty()
    val bytes = engine.readStream(handle) ?: ByteArray(0)
    Log.i(logTag, "IO.read handle=$handle bytes=${bytes.size}")
    return ReadResult(
      data = Base64.encodeToString(bytes, Base64.NO_WRAP),
      eof = true,
      base64Encoded = true,
    )
  }

  @ChromeDevtoolsMethod
  fun close(peer: JsonRpcPeer, params: JSONObject?) {
    val handle = params?.optString("handle").orEmpty()
    engine.closeStream(handle)
    Log.i(logTag, "IO.close handle=$handle")
  }

  class ReadResult(
    @JvmField @JsonProperty val data: String,
    @JvmField @JsonProperty val eof: Boolean,
    @JvmField @JsonProperty val base64Encoded: Boolean,
  ) : JsonRpcResult
}
