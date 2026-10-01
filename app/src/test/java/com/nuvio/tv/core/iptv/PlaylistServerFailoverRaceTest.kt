package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.FailoverRaceGolden.Behavior
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * Step 0.3b — the staggered failover EXECUTOR in virtual time (`runTest`), driven by the same golden
 * scenarios as the pure scheduler, plus the executor-only guarantees: one big request at most, losers
 * cancelled without side effects, definitive answers surfaced, structured concurrency. TV twin of
 * NuvioMobile's commonTest PlaylistServerFailoverRaceTest (JUnit: `assertEquals(message, expected, actual)`),
 * plus the TV-only stale-catalog cases.
 */
class PlaylistServerFailoverRaceTest {

    private var store = InMemoryServerFailoverStateStore()
    private var wallNow = 1_000_000L

    private fun accountOf(count: Int, hosts: Map<Int, String> = emptyMap()): XtreamAccount {
        fun url(i: Int) = "http://${hosts[i] ?: "s$i.test"}:${1000 + i}"
        return XtreamAccount(
            id = "race|u", name = "P", baseUrl = url(0), username = "u", password = "p",
            backupUrls = (1 until count).map(::url),
        )
    }

    private fun TestScope.failover() =
        PlaylistServerFailover(store, clock = { wallNow }, profileId = { 1 }, raceClock = { testScheduler.currentTime })

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.simpleName}, got $t", t)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    /** A fake panel world: every server follows a [Behavior]; everything it does is recorded. */
    private inner class World(
        val failover: PlaylistServerFailover,
        val acc: XtreamAccount,
        val behaviors: Map<Int, Behavior>,
        val realAfterProbeMs: Long = 100,
    ) {
        val startedAt = LinkedHashMap<Int, Long>()
        val raceStarts = LinkedHashMap<Int, String>()            // server -> "real" | "probe"
        val cancelled = LinkedHashSet<Int>()
        val active = LinkedHashSet<Int>()
        var bodyReads = 0
        var realRequests = 0
        var probeRequests = 0
        var maxCounted = 0
        var hostOverlap = false
        val connectTimeouts = mutableListOf<Long?>()
        var scope: TestScope? = null

        private fun indexOf(a: XtreamAccount) = failover.servers(acc).indexOf(a.baseUrl)

        private fun begin(index: Int, kind: String) {
            val now = scope!!.testScheduler.currentTime
            if (kind != "followup") {
                startedAt[index] = now
                raceStarts[index] = kind
            }
            val host = FailoverHostKey.of(failover.servers(acc)[index])
            if (active.any { FailoverHostKey.of(failover.servers(acc)[it]) == host }) hostOverlap = true
            active += index
            maxCounted = maxOf(maxCounted, active.count { it !in startedAt || now - startedAt.getValue(it) < FailoverRace.HANG_CUTOFF_MS })
        }

        private suspend fun behave(index: Int, b: Behavior, real: Boolean): String {
            try {
                when (b) {
                    is Behavior.Hang -> { delay(b.failsAfterMs); throw HttpStatusException(504, "timeout s$index") }
                    is Behavior.Fail -> { delay(b.afterMs); throw HttpStatusException(503, "refused s$index") }
                    is Behavior.Invalid -> { delay(b.afterMs); throw FailoverInvalidResponseException("html s$index") }
                    is Behavior.Definitive -> {
                        delay(b.afterMs)
                        throw if (real) HttpStatusException(401, "401 s$index") else FailoverAuthRejectedException("auth=0 s$index")
                    }
                    is Behavior.Ok -> {
                        delay(b.afterMs)
                        if (real) { signalHttpHeaders(); bodyReads++ }
                        return "real:$index"
                    }
                    is Behavior.Queued -> {
                        awaitingLocally { delay(b.queuedMs) }
                        return behave(index, b.then, real)
                    }
                }
            } catch (c: CancellationException) {
                cancelled += index
                throw c
            } finally {
                active -= index
            }
        }

        val probe: suspend (XtreamAccount) -> Unit = { a ->
            val i = indexOf(a)
            probeRequests++
            connectTimeouts += failoverConnectTimeoutMs()
            begin(i, "probe")
            behave(i, behaviors.getValue(i), real = false)
        }

        val attempt: suspend (XtreamAccount) -> String = { a ->
            val i = indexOf(a)
            connectTimeouts += failoverConnectTimeoutMs()
            realRequests++
            if (i in startedAt || raceStarts.isNotEmpty() && raceStarts[i] != null) {
                // the follow-up real request on a probe's winner
                begin(i, "followup")
                try {
                    delay(realAfterProbeMs)
                    signalHttpHeaders()
                    bodyReads++
                    "real:$i"
                } finally {
                    active -= i
                }
            } else {
                begin(i, "real")
                behave(i, behaviors.getValue(i), real = true)
            }
        }
    }

    private fun staggerStats(scenario: FailoverRaceGolden.Scenario): ServerFailoverState {
        val stats = scenario.staggers.mapValues { (_, ms) ->
            if (ms == FailoverStagger.RECENT_FAILURE_MS) ServerLatencyStats(lastFailAtMs = wallNow - 1_000)
            else ServerLatencyStats(ewmaMs = (ms / FailoverStagger.EWMA_FACTOR).toLong(), samples = 3, lastSampleAtMs = wallNow - 1_000)
        }
        return ServerFailoverState(stats = stats)
    }

    @Test
    fun `every golden scenario plays out identically on the executor`() {
        for (scenario in FailoverRaceGolden.scenarios) {
            runTest {
                store = InMemoryServerFailoverStateStore()
                val failover = failover()
                val acc = accountOf(scenario.order.size, scenario.hosts)
                store.write(1, acc.id, staggerStats(scenario))
                val world = World(failover, acc, scenario.behaviors).also { it.scope = this }
                val outcome = runCatching { failover.run(acc, probe = world.probe, attempt = world.attempt) }
                val e = scenario.expected
                val label = scenario.name
                val elapsed = testScheduler.currentTime
                when {
                    e.winner == null -> {
                        assertTrue("$label: gives up", outcome.isFailure)
                        assertEquals("$label: gave up at", e.decidedAtMs, elapsed)
                        assertTrue("$label: surfaces s${e.surface}'s error, got ${outcome.exceptionOrNull()}", outcome.exceptionOrNull()!!.message!!.contains("s${e.surface}"))
                    }
                    e.definitive -> {
                        assertTrue("$label: surfaces the definitive answer", outcome.isFailure)
                        assertTrue(label, outcome.exceptionOrNull() !is CancellationException)
                        assertEquals("$label: surfaced at", e.decidedAtMs, elapsed)
                        assertTrue(label, outcome.exceptionOrNull()!!.message!!.contains("s${e.winner}"))
                    }
                    else -> {
                        assertEquals("$label: served by (${outcome.exceptionOrNull()})", "real:${e.winner}", outcome.getOrNull())
                        val total = e.decidedAtMs + if (e.winner == scenario.order.first()) 0 else world.realAfterProbeMs
                        assertEquals("$label: total virtual time", total, elapsed)
                    }
                }
                assertEquals("$label: attempt start times", e.startedAtMs, world.startedAt.toMap())
                assertEquals("$label: cancelled losers", e.cancelled, world.cancelled)
                assertEquals("$label: max counted in flight", e.maxInFlight, world.maxCounted)
                assertFalse("$label: never two concurrent attempts on one host", world.hostOverlap)
                assertTrue("$label: nothing left running (no leaked coroutine)", world.active.isEmpty())
                assertTrue("$label: at most one big body ever read (was ${world.bodyReads})", world.bodyReads <= 1)
            }
        }
    }

    // --- the timing claims the old sequential walk failed (red log: scratchpad step03b_tv/red_sequential.log) ---

    @Test
    fun `c - main hangs and backup 1 is healthy - served within a few seconds, not after 60 s`() = runTest {
        val failover = failover()
        val acc = accountOf(5)
        val world = World(failover, acc, mapOf(0 to Behavior.Hang(), 1 to Behavior.Ok(150))).also { it.scope = this }
        assertEquals("real:1", failover.run(acc, probe = world.probe, attempt = world.attempt))
        assertEquals("stagger 1500 + probe 150 + real 100", 1_750L, testScheduler.currentTime)
    }

    @Test
    fun `d - five servers dead at the 8 s connect timeout - gives up in about two waves`() = runTest {
        val failover = failover()
        val acc = accountOf(5)
        val world = World(failover, acc, (0..4).associateWith { Behavior.Fail(8_000) }).also { it.scope = this }
        runCatching { failover.run(acc, probe = world.probe, attempt = world.attempt) }
        assertTrue("took ${testScheduler.currentTime} ms for 5 dead servers", testScheduler.currentTime <= 20_000)
    }

    @Test
    fun `e - three hang then backup 3 is healthy - served by 12 s`() = runTest {
        val failover = failover()
        val acc = accountOf(5)
        val world = World(failover, acc, mapOf(0 to Behavior.Hang(), 1 to Behavior.Hang(), 2 to Behavior.Hang(), 3 to Behavior.Ok(100)))
            .also { it.scope = this }
        assertEquals("real:3", failover.run(acc, probe = world.probe, attempt = world.attempt))
        assertTrue("took ${testScheduler.currentTime} ms", testScheduler.currentTime <= 12_500)
    }

    // --- a: the common path ---------------------------------------------------------------

    @Test
    fun `a healthy main makes exactly one request and no probe`() = runTest {
        val failover = failover()
        val acc = accountOf(3)
        val world = World(failover, acc, mapOf(0 to Behavior.Ok(120))).also { it.scope = this }
        val served = failover.run(acc, probe = world.probe, attempt = world.attempt)
        assertEquals("real:0", served)
        assertEquals("exactly one request", 1, world.realRequests)
        assertEquals("no probe", 0, world.probeRequests)
        assertEquals(1, world.bodyReads)
        assertEquals(120L, testScheduler.currentTime)
        assertEquals(0, failover.activeIndex(acc))
    }

    @Test
    fun `a healthy active backup inside its window is one request to the backup and nothing to main`() = runTest {
        val failover = failover()
        val acc = accountOf(3)
        store.write(1, acc.id, ServerFailoverState(activeIndex = 1, mainRetryAfterMs = wallNow + 60_000))
        val world = World(failover, acc, mapOf(1 to Behavior.Ok(90))).also { it.scope = this }
        assertEquals("real:1", failover.run(acc, probe = world.probe, attempt = world.attempt))
        assertEquals(1, world.realRequests)
        assertEquals(0, world.probeRequests)
        assertEquals(setOf(1), world.startedAt.keys)
    }

    // --- c: cancelled losers are not failures ---------------------------------------------

    @Test
    fun `a hung main that loses the race is not marked failed and does not trip the breaker`() = runTest {
        val failover = failover()
        val acc = accountOf(2)
        val mainUrl = acc.baseUrl
        failover.run(
            acc,
            probe = { delay(150) },
            attempt = { a ->
                if (a.baseUrl == mainUrl) {
                    // The real production shape: the request runs inside the breaker's guard.
                    IptvPanelGuard.guard.guardedPanelRequest(a.baseUrl) { awaitCancellation() }
                } else { delay(100); a.baseUrl }
            },
        )
        val state = store.read(1, acc.id)
        assertEquals("the winner becomes active", 1, state.activeIndex)
        assertNull("a cancelled loser is not a failure: no lastFailAt", state.stats[0]?.lastFailAtMs)
        // Hammering main's admission after the cancel must still be Allowed (a counted failure would open it at 2).
        repeat(3) { assertTrue(IptvPanelGuard.guard.admit(mainUrl) is PanelAdmission.Allowed) }
    }

    @Test
    fun `a failed main IS marked failed so its successor starts almost at once next time`() = runTest {
        val failover = failover()
        val acc = accountOf(2)
        val world = World(failover, acc, mapOf(0 to Behavior.Fail(20), 1 to Behavior.Ok(50))).also { it.scope = this }
        failover.run(acc, probe = world.probe, attempt = world.attempt)
        val stats = store.read(1, acc.id).stats
        assertEquals("main failed (fail-over-able): recorded", wallNow, stats[0]?.lastFailAtMs)
        assertEquals(FailoverStagger.RECENT_FAILURE_MS, FailoverStagger.compute(stats[0], wallNow))
        assertNull(stats[1]?.lastFailAtMs)
        assertEquals("the winner's time-to-valid became its first sample", 50L, stats[1]?.ewmaMs)
    }

    // --- f / m: definitive answers ---------------------------------------------------------

    @Test
    fun `a 401 from the first responder is surfaced immediately and nothing else is started`() = runTest {
        val failover = failover()
        val acc = accountOf(3)
        val world = World(failover, acc, mapOf(0 to Behavior.Definitive(80))).also { it.scope = this }
        val e = assertThrows<HttpStatusException> { failover.run(acc, probe = world.probe, attempt = world.attempt) }
        assertEquals(401, e.status)
        assertEquals(80L, testScheduler.currentTime)
        assertEquals(setOf(0), world.startedAt.keys)
        assertEquals("a definitive answer changes nothing", ServerFailoverState(), store.read(1, acc.id))
    }

    @Test
    fun `a probe that says auth=0 surfaces at once without failing over and cancels the hung main`() = runTest {
        val failover = failover()
        val acc = accountOf(3)
        val world = World(failover, acc, mapOf(0 to Behavior.Hang(), 1 to Behavior.Definitive(150), 2 to Behavior.Ok(10))).also { it.scope = this }
        assertThrows<FailoverAuthRejectedException> { failover.run(acc, probe = world.probe, attempt = world.attempt) }
        assertEquals(1_650L, testScheduler.currentTime)
        assertEquals("backup 2 never starts", setOf(0, 1), world.startedAt.keys)
        assertEquals(setOf(0), world.cancelled)
        assertEquals(0, store.read(1, acc.id).activeIndex)
    }

    // --- k: a parked domain never wins -----------------------------------------------------

    @Test
    fun `a probe that gets 200 HTML does not win and the next server is tried`() = runTest {
        val failover = failover()
        val acc = accountOf(3)
        val world = World(failover, acc, mapOf(0 to Behavior.Fail(20), 1 to Behavior.Invalid(60), 2 to Behavior.Ok(40))).also { it.scope = this }
        assertEquals("real:2", failover.run(acc, probe = world.probe, attempt = world.attempt))
        assertEquals(2, failover.activeIndex(acc))
        assertTrue("the parked domain is remembered as failed", store.read(1, acc.id).stats[1]?.lastFailAtMs != null)
    }

    // --- h: one big request ever -----------------------------------------------------------

    @Test
    fun `probe plus real is at most one big body even when two attempts answer at the same instant`() = runTest {
        val failover = failover()
        val acc = accountOf(2)
        // Main's real headers and backup 1's valid probe both land at t=1650.
        val world = World(failover, acc, mapOf(0 to Behavior.Ok(1_650), 1 to Behavior.Ok(150))).also { it.scope = this }
        val served = failover.run(acc, probe = world.probe, attempt = world.attempt)
        assertTrue(served, served == "real:0" || served == "real:1")
        assertEquals("the loser's body is never read: exactly one big body in total", 1, world.bodyReads)
        assertTrue(world.active.isEmpty())
    }

    // --- a winner whose real request then fails continues the race --------------------------

    @Test
    fun `a valid probe whose real request then fails continues with the remaining servers`() = runTest {
        val failover = failover()
        val acc = accountOf(3)
        val tried = mutableListOf<String>()
        val served = failover.run(
            acc,
            probe = { a -> if (a.baseUrl == acc.baseUrl) throw HttpStatusException(503, "main down") else delay(30) },
            attempt = { a ->
                tried += a.baseUrl
                when (a.baseUrl) {
                    acc.baseUrl -> throw HttpStatusException(503, "main down")
                    acc.backupUrls!![0] -> throw HttpStatusException(500, "catalog broke on b1")
                    else -> a.baseUrl
                }
            },
        )
        assertEquals(acc.backupUrls!![1], served)
        assertEquals("main, b1 (probe ok, catalog failed), b2", listOf(acc.baseUrl, acc.backupUrls!![0], acc.backupUrls!![1]), tried)
        assertEquals(2, failover.activeIndex(acc))
    }

    @Test
    fun `when every server fails the main server's error is surfaced and the state is kept`() = runTest {
        val failover = failover()
        val acc = accountOf(3)
        val mainErr = HttpStatusException(502, "main 502")
        val thrown = assertThrows<HttpStatusException> {
            failover.run(
                acc,
                probe = { throw HttpStatusException(503, "probe down") },
                attempt = { a -> throw if (a.baseUrl == acc.baseUrl) mainErr else HttpStatusException(504, "other") },
            )
        }
        assertEquals(mainErr, thrown)
        assertEquals(0, store.read(1, acc.id).activeIndex)
    }

    // --- an attempt that delivered rows is never replayed ------------------------------------

    @Test
    fun `a real request that already delivered rows is not replayed on another server`() = runTest {
        val failover = failover()
        val acc = accountOf(3)
        var delivered = false
        val probes = mutableListOf<String>()
        assertThrows<HttpStatusException> {
            failover.run(
                acc,
                canRetry = { !delivered },
                probe = { a -> probes += a.baseUrl },
                attempt = { delivered = true; throw HttpStatusException(503, "mid-body") },
            )
        }
        assertTrue("no probe started: the failure was not fail-over-able any more", probes.isEmpty())
    }

    // --- TV: the 7-day stale-catalog rule under a race ---------------------------------------

    @Test
    fun `a stale stand-in for a dead main is held unread while a backup answers live`() = runTest {
        val failover = failover()
        val acc = accountOf(3)
        val served = failover.run(
            acc,
            evidence = { r: String -> if (r.startsWith("stale")) ServedFrom.STALE_FALLBACK else ServedFrom.NETWORK },
            probe = { delay(40) },
            attempt = { a -> if (a.baseUrl == acc.baseUrl) { delay(20); "stale-main" } else { delay(60); "live:${a.baseUrl}" } },
        )
        assertEquals("the backup's live answer beats main's stale copy", "live:${acc.backupUrls!![0]}", served)
        assertEquals(1, failover.activeIndex(acc))
        assertTrue("main's host failed (a stale copy stood in): remembered", store.read(1, acc.id).stats[0]?.lastFailAtMs != null)
    }

    @Test
    fun `the stale copy is the last resort when every server fails`() = runTest {
        val failover = failover()
        val acc = accountOf(3)
        val served = failover.run(
            acc,
            evidence = { r: String -> if (r.startsWith("stale")) ServedFrom.STALE_FALLBACK else ServedFrom.NETWORK },
            probe = { throw java.net.ConnectException("down") },
            attempt = { a -> if (a.baseUrl == acc.baseUrl) "stale-main" else throw java.net.ConnectException("down") },
        )
        assertEquals("stale-main", served)
        assertEquals("the active server is kept", 0, store.read(1, acc.id).activeIndex)
    }

    @Test
    fun `a fresh cache hit wins without probing and teaches nothing`() = runTest {
        val failover = failover()
        val acc = accountOf(3)
        var probes = 0
        val served = failover.run(
            acc,
            evidence = { _: String -> ServedFrom.CACHE },
            probe = { probes++ },
            attempt = { "cached" },
        )
        assertEquals("cached", served)
        assertEquals(0, probes)
        assertEquals("a disk-cache answer never touched the host: nothing recorded", ServerFailoverState(), store.read(1, acc.id))
    }

    // --- connect timeout & structured concurrency -------------------------------------------

    @Test
    fun `every attempt of a failover walk asks for the 8 s connect timeout`() = runTest {
        val failover = failover()
        val acc = accountOf(3)
        val world = World(failover, acc, mapOf(0 to Behavior.Hang(), 1 to Behavior.Ok(100))).also { it.scope = this }
        failover.run(acc, probe = world.probe, attempt = world.attempt)
        assertTrue("real, probe, follow-up real", world.connectTimeouts.size >= 3)
        assertTrue("${world.connectTimeouts}", world.connectTimeouts.all { it == FailoverRace.CONNECT_TIMEOUT_MS })
    }

    @Test
    fun `caller cancellation tears every attempt down`() = runTest {
        val failover = failover()
        val acc = accountOf(3)
        val world = World(failover, acc, mapOf(0 to Behavior.Hang(), 1 to Behavior.Hang(), 2 to Behavior.Hang())).also { it.scope = this }
        val outcome = runCatching {
            withTimeout(5_000) { failover.run(acc, probe = world.probe, attempt = world.attempt) }
        }
        assertTrue("${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is TimeoutCancellationException)
        assertEquals("every in-flight attempt was cancelled with the caller", setOf(0, 1, 2), world.cancelled)
        assertTrue(world.active.isEmpty())
        assertEquals("a cancelled walk records nothing", ServerFailoverState(), store.read(1, acc.id))
    }

    @Test
    fun `without a probe the walk is sequential and never races`() = runTest {
        val failover = failover()
        val acc = accountOf(3)
        val world = World(failover, acc, mapOf(0 to Behavior.Fail(10), 1 to Behavior.Ok(30))).also { it.scope = this }
        assertEquals("real:1", failover.run(acc, attempt = world.attempt))
        assertEquals(0, world.probeRequests)
    }
}
