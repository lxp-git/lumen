package dev.lumen.ui

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.lumen.common.LogUtil
import dev.lumen.store.EventStore
import java.util.concurrent.Executors

/**
 * Lightweight debug controls without a desktop UI:
 * a persistent notification with actions to switch log segments and export HAR/logs.
 *
 * (A true overlay FAB needs SYSTEM_ALERT_WINDOW; notification actions are zero-permission
 * and enough for P0.)
 */
object LumenDebugFab {
  private const val CHANNEL_ID = "lumen-debug"
  private const val NOTIF_ID = 0x4C554D // LUM
  private const val ACTION_PREV = "dev.lumen.action.LOG_PREV"
  private const val ACTION_NEXT = "dev.lumen.action.LOG_NEXT"
  private const val ACTION_EXPORT_HAR = "dev.lumen.action.EXPORT_HAR"
  private const val ACTION_EXPORT_LOGS = "dev.lumen.action.EXPORT_LOGS"

  @Volatile private var store: EventStore? = null
  private val mainHandler = Handler(Looper.getMainLooper())

  /** Segment listing / export / notification refresh all do file I/O — keep off main. */
  private val ioExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "Lumen-DebugFab").apply { isDaemon = true }
  }

  fun install(application: Application, eventStore: EventStore) {
    store = eventStore
    ensureChannel(application)
    val filter = IntentFilter().apply {
      addAction(ACTION_PREV)
      addAction(ACTION_NEXT)
      addAction(ACTION_EXPORT_HAR)
      addAction(ACTION_EXPORT_LOGS)
    }
    // NOT_EXPORTED on every API level (androidx shims pre-33 with a permission)
    // so other apps cannot fire our actions.
    ContextCompat.registerReceiver(
      application,
      receiver,
      filter,
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityResumed(activity: Activity) = publish(application)
      override fun onActivityCreated(a: Activity, b: Bundle?) {}
      override fun onActivityStarted(a: Activity) {}
      override fun onActivityPaused(a: Activity) {}
      override fun onActivityStopped(a: Activity) {}
      override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
      override fun onActivityDestroyed(a: Activity) {}
    })
    publish(application)
  }

  private fun publish(context: Context) {
    ioExecutor.execute {
      try {
        publishBlocking(context)
      } catch (t: Throwable) {
        LogUtil.w(t, "Lumen notification refresh failed")
      }
    }
  }

  private fun publishBlocking(context: Context) {
    val s = store ?: return
    val segments = s.logs.listSegments()
    val active = s.logs.activeSegmentId ?: "latest"
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun pi(action: String): PendingIntent {
      val intent = Intent(action).setPackage(context.packageName)
      val flags = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
      return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
    }

    val openActivity = Intent(context, LumenLogSegmentsActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val openPi = PendingIntent.getActivity(
      context,
      0,
      openActivity,
      PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0,
    )

    val notif = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_menu_info_details)
      .setContentTitle("Lumen debug")
      .setContentText("Log segment: $active · net ${s.network.allRecords().size} · segs ${segments.size}")
      .setContentIntent(openPi)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .addAction(0, "Log pages", openPi)
      .addAction(0, "Prev log", pi(ACTION_PREV))
      .addAction(0, "Next/Live", pi(ACTION_NEXT))
      .addAction(0, "Export HAR", pi(ACTION_EXPORT_HAR))
      .addAction(0, "Export logs", pi(ACTION_EXPORT_LOGS))
      .build()
    try {
      nm.notify(NOTIF_ID, notif)
    } catch (t: Throwable) {
      LogUtil.w(t, "Lumen notification failed")
    }
  }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
      val action = intent?.action ?: return
      // Everything below reads/writes files; onReceive runs on the main thread.
      ioExecutor.execute {
        try {
          handleAction(context, action)
        } catch (t: Throwable) {
          LogUtil.w(t, "Lumen debug action %s failed", action)
          toast(context, "Lumen action failed: $t")
        }
        publish(context)
      }
    }
  }

  private fun handleAction(context: Context, action: String) {
    val s = store ?: return
    when (action) {
      ACTION_PREV -> {
        val segs = s.logs.listSegments()
        if (segs.isEmpty()) {
          toast(context, "No log segments yet")
          return
        }
        val current = s.logs.activeSegmentId
        val idx = segs.indexOfFirst { it.id == current }.let { if (it < 0) segs.size else it }
        val nextIdx = (idx - 1).coerceAtLeast(0)
        s.logs.setActiveSegment(segs[nextIdx].id)
        toast(context, "Log segment: ${segs[nextIdx].id}")
      }
      ACTION_NEXT -> {
        val segs = s.logs.listSegments()
        val current = s.logs.activeSegmentId
        if (current == null) {
          toast(context, "Already on live tail")
        } else {
          val idx = segs.indexOfFirst { it.id == current }
          if (idx < 0 || idx >= segs.lastIndex) {
            s.logs.setActiveSegment(null)
            toast(context, "Log segment: live")
          } else {
            s.logs.setActiveSegment(segs[idx + 1].id)
            toast(context, "Log segment: ${segs[idx + 1].id}")
          }
        }
      }
      ACTION_EXPORT_HAR -> {
        val file = s.network.exportHarToFile(true)
        toast(context, "HAR: ${file.absolutePath}")
        LogUtil.i("Exported HAR to %s", file.absolutePath)
      }
      ACTION_EXPORT_LOGS -> {
        val file = s.logs.exportBundle()
        toast(context, "Logs: ${file.absolutePath}")
        LogUtil.i("Exported logs to %s", file.absolutePath)
      }
    }
  }

  private fun toast(context: Context, msg: String) {
    mainHandler.post {
      Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
    }
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(CHANNEL_ID, "Lumen debug", NotificationManager.IMPORTANCE_MIN)
    nm.createNotificationChannel(channel)
  }
}
