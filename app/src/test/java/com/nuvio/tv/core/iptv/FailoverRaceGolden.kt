package com.nuvio.tv.core.iptv

/**
 * Step 0.3b GOLDEN TABLES — staggered parallel failover. Framework-free (no kotlin.test / JUnit types),
 * copied VERBATIM (package line aside) into NuvioTV's JUnit tests, next to [ServerFailoverGolden]. TV's
 * thin runners assert the same decisions; the only per-framework part (assertEquals argument order)
 * stays in each repo's runner, never here.
 *
 * What it pins:
 *  - [staggerCases] / [statsSteps]   — FailoverStagger / FailoverLatencyStats
 *  - [xtreamProbeCases] / [m3uProbeCases] / [stalkerProbeCases] — FailoverProbePolicy validity (the
 *    probe addendum: a 200 with HTML is INVALID and fails over, auth=0 is DEFINITIVE and never does)
 *  - [hostKeyCases]                  — which URLs count as "the same host" (never raced concurrently)
 *  - [scenarios] + [simulate]        — virtual-time runs of FailoverRaceScheduler (contract tests a–f, k–m);
 *    the coroutine executors replay the SAME scenarios in their own runners
 *  - [persistenceCases]              — the persisted ServerFailoverState JSON (backward compatibility, test j)
 */
object FailoverRaceGolden {

    // --- stagger ---------------------------------------------------------------------------

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val NOW = 100 * HOUR

    private fun fresh(ewma: Long, samples: Int = 3, ageMs: Long = HOUR, failAgoMs: Long? = null) = ServerLatencyStats(
        ewmaMs = ewma, samples = samples, lastSampleAtMs = NOW - ageMs, lastFailAtMs = failAgoMs?.let { NOW - it },
    )

    /** (name, stats, nowMs, expected stagger ms). */
    data class StaggerCase(val name: String, val stats: ServerLatencyStats?, val nowMs: Long, val expectedMs: Long)

    val staggerCases: List<StaggerCase> = listOf(
        StaggerCase("no samples -> 1500 default", null, NOW, 1_500),
        StaggerCase("empty record -> 1500 default", ServerLatencyStats(), NOW, 1_500),
        StaggerCase("ewma 400 -> 600", fresh(400), NOW, 600),
        StaggerCase("ewma 1000 -> 1500", fresh(1_000), NOW, 1_500),
        StaggerCase("ewma 3000 -> clamped to 2500", fresh(3_000), NOW, 2_500),
        StaggerCase("ewma 100 -> clamped up to 400", fresh(100), NOW, 400),
        StaggerCase("ewma with zero samples is ignored", fresh(400, samples = 0), NOW, 1_500),
        StaggerCase("failed 5 min ago -> 200 even with a fast ewma", fresh(400, failAgoMs = 5 * MIN), NOW, 200),
        StaggerCase("failed 5 min ago, no samples -> 200", ServerLatencyStats(lastFailAtMs = NOW - 5 * MIN), NOW, 200),
        StaggerCase("failed exactly 10 min ago is no longer recent", fresh(400, failAgoMs = 10 * MIN), NOW, 600),
        StaggerCase("failed 11 min ago -> back to ewma", fresh(400, failAgoMs = 11 * MIN), NOW, 600),
        StaggerCase("ewma older than 24 h decays to the default", fresh(400, ageMs = 25 * HOUR), NOW, 1_500),
        StaggerCase("ewma just inside 24 h still counts", fresh(400, ageMs = 24 * HOUR), NOW, 600),
    )

    // --- stats update ----------------------------------------------------------------------

    /** One update: op is "win" (arg = time-to-valid ms) or "fail" (arg unused), at [nowMs]. [expected] null = record dropped. */
    data class StatsStep(val op: String, val arg: Long, val nowMs: Long, val expected: ServerLatencyStats?)

    data class StatsCase(val name: String, val initial: ServerLatencyStats?, val steps: List<StatsStep>)

    private fun s(ewma: Long?, samples: Int, lastSample: Long, lastFail: Long? = null) =
        ServerLatencyStats(ewma, samples, lastSample, lastFail)

