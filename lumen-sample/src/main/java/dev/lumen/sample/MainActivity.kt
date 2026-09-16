package dev.lumen.sample

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val Context.sampleDataStore by preferencesDataStore(name = "lumen_sample")

/**
 * Intentionally no Lumen.* / Stetho.* calls.
 * Agent auto-starts via ContentProvider; interceptor + WebSocket listener wrap are ASM-injected.
 */
class MainActivity : AppCompatActivity() {
  private val executor = Executors.newSingleThreadExecutor()
  private val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .build()
  }

  private var activeSocket: WebSocket? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    val status = findViewById<TextView>(R.id.status)
    val output = findViewById<TextView>(R.id.output)

    val agentStarted = try {
      Class.forName("dev.lumen.LumenAgent")
        .getMethod("isStarted")
        .invoke(null) as Boolean
    } catch (_: Throwable) {
      false
    }
    status.text = "Lumen agent started=$agentStarted · open chrome://inspect"

    seedKvStores()
    findViewById<Button>(R.id.btn_kv).setOnClickListener {
      seedKvStores()
      output.text = "Seeded SharedPreferences / DataStore / MMKV\n" + output.text
    }

    findViewById<Button>(R.id.btn_request).setOnClickListener {
      executor.execute {
        val sb = StringBuilder()
        repeat(5) { i ->
          val url = "https://httpbin.org/get?n=$i&t=${System.currentTimeMillis()}"
          try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
              val len = resp.body?.string()?.length ?: 0
              sb.append("GET $url -> ${resp.code} (${len}b)\n")
            }
          } catch (t: Throwable) {
            sb.append("GET $url -> ERR ${t.message}\n")
          }
        }
        runOnUiThread { output.text = sb.toString() + output.text }
      }
    }

    findViewById<Button>(R.id.btn_log).setOnClickListener {
      repeat(50) { i ->
        Log.i("LumenSample", "sample log line #$i at ${System.currentTimeMillis()}")
      }
      output.text = "Wrote 50 log lines\n" + output.text
    }

    findViewById<Button>(R.id.btn_mock_target).setOnClickListener {
      executor.execute {
        val sb = StringBuilder()
        for (url in listOf(
          "https://httpbin.org/uuid",
          "https://httpbin.org/anything/lumen-glob",
        )) {
          try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
              sb.append("GET $url -> ${resp.code}\n${resp.body?.string()}\n")
            }
          } catch (t: Throwable) {
            sb.append("GET $url -> ERR ${t.message}\n")
          }
        }
        runOnUiThread { output.text = sb.toString() + output.text }
      }
    }

    findViewById<Button>(R.id.btn_websocket).setOnClickListener {
      activeSocket?.cancel()
      val url = "wss://ws.postman-echo.com/raw"
      val request = Request.Builder().url(url).build()
      val socket = client.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            runOnUiThread { output.text = "WS open $url (${response.code})\n" + output.text }
            webSocket.send("hello-from-lumen-sample ${System.currentTimeMillis()}")
            repeat(20) { i ->
              webSocket.send("""{"i":$i,"chunk":"stream-delta-$i"}""")
            }
          }

          override fun onMessage(webSocket: WebSocket, text: String) {
            runOnUiThread { output.text = "WS recv: $text\n" + output.text }
          }

          override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            runOnUiThread { output.text = "WS closed $code $reason\n" + output.text }
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            runOnUiThread { output.text = "WS fail: ${t.message}\n" + output.text }
          }
        },
      )
      activeSocket = socket
      executor.execute {
        try {
          Thread.sleep(2_000)
        } catch (_: InterruptedException) {
        }
        socket.close(1000, "sample-done")
      }
    }
  }

  override fun onDestroy() {
    activeSocket?.cancel()
    super.onDestroy()
  }

  /**
   * Values show up in Chrome Application → IndexedDB
   * (`SharedPreferences` / `DataStore` / `MMKV`) and Local Storage.
   */
  private fun seedKvStores() {
    getSharedPreferences("lumen_sample", Context.MODE_PRIVATE).edit()
      .putString("theme", "dark")
      .putBoolean("onboarded", true)
      .putInt("launch_count", 3)
      .putStringSet("flags", setOf("beta", "inspect"))
      .apply()

    runBlocking {
      sampleDataStore.edit { prefs ->
        prefs[stringPreferencesKey("display_name")] = "Lumen sample"
        prefs[booleanPreferencesKey("analytics")] = false
        prefs[intPreferencesKey("session")] = 42
        prefs[stringSetPreferencesKey("tags")] = setOf("kv", "datastore")
      }
    }

    MMKV.initialize(this)
    val kv = MMKV.defaultMMKV()
    kv.encode("token", "sample-token")
    kv.encode("retry", 2)
    kv.encode("ok", true)
  }
}
