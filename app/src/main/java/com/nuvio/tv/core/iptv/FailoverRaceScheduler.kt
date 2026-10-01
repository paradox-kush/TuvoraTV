package com.nuvio.tv.core.iptv

/**
 * Step 0.3b — constants of the staggered ("happy eyeballs", RFC 8305 §5) failover race.
 *
 * RFC 8305 recommends a 250 ms Connection Attempt Delay with a 100 ms minimum (never < 10 ms) and a
 * 2 s maximum, for a TCP SYN. Ours is the gap between two HTTPS API round-trips over the WAN, so the
 * numbers are larger on purpose (see [FailoverStagger]).
 */
object FailoverRace {
    /** At most this many attempts count as in flight at once. */
    const val MAX_IN_FLIGHT: Int = 3

    /** An attempt older than this without a result no longer counts toward [MAX_IN_FLIGHT]. */
    const val HANG_CUTOFF_MS: Long = 12_000L

    /** Per-attempt CONNECT timeout while a playlist has backups (read/overall timeouts unchanged). */
    const val CONNECT_TIMEOUT_MS: Long = 8_000L
}

/** What [FailoverRaceScheduler] tells its executor to do. Server numbers are indexes into the playlist's server list. */
sealed interface RaceDecision {
    /** Start an attempt against this server now. */
    data class StartAttempt(val server: Int) : RaceDecision

    /** Cancel these in-flight attempts at once (before any body is read). Never a failure of theirs. */
    data class CancelAttempts(val servers: Set<Int>) : RaceDecision

    /** This attempt decided the race: a valid answer, or a definitive failure that must surface (no failover). */
    data class Winner(val server: Int) : RaceDecision

    /** Every server failed with a fail-over-able failure. Surface [surfaceServer]'s error (the main server's when it failed). */
    data class GiveUp(val surfaceServer: Int) : RaceDecision
}

/**
 * Step 0.3b — the pure, virtual-time state machine behind staggered parallel failover.
 *
 * Attempts are tried in [order]. Attempt k is STARTED at the earliest of
 *  (a) attempt k-1 FAILED with a fail-over-able failure, or
 *  (b) its stagger elapsed since k-1 started while k-1 has produced nothing,
 * subject to: at most [maxInFlight] attempts in flight (an attempt older than [hangCutoffMs] no longer
 * counts, but stays eligible to win), and never two concurrent attempts against the same host.
 *
 * The first attempt to report a definitive answer wins ([onHeaders], or [onFailed] with
 * `failsOver = false` for a failure that must surface): every other running attempt is cancelled at once
 * and no pending one ever starts. Losers are never failures — the scheduler reports nothing about them.
 *
 * An attempt's clock (stagger, hang cutoff) runs only while it is the SERVER's turn: local waits reported
 * through [onLocalWait] stop it.
 *
 * No I/O, no coroutines, no clock: the caller passes `nowMs` (any monotonic base) and asks
 * [nextWakeMs] when to call [onTick] next. TV twin of NuvioMobile/NuvioDesktop `FailoverRaceScheduler` — identical logic, pinned by the shared golden `FailoverRaceGolden`.
 */