    val statsSteps: List<StatsCase> = listOf(
        StatsCase("first win seeds the ewma", null, listOf(
            StatsStep("win", 500, 1_000, s(500, 1, 1_000)),
        )),
        StatsCase("ewma alpha 0.3 over a material change", s(500, 1, 1_000), listOf(
            StatsStep("win", 1_000, 11_000, s(650, 2, 11_000)),     // 0.3*1000 + 0.7*500
        )),
        StatsCase("a sample within 20% of the ewma changes nothing (no rewrite)", s(500, 3, 1_000), listOf(
            StatsStep("win", 520, 20_000, s(500, 3, 1_000)),
        )),
        StatsCase("an immaterial sample still refreshes after an hour", s(500, 3, 1_000), listOf(
            StatsStep("win", 520, 1_000 + HOUR, s(506, 4, 1_000 + HOUR)),
        )),
        StatsCase("a time-to-valid of 60 s or more is not a sample", s(500, 2, 1_000), listOf(
            StatsStep("win", 60_000, 5_000, s(500, 2, 1_000)),
        )),
        StatsCase("a win clears the failure mark even without a sample", s(500, 2, 1_000, lastFail = 4_000), listOf(
            StatsStep("win", 70_000, 5_000, s(500, 2, 1_000)),
        )),
        StatsCase("samples older than 24 h are dropped, the new one starts over", s(500, 9, 1_000), listOf(
            StatsStep("win", 900, 1_000 + 25 * HOUR, s(900, 1, 1_000 + 25 * HOUR)),
        )),
        StatsCase("a failure records its time", null, listOf(
            StatsStep("fail", 0, 7_000, s(null, 0, 0, lastFail = 7_000)),
        )),
        StatsCase("a repeat failure within a minute changes nothing", s(null, 0, 0, lastFail = 7_000), listOf(
            StatsStep("fail", 0, 7_000 + 59_999, s(null, 0, 0, lastFail = 7_000)),
            StatsStep("fail", 0, 7_000 + 60_000, s(null, 0, 0, lastFail = 7_000 + 60_000)),
        )),
        StatsCase("a failure keeps the samples", s(500, 4, 1_000), listOf(
            StatsStep("fail", 0, 9_000, s(500, 4, 1_000, lastFail = 9_000)),
        )),
    )

    // --- probe validity --------------------------------------------------------------------

    /** (name, response body, verdict name = ProbeVerdict entry). */
    data class ProbeCase(val name: String, val body: String, val expected: String)

    private const val XTREAM_OK =
        """{"user_info":{"username":"u","auth":1,"status":"Active","exp_date":"1999999999","is_trial":"0","max_connections":"2"},"server_info":{"url":"x","port":"80"}}"""

