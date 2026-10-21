package dev.lumen.sample

import android.app.Application
import com.tencent.mmkv.MMKV

/**
 * Plain application class. Note what is missing: no Lumen import, no init call, no
 * interceptor. The Gradle plugin adds all of that to debug builds only.
 */
class SampleApp : Application() {
  val graph: AppGraph by lazy { AppGraph(this) }

  override fun onCreate() {
    super.onCreate()
    MMKV.initialize(this)
  }
}
