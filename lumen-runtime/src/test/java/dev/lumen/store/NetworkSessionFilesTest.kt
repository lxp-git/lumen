package dev.lumen.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkSessionFilesTest {

  @Test
  fun stemsCarryPidAndLaunchTime() {
    val stem = NetworkSessionFiles.parseStem("session-4242-1792722723387")!!
    assertEquals(4242, stem.pid)
    assertEquals(1_792_722_723_387L, stem.startMs)
    assertEquals("4242-1792722723387", stem.launchKey)
    assertEquals(
      "session-4242-1792722723387",
      NetworkSessionFiles.sessionStem(NetworkSessionFiles.launchKey(4242, 1_792_722_723_387L)),
    )
  }

  @Test
  fun pidOnlyStemsHaveNoLaunchKey() {
    val stem = NetworkSessionFiles.parseStem("session-4242")!!
    assertEquals(4242, stem.pid)
    assertNull(stem.launchKey)
  }

  @Test
  fun anythingButDigitsIsRejected() {
    for (bad in listOf("session-../../x", "session-4242-12/..", "session-", "4242-1", "session-1-2-3",
      "session-99999999999")) {
      assertNull(bad, NetworkSessionFiles.parseStem(bad))
    }
  }

  @Test
  fun requestIdsNameTheirPid() {
    assertEquals(4242, NetworkSessionFiles.requestPid("4242.17"))
    assertEquals(17L, NetworkSessionFiles.requestSeq("4242.17"))
    assertNull(NetworkSessionFiles.requestPid("4242.17.lumen-ws"))
    assertNull(NetworkSessionFiles.requestSeq("ws-4242"))
  }

  @Test
  fun bodyFilesAreKeyedByLaunch() {
    assertEquals("body-4242-5-4242.17", NetworkSessionFiles.responseBodyName("4242-5", "4242.17"))
    assertEquals("req-4242-5-4242.17", NetworkSessionFiles.requestBodyName("4242-5", "4242.17"))
  }
}