    val xtreamProbeCases: List<ProbeCase> = listOf(
        ProbeCase("login ok", XTREAM_OK, "VALID"),
        ProbeCase("auth as a string", XTREAM_OK.replace("\"auth\":1", "\"auth\":\"1\""), "VALID"),
        ProbeCase("auth as a boolean", XTREAM_OK.replace("\"auth\":1", "\"auth\":true"), "VALID"),
        ProbeCase("trial account is valid", XTREAM_OK.replace("\"Active\"", "\"Trial\"").replace("\"is_trial\":\"0\"", "\"is_trial\":\"1\""), "VALID"),
        ProbeCase("missing status is valid", XTREAM_OK.replace("\"status\":\"Active\",", ""), "VALID"),
        ProbeCase("leading BOM and whitespace are tolerated", "﻿ \n$XTREAM_OK", "VALID"),
        ProbeCase("auth 0 is definitive", """{"user_info":{"auth":0}}""", "DEFINITIVE"),
        ProbeCase("auth 0 with server_info is definitive", """{"user_info":{"auth":0},"server_info":{"url":"x"}}""", "DEFINITIVE"),
        ProbeCase("auth \"0\" is definitive", """{"user_info":{"auth":"0"}}""", "DEFINITIVE"),
        ProbeCase("auth false is definitive", """{"user_info":{"auth":false}}""", "DEFINITIVE"),
        ProbeCase("Expired is definitive", XTREAM_OK.replace("\"Active\"", "\"Expired\""), "DEFINITIVE"),
        ProbeCase("Banned is definitive (case-insensitive)", XTREAM_OK.replace("\"Active\"", "\"BANNED\""), "DEFINITIVE"),
        ProbeCase("Disabled is definitive", XTREAM_OK.replace("\"Active\"", "\"Disabled\""), "DEFINITIVE"),
        ProbeCase("parked-domain HTML served as 200 is invalid", "<html><body>This domain is parked</body></html>", "INVALID"),
        ProbeCase("CDN error page is invalid", "<!DOCTYPE html><title>Error 1016</title>", "INVALID"),
        ProbeCase("empty body is invalid", "", "INVALID"),
        ProbeCase("whitespace body is invalid", "  \n ", "INVALID"),
        ProbeCase("a JSON array is invalid", "[]", "INVALID"),
        ProbeCase("JSON without user_info is invalid", """{"server_info":{"url":"x"}}""", "INVALID"),
        ProbeCase("user_info that is not an object is invalid", """{"user_info":[],"server_info":{}}""", "INVALID"),
        ProbeCase("auth 1 without server_info is invalid", """{"user_info":{"auth":1,"status":"Active"}}""", "INVALID"),
        ProbeCase("user_info without auth is invalid", """{"user_info":{"status":"Active"},"server_info":{}}""", "INVALID"),
        ProbeCase("truncated JSON is invalid", """{"user_info":{"auth":1""", "INVALID"),
    )

    /** M3U probe = the first bytes of the body (a Range: bytes=0-1023 request). */
    val m3uProbeCases: List<ProbeCase> = listOf(
        ProbeCase("plain header", "#EXTM3U\n#EXTINF:-1,A\nhttp://x/1.ts\n", "VALID"),
        ProbeCase("header with url-tvg attribute", "#EXTM3U url-tvg=\"http://epg\"\n#EXTINF", "VALID"),
        ProbeCase("BOM before the header", "﻿#EXTM3U\n", "VALID"),
        ProbeCase("blank lines before the header", "\r\n\r\n  #EXTM3U\r\n", "VALID"),
        ProbeCase("HTML is invalid", "<!DOCTYPE html><html>", "INVALID"),
        ProbeCase("a JSON error is invalid", """{"error":"forbidden"}""", "INVALID"),
        ProbeCase("empty is invalid", "", "INVALID"),
        ProbeCase("a bare entry without the header is invalid", "#EXTINF:-1,A\nhttp://x/1.ts", "INVALID"),
        ProbeCase("header must be upper-case", "#extm3u\n", "INVALID"),
    )

    /** Stalker handshake: the token (null = none). */
    val stalkerProbeCases: List<Pair<String?, String>> = listOf(
        "abc123" to "VALID",
        "  tok  " to "VALID",
        "" to "INVALID",
        "   " to "INVALID",
        null to "INVALID",
    )

    // --- host key ---------------------------------------------------------------------------

    val hostKeyCases: List<Pair<String, String>> = listOf(
        "http://A.Example.com:8080" to "a.example.com",
        "https://a.example.com/list.m3u?x=1#y" to "a.example.com",
        "http://user:pw@a.example.com:80/x" to "a.example.com",
        "http://a.example.com" to "a.example.com",
        "http://[2001:db8::1]:8080/x" to "[2001:db8::1]",
        "b.example.com:8080/path" to "b.example.com",
    )

    // --- race scenarios (virtual time) ------------------------------------------------------

