package dev.lumen.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogCatLineTest {
  @Test
  fun parsesDebugAsVerboseAndKeepsText() {
    val entry = LogCatLine.parse(
      "1787041095.123  5194  6109 D FGAppsFlyer: Conversion data success: {af_status=Organic}",
      lastTimestampMs = 0.0,
    )
    assertNotNull(entry)
    assertEquals("verbose", entry!!.level)
    assertEquals("FGAppsFlyer(5194): Conversion data success: {af_status=Organic}", entry.text)
    assertEquals(1_787_041_095_123.0, entry.timestampMs, 0.0)
  }

  @Test
  fun mapsPriorities() {
    assertEquals("verbose", parseLevel("V", "tag: v"))
    assertEquals("verbose", parseLevel("D", "tag: d"))
    assertEquals("info", parseLevel("I", "tag: i"))
    assertEquals("warning", parseLevel("W", "tag: w"))
    assertEquals("error", parseLevel("E", "tag: e"))
    assertEquals("error", parseLevel("F", "tag: f"))
  }

  @Test
  fun chromePromotesVerboseSoDefaultLevelsShowDebug() {
    assertEquals("info", LogCatLine.chromeLevel("verbose"))
    assertEquals("info", LogCatLine.chromeLevel("info"))
    assertEquals("warning", LogCatLine.chromeLevel("warning"))
    assertEquals("error", LogCatLine.chromeLevel("error"))
  }

  @Test
  fun dropsLumenTags() {
    assertTrue(LogCatLine.isSuppressedTag("LumenFetch"))
    assertTrue(LogCatLine.isSuppressedTag("LumenCDP"))
    assertTrue(LogCatLine.isSuppressedTag("LumenWS"))
    assertTrue(LogCatLine.isSuppressedTag("lumen"))
    assertTrue(LogCatLine.isSuppressedTag("CLog"))
    assertTrue(LogCatLine.isSuppressedTag("MockEngine"))
    assertFalse(LogCatLine.isSuppressedTag("FGAppsFlyer"))
    assertNull(
      LogCatLine.parse(
        "1787041095.123  5194  6109 I LumenFetch: resume fetchId=1 decision=Continue",
        lastTimestampMs = 0.0,
      ),
    )
  }

  @Test
  fun unparsedLineStaysInfo() {
    val entry = LogCatLine.parse("not a logcat line", lastTimestampMs = 42.0)
    assertEquals("info", entry!!.level)
    assertEquals("not a logcat line", entry.text)
    assertEquals(42.0, entry.timestampMs, 0.0)
  }

  private fun parseLevel(priority: String, rest: String): String {
    val entry = LogCatLine.parse(
      "1787041095.000  1  2 $priority $rest",
      lastTimestampMs = 0.0,
    )
    return entry!!.level
  }
}
