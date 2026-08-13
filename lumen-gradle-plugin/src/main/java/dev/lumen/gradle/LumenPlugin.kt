package dev.lumen.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.AdviceAdapter

/**
 * Zero-config Lumen wiring:
 * 1. Adds lumen-okhttp to debuggable variants
 * 2. Writes `lumen { }` DSL into app resources consumed by [dev.lumen.LumenConfig]
 * 3. ASM-injects [dev.lumen.okhttp.LumenInterceptor] into `OkHttpClient.Builder.build()`
 *    and wraps `OkHttpClient.newWebSocket` listeners
 *
 * Host usage:
 * ```
 * plugins { id("dev.lumen") }
 * ```
 */
class LumenPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val ext = project.extensions.create("lumen", LumenExtension::class.java, project)

    project.pluginManager.withPlugin("com.android.application") {
      configureAndroid(project, ext)
    }
    project.pluginManager.withPlugin("com.android.library") {
      configureAndroid(project, ext)
    }
  }

  private fun configureAndroid(project: Project, ext: LumenExtension) {
    val androidComponents =
      project.extensions.findByType(AndroidComponentsExtension::class.java) ?: return

    androidComponents.onVariants { variant ->
      val debuggable = try {
        variant.debuggable
      } catch (_: Throwable) {
        variant.name.contains("debug", ignoreCase = true)
      }
      if (ext.debugOnly.get() && !debuggable) {
        return@onVariants
      }
      if (!ext.enabled.get()) {
        return@onVariants
      }

      project.dependencies.add(
        "${variant.name}Implementation",
        project.rootProject.findProject(":lumen-okhttp")
          ?: "${LumenPluginVersion.MAVEN_GROUP}:lumen-okhttp:${LumenPluginVersion.VALUE}",
      )

      val configTask = project.tasks.register(
        "generate${variant.name.replaceFirstChar { it.uppercase() }}LumenConfig",
        GenerateLumenConfigTask::class.java,
      ) { task ->
        task.retentionDays.set(ext.retentionDays)
        task.logPageSize.set(ext.logPageSize)
        task.networkBodyQuotaMb.set(ext.networkBodyQuotaMb)
        task.wsMaxFrames.set(ext.wsMaxFrames)
        task.wsMaxFrameChars.set(ext.wsMaxFrameChars)
        task.mockEnabled.set(ext.mockEnabled)
        task.debugFab.set(ext.debugFab)
        task.debugLogs.set(ext.debugLogs)
        task.outputDirectory.set(
          project.layout.buildDirectory.dir("generated/lumen/${variant.name}/res"),
        )
      }
      variant.sources.res?.addGeneratedSourceDirectory(
        configTask,
        GenerateLumenConfigTask::outputDirectory,
      )

      if (ext.injectOkHttp.get()) {
        variant.instrumentation.transformClassesWith(
          OkHttpVisitorFactory::class.java,
          InstrumentationScope.ALL,
        ) { /* no params */ }
        variant.instrumentation.setAsmFramesComputationMode(
          FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
        )
      }
    }
  }
}

abstract class OkHttpVisitorFactory :
  AsmClassVisitorFactory<InstrumentationParameters.None> {

  override fun createClassVisitor(
    classContext: ClassContext,
    nextClassVisitor: ClassVisitor,
  ): ClassVisitor {
    val className = classContext.currentClassData.className
    return object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
      override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
      ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (className == "okhttp3.OkHttpClient\$Builder" &&
          name == "build" &&
          descriptor == "()Lokhttp3/OkHttpClient;"
        ) {
          return OkHttpBuildAdvice(mv, access, name, descriptor)
        }
        if (className == "okhttp3.OkHttpClient" &&
          name == "newWebSocket" &&
          descriptor == "(Lokhttp3/Request;Lokhttp3/WebSocketListener;)Lokhttp3/WebSocket;"
        ) {
          return OkHttpNewWebSocketAdvice(mv, access, name, descriptor)
        }
        return mv
      }
    }
  }

  override fun isInstrumentable(classData: ClassData): Boolean {
    return classData.className == "okhttp3.OkHttpClient\$Builder" ||
      classData.className == "okhttp3.OkHttpClient"
  }
}

/**
 * At the start of Builder.build(), call
 * `LumenOkHttp.install(builder)` which adds [LumenInterceptor] once as an
 * application interceptor (idempotent).
 */
private class OkHttpBuildAdvice(
  mv: MethodVisitor,
  access: Int,
  name: String,
  descriptor: String,
) : AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {

  override fun onMethodEnter() {
    visitVarInsn(Opcodes.ALOAD, 0)
    visitMethodInsn(
      Opcodes.INVOKESTATIC,
      "dev/lumen/okhttp/LumenOkHttp",
      "install",
      "(Lokhttp3/OkHttpClient\$Builder;)Lokhttp3/OkHttpClient\$Builder;",
      false,
    )
    visitInsn(Opcodes.POP)
  }
}

/**
 * Replace the listener argument of `newWebSocket` with a wrapping
 * [dev.lumen.okhttp.LumenWebSocketListener].
 */
private class OkHttpNewWebSocketAdvice(
  mv: MethodVisitor,
  access: Int,
  name: String,
  descriptor: String,
) : AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {

  override fun onMethodEnter() {
    visitVarInsn(Opcodes.ALOAD, 1) // Request
    visitVarInsn(Opcodes.ALOAD, 2) // WebSocketListener
    visitMethodInsn(
      Opcodes.INVOKESTATIC,
      "dev/lumen/okhttp/LumenOkHttp",
      "wrapWebSocketListener",
      "(Lokhttp3/Request;Lokhttp3/WebSocketListener;)Lokhttp3/WebSocketListener;",
      false,
    )
    visitVarInsn(Opcodes.ASTORE, 2)
  }

  override fun onMethodExit(opcode: Int) {
    if (opcode != Opcodes.ARETURN) return
    // stack: WebSocket result; local 2 is the (already wrapped) listener
    visitVarInsn(Opcodes.ALOAD, 2)
    visitMethodInsn(
      Opcodes.INVOKESTATIC,
      "dev/lumen/okhttp/LumenOkHttp",
      "wrapWebSocket",
      "(Lokhttp3/WebSocket;Lokhttp3/WebSocketListener;)Lokhttp3/WebSocket;",
      false,
    )
  }
}