    /** How one server behaves once an attempt against it starts. Times are relative to that attempt's start. */
    sealed interface Behavior {
        /** The attempt never answers; the transport's own read timeout fails it ([failsAfterMs], fail-over-able). */
        data class Hang(val failsAfterMs: Long = 60_000) : Behavior
        /** Fails fast / at its connect timeout with a fail-over-able failure. */
        data class Fail(val afterMs: Long) : Behavior
        /** A valid answer (2xx headers for a real request, a VALID probe). */
        data class Ok(val afterMs: Long) : Behavior
        /** A definitive failure that must surface: 401/403/456 on a real request, auth=0 on a probe. */
        data class Definitive(val afterMs: Long) : Behavior
        /** A 2xx that is not the panel (parked domain): fail-over-able. */
        data class Invalid(val afterMs: Long) : Behavior
        /**
         * The attempt waits [queuedMs] on a LOCAL queue first (a per-portal connection gate, "hold
         * browse while a stream plays"), then behaves as [then] with times counted from the end of the
         * wait. Queue time is not the server's latency: the stagger clock is stopped while it lasts.
         */
        data class Queued(val queuedMs: Long, val then: Behavior) : Behavior
    }

    data class Scenario(
        val name: String,
        /** Contract test letter(s) this pins: a–f from the 0.3b contract, k–n from the probe addendum. */
        val pins: String,
        val order: List<Int>,
        val behaviors: Map<Int, Behavior>,
        val staggers: Map<Int, Long> = emptyMap(),
        val hosts: Map<Int, String> = emptyMap(),
        val expected: Expected,
    )

    data class Expected(
        /** The server that decided the race; null = every server failed. */
        val winner: Int?,
        /** True when the winner's answer is a definitive FAILURE that must be surfaced (no failover). */
        val definitive: Boolean = false,
        /** Virtual ms at which the race was decided (winner answered / last failure). */
        val decidedAtMs: Long,
        /** Server -> virtual ms at which its attempt STARTED. Servers absent here must never have started. */
        val startedAtMs: Map<Int, Long>,
        /** Servers whose in-flight attempt was cancelled by the decision (never a failure). */
        val cancelled: Set<Int> = emptySet(),
        val maxInFlight: Int,
        /** On give-up: the server whose error is surfaced. */
        val surface: Int? = null,
    )

    const val DEFAULT_STAGGER = FailoverStagger.DEFAULT_MS

