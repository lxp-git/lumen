package dev.lumen.store

/**
 * File names under `filesDir/lumen/network/`.
 *
 * A launch is keyed `<pid>-<startEpochMs>`, not by pid alone: Android reuses pids, and a
 * pid-only key let a later launch append to an earlier launch's session file and overwrite its
 * bodies. Request ids stay `<pid>.<seq>` ([dev.lumen.inspector.network.NetworkRequestIds]), so an
 * id names its pid but not its launch; [NetworkArchive.find] tries that pid's launches newest
 * first.
 */
internal object NetworkSessionFiles {
  private val stemPattern = Regex("""session-(\d{1,10})(?:-(\d{1,19}))?""")
  private val requestIdPattern = Regex("""(\d{1,10})\.(\d{1,19})""")

  fun launchKey(pid: Int, startMs: Long): String = "$pid-$startMs"

  fun sessionStem(launchKey: String): String = "session-$launchKey"

  fun responseBodyName(launchKey: String, requestId: String): String = "body-$launchKey-$requestId"

  fun requestBodyName(launchKey: String, requestId: String): String = "req-$launchKey-$requestId"

  /**
   * `session-<pid>-<startMs>`, or `session-<pid>` as written before launch keys existed. Only
   * digits pass, so a parsed stem is safe to turn into a path.
   */
  class Stem(val pid: Int, val startMs: Long?) {
    /** Null for pid-only sessions: their body files may belong to another launch. */
    val launchKey: String? get() = startMs?.let { launchKey(pid, it) }
  }

  fun parseStem(stem: String): Stem? {
    val match = stemPattern.matchEntire(stem) ?: return null
    val pid = match.groupValues[1].toIntOrNull() ?: return null
    val start = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
    return Stem(pid, start)
  }

  /** Pid of a captured request id; null for synthetic rows such as `<id>.lumen-ws`. */
  fun requestPid(requestId: String): Int? =
    requestIdPattern.matchEntire(requestId)?.groupValues?.get(1)?.toIntOrNull()

  /** Per-launch sequence number of a captured request id; null for synthetic rows. */
  fun requestSeq(requestId: String): Long? =
    requestIdPattern.matchEntire(requestId)?.groupValues?.get(2)?.toLongOrNull()
}
