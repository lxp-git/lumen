package dev.lumen.inspector.jsonrpc

import org.json.JSONObject

/**
 * A result that is already JSON. [dev.lumen.inspector.MethodDispatcher] sends [json] as-is
 * instead of reflecting over annotated fields, so Kotlin domains can build replies with
 * `JSONObject().put(...)` the way they build events.
 */
class RawJsonResult(val json: JSONObject) : JsonRpcResult