class FailoverRaceScheduler(
    order: List<Int>,
    private val hostOf: (Int) -> String,
    private val staggerOf: (Int) -> Long,
    private val maxInFlight: Int = FailoverRace.MAX_IN_FLIGHT,
    private val hangCutoffMs: Long = FailoverRace.HANG_CUTOFF_MS,
) {
    private enum class Phase { PENDING, RUNNING, FAILED, CANCELLED, WON }

    private class Attempt(val server: Int, val host: String) {
        var phase = Phase.PENDING
        var startedAtMs = 0L
        var staggerMs = 0L
        /** Time this attempt spent waiting on LOCAL queues (a connection gate, playback deferral): not the server's latency. */
        var localWaitMs = 0L
        var localWaitDepth = 0
        var localWaitSinceMs = 0L

        /** Milliseconds the server has had to answer: elapsed time minus every local wait (running or finished). */
        fun serverAgeMs(nowMs: Long): Long {
            val running = if (localWaitDepth > 0) nowMs - localWaitSinceMs else 0L
            return nowMs - startedAtMs - localWaitMs - running
        }
    }

    private val attempts = order.distinct().map { Attempt(it, hostOf(it)) }
    private var last: Attempt? = null
    private var winner: Attempt? = null
    private var gaveUp = false
    private val failedInOrder = ArrayList<Int>()

    /** True once a [RaceDecision.Winner] or [RaceDecision.GiveUp] was emitted: every later event is ignored. */
    val isFinished: Boolean get() = winner != null || gaveUp

    /** The race clock advanced to [nowMs]: starts whatever the rules now allow (the first call starts attempt 0). */
    fun onTick(nowMs: Long): List<RaceDecision> = settle(nowMs)

    /** [server] produced a definitive, valid answer (response headers for a real request, a valid probe). */
    fun onHeaders(nowMs: Long, server: Int): List<RaceDecision> {
        if (isFinished) return emptyList()
        val a = running(server) ?: return emptyList()
        return decide(a)
    }

    /**
     * [server]'s attempt failed. [failsOver] = the failure classifier says another server may answer
     * differently; false = a definitive failure (401/403/456, auth rejected, …) that is surfaced as the
     * race's result — it wins, exactly like a valid answer, and the others are cancelled.
     */
    fun onFailed(nowMs: Long, server: Int, failsOver: Boolean): List<RaceDecision> {
        if (isFinished) return emptyList()
        val a = running(server) ?: return emptyList()
        if (!failsOver) return decide(a)
        a.phase = Phase.FAILED
        failedInOrder += server
        return settle(nowMs)
    }

    /**
     * [server]'s attempt started ([waiting] = true) or finished ([waiting] = false) waiting on a LOCAL
     * queue — a per-portal connection gate, "hold browse traffic while a stream plays". That time is not
     * the server being slow, so it does not run the stagger clock or the hang cutoff: a healthy main
     * that merely queued behind its own siblings must never hand the request to a backup.
     */
    fun onLocalWait(nowMs: Long, server: Int, waiting: Boolean): List<RaceDecision> {
        if (isFinished) return emptyList()
        val a = running(server) ?: return emptyList()
        if (waiting) {
            if (a.localWaitDepth++ == 0) a.localWaitSinceMs = nowMs
            return emptyList()
        }
        if (a.localWaitDepth == 0) return emptyList()
        if (--a.localWaitDepth == 0) a.localWaitMs += nowMs - a.localWaitSinceMs
        return settle(nowMs)
    }

    /** The caller cancelled [server] on its own (not as a result of a decision here). Not a failure. */
    fun onCancelled(nowMs: Long, server: Int): List<RaceDecision> {
        if (isFinished) return emptyList()
        val a = running(server) ?: return emptyList()
        a.phase = Phase.CANCELLED
        return settle(nowMs)
    }

    /** The absolute time (same base as `nowMs`) at which [onTick] could next start something; null = wait for an event. */
    fun nextWakeMs(nowMs: Long): Long? {
        if (isFinished || attempts.none { it.phase == Phase.PENDING }) return null
        var wake: Long? = null
        fun offer(t: Long) { if (t > nowMs && (wake == null || t < wake!!)) wake = t }
        // A locally-waiting attempt has no deadline: its clock is stopped until the wait ends.
        last?.takeIf { it.phase == Phase.RUNNING && it.localWaitDepth == 0 }
            ?.let { offer(nowMs + (it.staggerMs - it.serverAgeMs(nowMs))) }
        if (counted(nowMs) >= maxInFlight) {
            attempts.filter { it.phase == Phase.RUNNING && it.localWaitDepth == 0 && it.serverAgeMs(nowMs) < hangCutoffMs }
                .forEach { offer(nowMs + (hangCutoffMs - it.serverAgeMs(nowMs))) }
        }
        return wake
    }

    private fun running(server: Int) = attempts.firstOrNull { it.server == server && it.phase == Phase.RUNNING }

    private fun counted(nowMs: Long) =
        attempts.count { it.phase == Phase.RUNNING && it.serverAgeMs(nowMs) < hangCutoffMs }

    private fun decide(a: Attempt): List<RaceDecision> {
        val out = ArrayList<RaceDecision>(2)
        val losers = attempts.filter { it !== a && it.phase == Phase.RUNNING }
        losers.forEach { it.phase = Phase.CANCELLED }
        attempts.filter { it.phase == Phase.PENDING }.forEach { it.phase = Phase.CANCELLED }   // never started
        if (losers.isNotEmpty()) out += RaceDecision.CancelAttempts(losers.map { it.server }.toSet())
        a.phase = Phase.WON
        winner = a
        out += RaceDecision.Winner(a.server)
        return out
    }

    private fun settle(nowMs: Long): List<RaceDecision> {
        if (isFinished) return emptyList()
        val out = ArrayList<RaceDecision>(2)
        while (true) {
            val running = attempts.filter { it.phase == Phase.RUNNING }
            val next = attempts.firstOrNull { cand -> cand.phase == Phase.PENDING && running.none { it.host == cand.host } }
                ?: break
            val prev = last
            val due = prev == null ||
                prev.phase == Phase.FAILED || prev.phase == Phase.CANCELLED ||
                (prev.phase == Phase.RUNNING && prev.localWaitDepth == 0 && prev.serverAgeMs(nowMs) >= prev.staggerMs)
            if (!due || counted(nowMs) >= maxInFlight) break
            next.phase = Phase.RUNNING
            next.startedAtMs = nowMs
            next.staggerMs = staggerOf(next.server).coerceAtLeast(0L)
            last = next
            out += RaceDecision.StartAttempt(next.server)
        }
        if (attempts.none { it.phase == Phase.RUNNING || it.phase == Phase.PENDING }) {
            gaveUp = true
            out += RaceDecision.GiveUp(if (0 in failedInOrder) 0 else failedInOrder.lastOrNull() ?: 0)
        }
        return out
    }
}