    val scenarios: List<Scenario> = listOf(
        Scenario("a healthy main answers inside its stagger: no other attempt is ever started", "a",
            listOf(0, 1, 2), mapOf(0 to Behavior.Ok(200), 1 to Behavior.Ok(50), 2 to Behavior.Ok(50)),
            expected = Expected(winner = 0, decidedAtMs = 200, startedAtMs = mapOf(0 to 0L), maxInFlight = 1)),
        Scenario("main refuses fast: backup 1 starts at once and wins", "b",
            listOf(0, 1, 2), mapOf(0 to Behavior.Fail(30), 1 to Behavior.Ok(150), 2 to Behavior.Ok(50)),
            expected = Expected(winner = 1, decidedAtMs = 180, startedAtMs = mapOf(0 to 0L, 1 to 30L), maxInFlight = 1)),
        Scenario("main hangs: backup 1 starts after the stagger, wins, main is cancelled", "c",
            listOf(0, 1, 2), mapOf(0 to Behavior.Hang(), 1 to Behavior.Ok(150), 2 to Behavior.Ok(50)),
            expected = Expected(winner = 1, decidedAtMs = 1_650, startedAtMs = mapOf(0 to 0L, 1 to 1_500L),
                cancelled = setOf(0), maxInFlight = 2)),
        Scenario("main hangs with a fast ewma: the stagger is shorter", "c,g",
            listOf(0, 1), mapOf(0 to Behavior.Hang(), 1 to Behavior.Ok(100)), staggers = mapOf(0 to 600L),
            expected = Expected(winner = 1, decidedAtMs = 700, startedAtMs = mapOf(0 to 0L, 1 to 600L),
                cancelled = setOf(0), maxInFlight = 2)),
        Scenario("five servers fail fast: one after another, never overlapping, main's error surfaced", "d",
            listOf(0, 1, 2, 3, 4), (0..4).associateWith { Behavior.Fail(10) },
            expected = Expected(winner = null, decidedAtMs = 50,
                startedAtMs = mapOf(0 to 0L, 1 to 10L, 2 to 20L, 3 to 30L, 4 to 40L), maxInFlight = 1, surface = 0)),
        Scenario("five servers dead at the 8 s connect timeout: two waves, never more than 3 in flight", "d",
            listOf(0, 1, 2, 3, 4), (0..4).associateWith { Behavior.Fail(8_000) },
            expected = Expected(winner = null, decidedAtMs = 17_500,
                startedAtMs = mapOf(0 to 0L, 1 to 1_500L, 2 to 3_000L, 3 to 8_000L, 4 to 9_500L), maxInFlight = 3, surface = 0)),
        Scenario("three hang then backup 3 is healthy: the hang cutoff frees a slot at 12 s", "e",
            listOf(0, 1, 2, 3), mapOf(0 to Behavior.Hang(), 1 to Behavior.Hang(), 2 to Behavior.Hang(), 3 to Behavior.Ok(100)),
            expected = Expected(winner = 3, decidedAtMs = 12_100,
                startedAtMs = mapOf(0 to 0L, 1 to 1_500L, 2 to 3_000L, 3 to 12_000L), cancelled = setOf(0, 1, 2), maxInFlight = 3)),
        Scenario("every server hangs: bounded by one read timeout plus the staggers, not n x 60 s", "e",
            listOf(0, 1, 2), (0..2).associateWith { Behavior.Hang() },
            expected = Expected(winner = null, decidedAtMs = 63_000,
                startedAtMs = mapOf(0 to 0L, 1 to 1_500L, 2 to 3_000L), maxInFlight = 3, surface = 0)),
        Scenario("the first responder is a 401: surfaced at once, nothing else ever started", "f",
            listOf(0, 1, 2), mapOf(0 to Behavior.Definitive(100), 1 to Behavior.Ok(10), 2 to Behavior.Ok(10)),
            expected = Expected(winner = 0, definitive = true, decidedAtMs = 100, startedAtMs = mapOf(0 to 0L), maxInFlight = 1)),
        Scenario("a hung main and a backup whose probe says auth=0: surfaced, main cancelled, no failover", "f,m",
            listOf(0, 1, 2), mapOf(0 to Behavior.Hang(), 1 to Behavior.Definitive(150), 2 to Behavior.Ok(10)),
            expected = Expected(winner = 1, definitive = true, decidedAtMs = 1_650,
                startedAtMs = mapOf(0 to 0L, 1 to 1_500L), cancelled = setOf(0), maxInFlight = 2)),
        Scenario("a parked domain answering 200 HTML does not win: the next server starts at once", "k",
            listOf(0, 1, 2), mapOf(0 to Behavior.Fail(20), 1 to Behavior.Invalid(100), 2 to Behavior.Ok(100)),
            expected = Expected(winner = 2, decidedAtMs = 220, startedAtMs = mapOf(0 to 0L, 1 to 20L, 2 to 120L), maxInFlight = 1)),
        Scenario("a hung main and a parked-domain backup: the invalid answer never wins, backup 2 does", "k",
            listOf(0, 1, 2), mapOf(0 to Behavior.Hang(), 1 to Behavior.Invalid(80), 2 to Behavior.Ok(100)),
            expected = Expected(winner = 2, decidedAtMs = 1_680, startedAtMs = mapOf(0 to 0L, 1 to 1_500L, 2 to 1_580L),
                cancelled = setOf(0), maxInFlight = 2)),
        Scenario("two servers on one host are never raced: the same-host backup is skipped while main is in flight", "l",
            listOf(0, 1, 2), mapOf(0 to Behavior.Hang(), 1 to Behavior.Ok(100), 2 to Behavior.Ok(100)),
            hosts = mapOf(0 to "a.test", 1 to "a.test", 2 to "b.test"),
            expected = Expected(winner = 2, decidedAtMs = 1_600, startedAtMs = mapOf(0 to 0L, 2 to 1_500L),
                cancelled = setOf(0), maxInFlight = 2)),
        Scenario("a same-host backup starts once main has failed", "l",
            listOf(0, 1), mapOf(0 to Behavior.Fail(40), 1 to Behavior.Ok(100)),
            hosts = mapOf(0 to "a.test", 1 to "a.test"),
            expected = Expected(winner = 1, decidedAtMs = 140, startedAtMs = mapOf(0 to 0L, 1 to 40L), maxInFlight = 1)),
        Scenario("a server that failed recently has a 200 ms stagger: its successor starts almost at once", "g",
            listOf(0, 1), mapOf(0 to Behavior.Hang(), 1 to Behavior.Ok(100)), staggers = mapOf(0 to 200L),
            expected = Expected(winner = 1, decidedAtMs = 300, startedAtMs = mapOf(0 to 0L, 1 to 200L),
                cancelled = setOf(0), maxInFlight = 2)),
        Scenario("a healthy main queued locally for 5 s is not slow: no successor is ever started", "q",
            listOf(0, 1), mapOf(0 to Behavior.Queued(5_000, Behavior.Ok(200)), 1 to Behavior.Ok(50)),
            expected = Expected(winner = 0, decidedAtMs = 5_200, startedAtMs = mapOf(0 to 0L), maxInFlight = 1)),
        Scenario("main queued locally for 3 s and then hangs: the stagger clock only starts after the queue", "q",
            listOf(0, 1), mapOf(0 to Behavior.Queued(3_000, Behavior.Hang()), 1 to Behavior.Ok(100)),
            expected = Expected(winner = 1, decidedAtMs = 4_600, startedAtMs = mapOf(0 to 0L, 1 to 4_500L),
                cancelled = setOf(0), maxInFlight = 2)),
        Scenario("a slow-but-alive main that answers before the backup's probe does keeps the race", "c",
            listOf(0, 1), mapOf(0 to Behavior.Ok(1_700), 1 to Behavior.Ok(500)),
            expected = Expected(winner = 0, decidedAtMs = 1_700, startedAtMs = mapOf(0 to 0L, 1 to 1_500L),
                cancelled = setOf(1), maxInFlight = 2)),
    )

