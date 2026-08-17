package dev.lumen.gradle

import org.gradle.api.Project

/**
 * DSL:
 * ```
 * lumen {
 *   enabled.set(true)
 *   retentionDays.set(7)
 *   injectOkHttp.set(true)
 * }
 * ```
 */
open class LumenExtension(project: Project) {
  val enabled = project.objects.property(Boolean::class.java).convention(true)
  val retentionDays = project.objects.property(Int::class.java).convention(7)
  val networkBodyQuotaMb = project.objects.property(Int::class.java).convention(512)
  val logPageSize = project.objects.property(Int::class.java).convention(1_000)
  val wsMaxFrames = project.objects.property(Int::class.java).convention(2_500)
  val wsMaxFrameChars = project.objects.property(Int::class.java).convention(16_384)
  val mockEnabled = project.objects.property(Boolean::class.java).convention(true)
  /** Persist DevTools overrides so they replay without Chrome attached. Off by default. */
  val mockRecordOverrides = project.objects.property(Boolean::class.java).convention(false)
  val debugFab = project.objects.property(Boolean::class.java).convention(true)
  /** Write Lumen agent diagnostics to logcat. Off by default. */
  val debugLogs = project.objects.property(Boolean::class.java).convention(false)
  /** When true, ASM-inject LumenInterceptor into OkHttpClient.Builder.build(). */
  val injectOkHttp = project.objects.property(Boolean::class.java).convention(true)
  /** Apply only to debuggable variants (default). */
  val debugOnly = project.objects.property(Boolean::class.java).convention(true)
}
