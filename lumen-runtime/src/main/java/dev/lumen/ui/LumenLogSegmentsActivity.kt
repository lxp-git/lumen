package dev.lumen.ui

import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import dev.lumen.LumenAgent

/**
 * In-app picker for the 7-day logcat archive. Stock Chrome Console can only
 * hold one page; this is how you flip pages. Also startable with:
 *
 * `adb shell am start -n &lt;pkg&gt;/dev.lumen.ui.LumenLogSegmentsActivity`
 */
class LumenLogSegmentsActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    title = "Lumen log segments"
    rebuild()
  }

  override fun onResume() {
    super.onResume()
    rebuild()
  }

  private fun rebuild() {
    val store = LumenAgent.store
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      val pad = dp(16)
      setPadding(pad, pad, pad, pad)
    }

    val hint = TextView(this).apply {
      text = "Chrome Console only shows one page. Pick a segment to replay. " +
        "Or: adb shell content call --uri content://$packageName.lumen-adb " +
        "--method setActiveLogSegment --arg seg-N"
      textSize = 13f
    }
    root.addView(hint)

    if (store == null) {
      root.addView(TextView(this).apply { text = "Lumen agent is not started." })
      setContentView(ScrollView(this).apply { addView(root) })
      return
    }

    val active = store.logs.activeSegmentId
    root.addView(segmentButton(LumenShell.formatLive(active == null), active == null) {
      apply("live")
    })
    for (seg in store.logs.listSegments()) {
      val selected = seg.id == active
      root.addView(segmentButton(LumenShell.formatSeg(seg, selected), selected) {
        apply(seg.id)
      })
    }

    setContentView(ScrollView(this).apply { addView(root) })
  }

  private fun segmentButton(label: String, selected: Boolean, onClick: () -> Unit): AppCompatButton {
    return AppCompatButton(this).apply {
      text = label
      isAllCaps = false
      textAlignment = TextView.TEXT_ALIGNMENT_VIEW_START
      if (selected) {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.colorPrimary, tv, true)
        setTextColor(if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT) tv.data else 0xFF1565C0.toInt())
      }
      setOnClickListener { onClick() }
    }
  }

  private fun apply(raw: String) {
    val msg = LumenShell.setActiveSegment(raw)
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    rebuild()
  }

  private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
