package dev.lumen.sample

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.lumen.sample.databinding.ActivityMainBinding
import dev.lumen.sample.databinding.ItemScenarioBinding
import dev.lumen.sample.demo.Scenario
import kotlinx.coroutines.launch

/**
 * One card per Lumen feature plus a console. Classic Views on purpose: they are what the
 * Elements panel inspects. Scenarios also run from `--es scenario <name>` (see [Scenario]),
 * and `--es fetch <url>` issues a single GET.
 */
class MainActivity : AppCompatActivity() {
  private val viewModel: MainViewModel by viewModels()
  private lateinit var binding: ActivityMainBinding
  private val buttons = HashMap<Scenario, ItemScenarioBinding>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    binding.toolbar.subtitle = getString(R.string.inspect_hint, packageName)

    for (scenario in Scenario.entries) {
      val card = ItemScenarioBinding.inflate(LayoutInflater.from(this), binding.scenarios, true)
      card.title.text = scenario.title
      card.summary.text = scenario.summary
      card.where.text = scenario.where
      card.run.setOnClickListener { viewModel.run(scenario) }
      buttons[scenario] = card
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch { viewModel.console.collect { binding.console.text = it.joinToString("\n") } }
        launch {
          viewModel.running.collect { running ->
            for ((scenario, card) in buttons) card.run.isEnabled = scenario !in running
          }
        }
      }
    }

    if (savedInstanceState == null) runFromIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    runFromIntent(intent)
  }

  private fun runFromIntent(intent: Intent?) {
    intent?.getStringExtra(EXTRA_FETCH)?.let { url ->
      viewModel.fetch(url, intent.getIntExtra(EXTRA_COUNT, 1).coerceIn(1, 10_000))
    }
    val name = intent?.getStringExtra(EXTRA_SCENARIO) ?: return
    val scenario = Scenario.parse(name)
    if (scenario == null) {
      binding.console.text = getString(R.string.unknown_scenario, name)
      return
    }
    viewModel.run(scenario)
  }

  companion object {
    const val EXTRA_SCENARIO = "scenario"

    /** `--es fetch <url>`: one GET through the app's client, e.g. to try a mock rule. */
    const val EXTRA_FETCH = "fetch"

    /** `--ei count <n>` with `fetch`: n GETs one after another, e.g. to fill the network history. */
    const val EXTRA_COUNT = "count"
  }
}