    private object QueueEnd

    /** What [simulate] observed. Compared field-for-field with [Expected]. */
    data class Observed(
        val winner: Int?,
        val definitive: Boolean,
        val decidedAtMs: Long,
        val startedAtMs: Map<Int, Long>,
        val cancelled: Set<Int>,
        val maxInFlight: Int,
        val surface: Int?,
        /** True if an attempt ever started while another attempt on the same host was still running. */
        val hostOverlap: Boolean = false,
    )

    /**
     * Runs [FailoverRaceScheduler] over [scenario] in pure virtual time: an attempt's [Behavior] fires at
     * start + its delay; the scheduler is ticked at every behaviour event and at its own `nextWakeMs`.
     * Behaviour events at one instant are processed before the tick of that instant.
     */
    fun simulate(scenario: Scenario): Observed {
        val scheduler = FailoverRaceScheduler(
            order = scenario.order,
            hostOf = { scenario.hosts[it] ?: "host-$it" },
            staggerOf = { scenario.staggers[it] ?: DEFAULT_STAGGER },
        )
        val started = LinkedHashMap<Int, Long>()
        // (fire time, server, what fires): a Behavior, or QueueEnd = the end of a local wait.
        val pending = ArrayList<Triple<Long, Int, Any>>()
        val running = HashSet<Int>()
        var winner: Int? = null
        var definitive = false
        var cancelled: Set<Int> = emptySet()
        var surface: Int? = null
        var decidedAt = 0L
        var maxInFlight = 0
        var finished = false
        var hostOverlap = false
        fun host(server: Int) = scenario.hosts[server] ?: "host-$server"

        fun afterOf(b: Behavior): Long = when (b) {
            is Behavior.Hang -> b.failsAfterMs
            is Behavior.Fail -> b.afterMs
            is Behavior.Ok -> b.afterMs
            is Behavior.Definitive -> b.afterMs
            is Behavior.Invalid -> b.afterMs
            is Behavior.Queued -> b.queuedMs + afterOf(b.then)
        }

        fun apply(now: Long, decisions: List<RaceDecision>) {
            for (d in decisions) when (d) {
                is RaceDecision.StartAttempt -> {
                    started[d.server] = now
                    if (running.any { host(it) == host(d.server) }) hostOverlap = true
                    running += d.server
                    // "In flight" is what counts toward the cap: an attempt past the hang cutoff does not.
                    maxInFlight = maxOf(maxInFlight, running.count { now - started.getValue(it) < FailoverRace.HANG_CUTOFF_MS })
                    val b = scenario.behaviors.getValue(d.server)
                    if (b is Behavior.Queued) {
                        scheduler.onLocalWait(now, d.server, waiting = true)
                        pending += Triple(now + b.queuedMs, d.server, QueueEnd)
                        pending += Triple(now + afterOf(b), d.server, b.then)
                    } else {
                        pending += Triple(now + afterOf(b), d.server, b)
                    }
                }
                is RaceDecision.CancelAttempts -> {
                    cancelled = cancelled + d.servers
                    running.removeAll(d.servers)
                    pending.removeAll { it.second in d.servers }
                }
                is RaceDecision.Winner -> {
                    winner = d.server; decidedAt = now; finished = true
                    running.remove(d.server)
                    pending.removeAll { it.second == d.server }
                }
                is RaceDecision.GiveUp -> { surface = d.surfaceServer; decidedAt = now; finished = true }
            }
        }

        var now = 0L
        apply(now, scheduler.onTick(now))
        var guard = 0
        while (!finished && guard++ < 10_000) {
            val nextEvent = pending.minOfOrNull { it.first }
            val nextWake = scheduler.nextWakeMs(now)
            now = listOfNotNull(nextEvent, nextWake).minOrNull() ?: break
            val due = pending.filter { it.first == now }.sortedBy { it.second }
            for (ev in due) {
                if (finished) break
                pending.remove(ev)
                val what = ev.third
                if (what === QueueEnd) {
                    apply(now, scheduler.onLocalWait(now, ev.second, waiting = false))
                    continue
                }
                running.remove(ev.second)
                val decisions = when (what as Behavior) {
                    is Behavior.Ok -> scheduler.onHeaders(now, ev.second)
                    is Behavior.Definitive -> { definitive = true; scheduler.onFailed(now, ev.second, failsOver = false) }
                    is Behavior.Fail, is Behavior.Invalid, is Behavior.Hang -> scheduler.onFailed(now, ev.second, failsOver = true)
                    is Behavior.Queued -> error("a Queued behaviour cannot nest")
                }
                apply(now, decisions)
            }
            if (!finished) apply(now, scheduler.onTick(now))
        }
        return Observed(winner, definitive && winner != null, decidedAt, started, cancelled, maxInFlight, surface, hostOverlap)
    }

