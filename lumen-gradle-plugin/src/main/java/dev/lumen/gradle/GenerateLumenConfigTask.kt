package dev.lumen.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes host-app resource overrides so [dev.lumen.LumenConfig.from] sees the
 * `lumen { }` DSL instead of library defaults.
 */
abstract class GenerateLumenConfigTask : DefaultTask() {

  @get:Input
  abstract val retentionDays: Property<Int>

  @get:Input
  abstract val logPageSize: Property<Int>

  @get:Input
  abstract val networkBodyQuotaMb: Property<Int>

  @get:Input
  abstract val wsMaxFrames: Property<Int>

  @get:Input
  abstract val wsMaxFrameChars: Property<Int>

  @get:Input
  abstract val mockEnabled: Property<Boolean>

  @get:Input
  abstract val mockRecordOverrides: Property<Boolean>

  @get:Input
  abstract val debugFab: Property<Boolean>

  @get:Input
  abstract val debugLogs: Property<Boolean>

  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun generate() {
    val valuesDir = outputDirectory.get().asFile.resolve("values")
    valuesDir.mkdirs()
    valuesDir.resolve("lumen_config.xml").writeText(
      """
      <?xml version="1.0" encoding="utf-8"?>
      <resources>
          <integer name="lumen_retention_days">${retentionDays.get()}</integer>
          <integer name="lumen_log_page_size">${logPageSize.get()}</integer>
          <integer name="lumen_network_body_quota_mb">${networkBodyQuotaMb.get()}</integer>
          <integer name="lumen_ws_max_frames">${wsMaxFrames.get()}</integer>
          <integer name="lumen_ws_max_frame_chars">${wsMaxFrameChars.get()}</integer>
          <bool name="lumen_mock_enabled">${mockEnabled.get()}</bool>
          <bool name="lumen_mock_record_overrides">${mockRecordOverrides.get()}</bool>
          <bool name="lumen_debug_fab">${debugFab.get()}</bool>
          <bool name="lumen_debug_logs">${debugLogs.get()}</bool>
      </resources>
      """.trimIndent() + "\n",
    )
  }
}
