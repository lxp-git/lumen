package dev.lumen.sample.demo

import android.util.Log
import dev.lumen.sample.AppGraph
import dev.lumen.sample.data.Favorite
import kotlinx.coroutines.CancellationException

/** Progress lines shown in the app's console card (and logged, so they reach Lumen too). */
typealias Report = (String) -> Unit

/** Runs [Scenario]s against the shared [AppGraph]. Every step tolerates being offline. */
class Demos(private val graph: AppGraph) {
  private val http = HttpDemo(graph)
  private val sockets = SocketDemo(graph.okHttp)

  suspend fun run(scenario: Scenario, report: Report) {
    val scenarios = if (scenario == Scenario.ALL) Scenario.entries - Scenario.ALL else listOf(scenario)
    for (s in scenarios) {
      report("▶ ${s.title}")
      try {
        when (s) {
          Scenario.HTTP -> http.run(report)
          Scenario.MOCK -> http.runMocks(report)
          Scenario.WEBSOCKET -> sockets.webSocket(report)
          Scenario.SOCKET_IO -> sockets.socketIo(report)
          Scenario.LOGCAT -> LogcatDemo.run(report)
          Scenario.STORAGE -> graph.settings.seed().forEach(report)
          Scenario.DATABASE -> database(report)
          Scenario.ALL -> Unit
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "${s.key} failed", e)
        report("  ✗ ${e.javaClass.simpleName}: ${e.message}")
      }
    }
  }

  suspend fun fetch(url: String): String = http.get(url)

  private suspend fun database(report: Report) {
    val names = try {
      graph.pokeApi.list(limit = 12).results.map { it.name }
    } catch (e: Exception) {
      report("  offline, using a built-in list (${e.javaClass.simpleName})")
      listOf("bulbasaur", "charmander", "squirtle", "pikachu", "eevee", "snorlax")
    }
    val now = System.currentTimeMillis()
    val dao = graph.database.favorites()
    dao.upsert(names.mapIndexed { i, name -> Favorite(i + 1, name, "unknown", now - i) })
    report("  favorites: ${dao.count()} rows; newest ${dao.latest(3).joinToString { it.name }}")
  }

  companion object {
    const val TAG = "LumenSample"
  }
}