    // --- persistence -------------------------------------------------------------------------

    /** (name, persisted JSON of one ServerFailoverState, the state it must load as). Test j. */
    data class PersistenceCase(val name: String, val json: String, val expected: ServerFailoverState)

    val persistenceCases: List<PersistenceCase> = listOf(
        PersistenceCase("a Step 0.3 state without stats still loads",
            """{"activeIndex":1,"mainRetryAfterMs":123}""", ServerFailoverState(1, 123)),
        PersistenceCase("an empty object is the default state", "{}", ServerFailoverState()),
        PersistenceCase("a state with stats",
            """{"activeIndex":0,"stats":{"0":{"ewmaMs":400,"samples":2,"lastSampleAtMs":5,"lastFailAtMs":9}}}""",
            ServerFailoverState(0, null, mapOf(0 to ServerLatencyStats(400, 2, 5, 9)))),
        PersistenceCase("stats entries may omit any field",
            """{"stats":{"2":{"lastFailAtMs":7}}}""", ServerFailoverState(0, null, mapOf(2 to ServerLatencyStats(lastFailAtMs = 7)))),
        PersistenceCase("unknown keys from a newer build are ignored",
            """{"activeIndex":2,"mainRetryAfterMs":9,"somethingNew":true,"stats":{"1":{"ewmaMs":10,"extra":1}}}""",
            ServerFailoverState(2, 9, mapOf(1 to ServerLatencyStats(ewmaMs = 10)))),
    )
}
