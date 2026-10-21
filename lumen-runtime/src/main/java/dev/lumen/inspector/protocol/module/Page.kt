package dev.lumen.inspector.protocol.module

import dev.lumen.common.ProcessUtil
import dev.lumen.inspector.jsonrpc.JsonRpcPeer
import dev.lumen.inspector.jsonrpc.JsonRpcResult
import dev.lumen.inspector.jsonrpc.RawJsonResult
import dev.lumen.inspector.protocol.ChromeDevtoolsDomain
import dev.lumen.inspector.protocol.ChromeDevtoolsMethod
import dev.lumen.json.annotation.JsonValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * CDP `Page`: one synthetic `lumen://<process>` frame so the frontend's init path (resource
 * tree, navigation history, storage key) resolves. Emulation / screencast calls fall through
 * to the dispatcher's stub ack. Class simple name must stay `Page`.
 */
class Page : ChromeDevtoolsDomain {

  @ChromeDevtoolsMethod
  fun enable(peer: JsonRpcPeer, params: JSONObject?) {
    peer.invokeMethod(
      "Runtime.executionContextCreated",
      JSONObject().put("context", JSONObject().put("frameId", FRAME_ID).put("id", 1)),
      null,
    )
    val banner = JSONObject()
      .put("source", Console.MessageSource.JAVASCRIPT.protocolValue)
      .put("level", Console.MessageLevel.LOG.protocolValue)
      .put("text", "Lumen attached\n          Attached to ${ProcessUtil.getProcessName()}\n")
    peer.invokeMethod("Console.messageAdded", JSONObject().put("message", banner), null)
  }

  @ChromeDevtoolsMethod
  fun getResourceTree(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    // One lumen:// frame so storageKey matches Storage.getStorageKeyForFrame.
    val url = selfUrl()
    val frame = JSONObject()
      .put("id", FRAME_ID)
      .put("loaderId", "1")
      .put("name", "Lumen")
      .put("url", url)
      .put("securityOrigin", url)
      .put("mimeType", "text/plain")
    return result("frameTree", JSONObject().put("frame", frame).put("resources", JSONArray()))
  }

  /** Live preview is unsupported: JPEG encoding every frame races Compose / hardware bitmaps. */
  @ChromeDevtoolsMethod
  fun canScreencast(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult =
    result("result", false)

  @ChromeDevtoolsMethod
  fun hasTouchInputs(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult =
    result("result", false)

  /** Chrome fills the back/forward buttons from this on every session start. */
  @ChromeDevtoolsMethod
  fun getNavigationHistory(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult {
    val entry = JSONObject()
      .put("id", 0)
      .put("url", selfUrl())
      .put("userTypedURL", selfUrl())
      .put("title", ProcessUtil.getProcessName())
      .put("transitionType", "typed")
    return RawJsonResult(JSONObject().put("currentIndex", 0).put("entries", JSONArray().put(entry)))
  }

  /** Acknowledged with a fixed id; there is no JS context to run init scripts in. */
  @ChromeDevtoolsMethod
  fun addScriptToEvaluateOnNewDocument(peer: JsonRpcPeer, params: JSONObject?): JsonRpcResult =
    result("identifier", "lumen-noop-script")

  enum class ResourceType(private val protocolValue: String) {
    DOCUMENT("Document"),
    STYLESHEET("Stylesheet"),
    IMAGE("Image"),
    FONT("Font"),
    SCRIPT("Script"),
    XHR("XHR"),
    WEBSOCKET("WebSocket"),
    OTHER("Other");

    @JsonValue
    fun getProtocolValue(): String = protocolValue
  }

  private companion object {
    const val FRAME_ID = "1"

    fun selfUrl(): String = "lumen://" + ProcessUtil.getProcessName()

    fun result(key: String, value: Any): JsonRpcResult = RawJsonResult(JSONObject().put(key, value))
  }
}
