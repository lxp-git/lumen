package dev.lumen.inspector.network

/**
 * One synthetic WebSocket row per Engine.IO polling session (`origin` + `sid`).
 * Handshake promotion touches two maps; mutate both under this lock.
 */
internal class EngineIoSessionTable(
  private val nowMs: () -> Long = { System.currentTimeMillis() },
  private val ttlMs: Long = TTL_MS,
  private val maxSessions: Int = MAX_SESSIONS,
) {
  class Session(
    val requestId: String,
    @Volatile var url: String,
    @Volatile var sid: String?,
  ) {
    val created = java.util.concurrent.atomic.AtomicBoolean(false)
    val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var lastSeenMs: Long = 0
  }

  private val pendingByOrigin = HashMap<String, Session>()
  private val byOriginAndSid = HashMap<String, Session>()

  @Synchronized
  fun getOrCreate(url: String, newId: () -> String): Session {
    val now = nowMs()
    val origin = EngineIoPayload.originKey(url)
    val sid = EngineIoPayload.sidFromUrl(url)
    if (sid != null) {
      val key = sidKey(origin, sid)
      byOriginAndSid[key]?.let {
        it.lastSeenMs = now
        return it
      }
      val pending = pendingByOrigin.remove(origin)
      val session = if (pending != null) {
        pending.sid = sid
        pending.url = url
        pending
      } else {
        Session(newId(), url, sid)
      }
      session.lastSeenMs = now
      byOriginAndSid[key] = session
      return session
    }
    val pending = pendingByOrigin[origin]
    if (pending != null && pending.sid == null) {
      pending.lastSeenMs = now
      return pending
    }
    val session = Session(newId(), url, null)
    session.lastSeenMs = now
    pendingByOrigin[origin] = session
    return session
  }

  @Synchronized
  fun attachSid(session: Session, sid: String) {
    if (sid.isEmpty()) return
    val already = session.sid
    if (already != null && already != sid) return
    val origin = EngineIoPayload.originKey(session.url)
    session.sid = sid
    session.lastSeenMs = nowMs()
    pendingByOrigin.remove(origin, session)
    byOriginAndSid.putIfAbsent(sidKey(origin, sid), session)
  }

  /** Stale and overflow sessions the caller should close in Chrome. */
  @Synchronized
  fun evictStale(): List<Session> {
    val now = nowMs()
    val victims = LinkedHashSet<Session>()
    for (session in pendingByOrigin.values) {
      if (now - session.lastSeenMs >= ttlMs) victims.add(session)
    }
    for (session in byOriginAndSid.values) {
      if (now - session.lastSeenMs >= ttlMs) victims.add(session)
    }
    val live = pendingByOrigin.size + byOriginAndSid.size - victims.size
    if (live > maxSessions) {
      val remaining = ArrayList<Session>(pendingByOrigin.size + byOriginAndSid.size)
      remaining.addAll(pendingByOrigin.values)
      remaining.addAll(byOriginAndSid.values)
      remaining.removeAll(victims)
      remaining.sortBy { it.lastSeenMs }
      val extra = live - maxSessions
      for (i in 0 until extra) victims.add(remaining[i])
    }
    val dropped = ArrayList<Session>(victims.size)
    for (session in victims) {
      if (session.closed.get()) continue
      val origin = EngineIoPayload.originKey(session.url)
      pendingByOrigin.remove(origin, session)
      session.sid?.let { byOriginAndSid.remove(sidKey(origin, it), session) }
      dropped.add(session)
    }
    return dropped
  }

  @Synchronized
  fun close(session: Session): Boolean {
    if (!session.closed.compareAndSet(false, true)) return false
    val origin = EngineIoPayload.originKey(session.url)
    pendingByOrigin.remove(origin, session)
    session.sid?.let { byOriginAndSid.remove(sidKey(origin, it), session) }
    return true
  }

  @Synchronized
  fun reset() {
    pendingByOrigin.clear()
    byOriginAndSid.clear()
  }

  private fun sidKey(origin: String, sid: String): String = "$origin\u0000$sid"

  private companion object {
    const val TTL_MS = 90_000L
    const val MAX_SESSIONS = 64
  }
}
