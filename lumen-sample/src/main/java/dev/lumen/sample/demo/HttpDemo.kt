package dev.lumen.sample.demo

import dev.lumen.sample.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.zip.GZIPInputStream

/** Ordinary app networking. Nothing here knows Lumen exists. */
class HttpDemo(private val graph: AppGraph) {
  private val client get() = graph.okHttp

  suspend fun run(report: Report) {
    val page = graph.pokeApi.list(limit = 20)
    report("  GET pokemon?limit=20 → ${page.results.size} of ${page.count}")
    val pikachu = graph.pokeApi.pokemon("pikachu")
    report("  GET pokemon/pikachu → #${pikachu.id} ${pikachu.typeNames}")

    call("POST httpbin /anything (JSON body)") {
      Request.Builder()
        .url("https://httpbin.org/anything/search")
        .post("""{"query":"lumen","page":1}""".toRequestBody(JSON))
        .build()
    }.let(report)

    // Setting Accept-Encoding ourselves turns off OkHttp's transparent gzip: the app (and
    // Lumen's capture) see the compressed bytes. Lumen decodes them only when viewed.
    withContext(Dispatchers.IO) {
      client.newCall(
        Request.Builder().url("https://httpbin.org/gzip").header("Accept-Encoding", "gzip").build(),
      ).execute().use { response ->
        val text = response.body?.byteStream()?.let { GZIPInputStream(it).bufferedReader().readText() }
        report("  GET httpbin /gzip (app-negotiated) → ${response.code}, ${text?.length ?: 0} chars")
      }
    }

    call("GET httpbin /image/png (binary body)") {
      Request.Builder().url("https://httpbin.org/image/png").build()
    }.let(report)
    call("GET httpbin /status/404") {
      Request.Builder().url("https://httpbin.org/status/404").build()
    }.let(report)
    call("GET unresolvable host (failed row)") {
      Request.Builder().url("https://no-such-host.lumen.invalid/ping").build()
    }.let(report)
  }

  /** Each request matches a rule in src/debug/assets/lumen-mocks; none of the hosts exist. */
  suspend fun runMocks(report: Report) {
    val missingno = graph.pokeApi.pokemon("missingno")
    report("  GET pokemon/missingno → #${missingno.id} ${missingno.name} ${missingno.typeNames} (mock)")
    call("GET api.lumen.invalid/v1/flags") {
      Request.Builder().url("https://api.lumen.invalid/v1/flags").build()
    }.let(report)
    call("POST api.lumen.invalid/v1/checkout (slow 503)") {
      Request.Builder()
        .url("https://api.lumen.invalid/v1/checkout")
        .post("""{"cart":[25,133]}""".toRequestBody(JSON))
        .build()
    }.let(report)
  }

  suspend fun get(url: String): String = call("GET $url") { Request.Builder().url(url).build() }

  private suspend fun call(label: String, request: () -> Request): String =
    withContext(Dispatchers.IO) {
      try {
        client.newCall(request()).execute().use { response ->
          val bytes = response.body?.bytes()?.size ?: 0
          "  $label → ${response.code}, $bytes bytes"
        }
      } catch (e: IOException) {
        "  $label → ${e.javaClass.simpleName}"
      }
    }

  private companion object {
    val JSON = "application/json; charset=utf-8".toMediaType()
  }
}
