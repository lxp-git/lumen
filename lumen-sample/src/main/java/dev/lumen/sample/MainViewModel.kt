package dev.lumen.sample

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lumen.sample.demo.Demos
import dev.lumen.sample.demo.Scenario
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
  private val demos = Demos((app as SampleApp).graph)

  private val _console = MutableStateFlow<List<String>>(emptyList())
  /** Newest-first progress lines for the console card. */
  val console: StateFlow<List<String>> = _console.asStateFlow()

  private val _running = MutableStateFlow<Set<Scenario>>(emptySet())
  val running: StateFlow<Set<Scenario>> = _running.asStateFlow()

  fun run(scenario: Scenario) {
    if (scenario in _running.value) return
    _running.update { it + scenario }
    viewModelScope.launch {
      try {
        demos.run(scenario, ::print)
        print("✓ ${scenario.title} done")
      } finally {
        _running.update { it - scenario }
      }
    }
  }

  fun fetch(url: String, count: Int = 1) {
    viewModelScope.launch {
      if (count == 1) {
        print(demos.fetch(url))
        return@launch
      }
      repeat(count) { demos.fetch(url) }
      print("fetched $url $count times")
    }
  }

  private fun print(line: String) {
    Log.i(Demos.TAG, line.trim())
    _console.update { (listOf(line) + it).take(MAX_LINES) }
  }

  private companion object {
    const val MAX_LINES = 80
  }
}
