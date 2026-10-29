package dev.lumen.store

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecordJsonTest {

  private fun record() = NetworkRecord(
    requestId = "4242.17",
    url = "https://api.example.com/status",
    method = "POST",
    requestHeaders = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer t"),
    requestBody = """{"memoryLimit":512}""",
    startedAtMs = 1_000L,
    startedAtMonotonicMs = 50_000L,
  )

  /** Folds lines the way NetworkArchive reads a session file. */
  private fun read(vararg lines: JSONObject): NetworkRecord {
    val merged = JSONObject(lines.first().toString())
    for (line in lines.drop(1)) NetworkRecordJson.merge(merged, JSONObject(line.toString()))
    return NetworkRecordJson.record(merged)
  }

  @Test
  fun headersAreWrittenOnlyWhenTheRecordHoldsANewMap() {
    val r = record()
    val put = NetworkRecordJson.line(r)
    assertTrue(put.has("requestHeaders"))
    assertFalse(put.has("responseHeaders")) // still empty

    r.statusCode = 200
    r.responseHeaders = mapOf("Content-Type" to "application/json")
    val response = NetworkRecordJson.line(r)
    assertFalse(response.has("requestHeaders"))
    assertTrue(response.has("responseHeaders"))

    r.finishedAtMs = 1_250L
    val finished = NetworkRecordJson.line(r)
    assertFalse(finished.has("requestHeaders"))
    assertFalse(finished.has("responseHeaders"))

    r.requestHeaders = mapOf("Content-Type" to "application/json", "Host" to "api.example.com")
    assertTrue(NetworkRecordJson.line(r).has("requestHeaders"))
  }

  @Test
  fun mergedLinesRebuildTheRecord() {
    val r = record()
    val put = NetworkRecordJson.line(r)
    r.statusCode = 200
    r.statusText = "OK"
    r.mimeType = "application/json"
    r.responseHeaders = mapOf("Content-Encoding" to "gzip")
    val response = NetworkRecordJson.line(r)
    r.responseBodyEncoding = "gzip"
    r.finishedAtMs = 1_250L
    r.encodedDataLength = 321
    val finished = NetworkRecordJson.line(r)

    val back = read(put, response, finished)

    assertEquals("4242.17", back.requestId)
    assertEquals("POST", back.method)
    assertEquals(r.requestHeaders, back.requestHeaders)
    assertEquals(mapOf("Content-Encoding" to "gzip"), back.responseHeaders)
    assertEquals(200, back.statusCode)
    assertEquals("OK", back.statusText)
    assertEquals("application/json", back.mimeType)
    assertEquals("gzip", back.responseBodyEncoding)
    assertEquals(50_000L, back.startedAtMonotonicMs)
    assertEquals(1_250L, back.finishedAtMs)
    assertEquals(321L, back.encodedDataLength)
    assertNull(back.requestBody) // bodies live in sidecar files
  }

  @Test
  fun unsetFieldsDoNotClobberEarlierValues() {
    val r = record()
    r.statusCode = 503
    r.failedReason = "java.io.IOException: reset"
    val first = NetworkRecordJson.line(r)
    // A later line for the same record with nothing new must not erase what came before.
    val sparse = JSONObject().put("id", "4242.17").put("status", null as Any?)

    val back = read(first, sparse)

    assertEquals(503, back.statusCode)
    assertEquals("java.io.IOException: reset", back.failedReason)
  }

  @Test
  fun linesFromBeforeHeadersWereKeptStillRead() {
    val legacy = JSONObject("""{"id":"77.3","url":"https://x","method":"GET","status":200,""" +
      """"startedAt":5,"finishedAt":9,"mocked":false,"encodedDataLength":10,"ws":false,"type":"Other"}""")

    val back = NetworkRecordJson.record(legacy)

    assertEquals(200, back.statusCode)
    assertEquals(emptyMap<String, String>(), back.requestHeaders)
    assertEquals(0L, back.startedAtMonotonicMs)
    assertNull(back.mimeType)
  }
}
