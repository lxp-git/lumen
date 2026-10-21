package dev.lumen.sample.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.first

private val Context.userPrefs by preferencesDataStore(name = "user_prefs")

/**
 * The three key-value engines Lumen maps into Chrome's Application panel. Each one gets a
 * value of every supported type so type-preserving edits can be tried from DevTools.
 */
class Settings(private val context: Context) {
  private val session = context.getSharedPreferences("session", Context.MODE_PRIVATE)
  private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  suspend fun seed(): List<String> {
    session.edit()
      .putString("user_id", "u_1024")
      .putLong("last_login", System.currentTimeMillis())
      .apply()
    settings.edit()
      .putString("theme", settings.getString("theme", "dark"))
      .putBoolean("onboarded", true)
      .putInt("launch_count", settings.getInt("launch_count", 0) + 1)
      .putFloat("font_scale", 1.15f)
      .putStringSet("flags", setOf("beta", "inspect"))
      .apply()

    context.userPrefs.edit { prefs ->
      prefs[stringPreferencesKey("display_name")] = "Ash"
      prefs[booleanPreferencesKey("analytics")] = false
      prefs[intPreferencesKey("favorite_count")] = 6
      prefs[longPreferencesKey("synced_at")] = System.currentTimeMillis()
      prefs[stringSetPreferencesKey("regions")] = setOf("kanto", "johto")
    }

    MMKV.defaultMMKV().apply {
      encode("token", "sample-token-${System.currentTimeMillis() % 10_000}")
      encode("retry", 2)
      encode("cached", true)
    }
    MMKV.mmkvWithID("feature_cache").apply {
      encode("home_layout", "grid")
      encode("ttl_seconds", 3_600L)
    }

    val dataStoreName = context.userPrefs.data.first()[stringPreferencesKey("display_name")]
    return listOf(
      "SharedPreferences session + settings (theme=${settings.getString("theme", null)}, " +
        "launch_count=${settings.getInt("launch_count", 0)})",
      "DataStore user_prefs (display_name=$dataStoreName)",
      "MMKV default + feature_cache",
    )
  }
}
