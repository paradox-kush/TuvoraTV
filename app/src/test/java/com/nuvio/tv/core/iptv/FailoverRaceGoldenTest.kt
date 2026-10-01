package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * JUnit runner for [FailoverRaceGolden] — the SAME table NuvioMobile/NuvioDesktop's commonTest runs
 * (FailoverRaceGoldenTest there). JUnit here, so every assertion is `assertEquals(message, expected, actual)`.
 */
class FailoverRaceGoldenTest {

    @Test
    fun `stagger table`() {
        for (c in FailoverRaceGolden.staggerCases) {
            assertEquals(c.name, c.expectedMs, FailoverStagger.compute(c.stats, c.nowMs))
        }
    }

    @Test
    fun `stats update table`() {
        for (case in FailoverRaceGolden.statsSteps) {
            var stats = case.initial
            for ((i, step) in case.steps.withIndex()) {
                val before = stats
                stats = when (step.op) {
                    "win" -> FailoverLatencyStats.onWin(stats, step.arg, step.nowMs)
                    "fail" -> FailoverLatencyStats.onFailure(stats, step.nowMs)
                    else -> error("unknown op ${step.op}")
                }
                assertEquals("${case.name} (step $i)", step.expected, stats)
                if (step.expected == before && before != null) {
                    assertSame("${case.name} (step $i): an unchanged record is the SAME instance (no prefs rewrite)", before, stats)
                }
            }
        }
    }

    @Test
    fun `xtream probe validity table`() {
        for (c in FailoverRaceGolden.xtreamProbeCases) {
            assertEquals(c.name, ProbeVerdict.valueOf(c.expected), FailoverProbePolicy.xtreamLogin(c.body))
        }
    }

    @Test
    fun `m3u probe validity table`() {
        for (c in FailoverRaceGolden.m3uProbeCases) {
            assertEquals(c.name, ProbeVerdict.valueOf(c.expected), FailoverProbePolicy.m3uPrefix(c.body))
        }
    }

    @Test
    fun `stalker probe validity table`() {
        for ((token, expected) in FailoverRaceGolden.stalkerProbeCases) {
            assertEquals("token=$token", ProbeVerdict.valueOf(expected), FailoverProbePolicy.stalkerHandshake(token))
        }
    }

    @Test
    fun `probe verdicts map to the failures the classifier understands`() {
        assertNull(FailoverProbePolicy.toFailure(ProbeVerdict.VALID, "x"))
        val invalid = FailoverProbePolicy.toFailure(ProbeVerdict.INVALID, "x")!!
        val definitive = FailoverProbePolicy.toFailure(ProbeVerdict.DEFINITIVE, "x")!!
        assertTrue("invalid response fails over", FailoverFailureClassifier.shouldFailOver(classifyFailoverThrowable(invalid)))
        assertFalse("auth rejected never fails over", FailoverFailureClassifier.shouldFailOver(classifyFailoverThrowable(definitive)))
    }

    @Test
    fun `host key table`() {
        for ((url, host) in FailoverRaceGolden.hostKeyCases) assertEquals(url, host, FailoverHostKey.of(url))
    }

    @Test
    fun `race scenarios in virtual time`() {
        for (s in FailoverRaceGolden.scenarios) {
            val got = FailoverRaceGolden.simulate(s)
            val e = s.expected
            assertEquals("${s.name}: winner", e.winner, got.winner)
            assertEquals("${s.name}: definitive", e.definitive, got.definitive)
            assertEquals("${s.name}: decided at", e.decidedAtMs, got.decidedAtMs)
            assertEquals("${s.name}: start times (absent = never started)", e.startedAtMs, got.startedAtMs)
            assertEquals("${s.name}: cancelled", e.cancelled, got.cancelled)
            assertEquals("${s.name}: max in flight", e.maxInFlight, got.maxInFlight)
            assertEquals("${s.name}: surfaced server", e.surface, got.surface)
        }
    }

    @Test
    fun `persisted state json table - old states load and unknown keys are ignored`() {
        for (c in FailoverRaceGolden.persistenceCases) {
            assertEquals(c.name, c.expected, PrefsServerFailoverStateStore.decodeState(c.json))
        }
    }

    @Test
    fun `a state without stats still encodes exactly as Step 0_3 wrote it`() {
        assertEquals(
            """{"activeIndex":1,"mainRetryAfterMs":123}""",
            PrefsServerFailoverStateStore.encodeState(ServerFailoverState(1, 123)),
        )
    }

    @Test
    fun `a Step 0_3 prefs row written by Gson still loads`() {
        // Step 0.3 on TV persisted the per-profile map with Gson; the same rows must decode now.
        val gsonWritten = com.google.gson.Gson().toJson(mapOf("p|u" to mapOf("activeIndex" to 2, "mainRetryAfterMs" to 99L)))
        assertEquals(mapOf("p|u" to ServerFailoverState(2, 99)), PrefsServerFailoverStateStore.decode(gsonWritten))
    }

    /** Invariants over 3000 random worlds: the rules hold whatever the servers do. */
    @Test
    fun `scheduler invariants hold for random servers`() {
        val rnd = Random(20261001)
        repeat(3_000) { n ->
            val count = rnd.nextInt(2, 7)
            val hosts = (0 until count).associateWith { "h${rnd.nextInt(0, 4)}" }
            val behaviors = (0 until count).associateWith {
                val after = rnd.nextLong(1, 3_000)
                when (rnd.nextInt(5)) {
                    0 -> FailoverRaceGolden.Behavior.Hang(rnd.nextLong(20_000, 61_000))
                    1 -> FailoverRaceGolden.Behavior.Fail(after)
                    2 -> FailoverRaceGolden.Behavior.Ok(after)
                    3 -> FailoverRaceGolden.Behavior.Invalid(after)
                    else -> FailoverRaceGolden.Behavior.Definitive(after)
                }
            }
            val staggers = (0 until count).associateWith { rnd.nextLong(200, 2_501) }
            val s = FailoverRaceGolden.Scenario("random $n", "", (0 until count).toList(), behaviors, staggers, hosts,
                FailoverRaceGolden.Expected(null, decidedAtMs = 0, startedAtMs = emptyMap(), maxInFlight = 0))
            val got = FailoverRaceGolden.simulate(s)
            val msg = "world $n $behaviors hosts=$hosts staggers=$staggers -> $got"
            assertTrue("never more than ${FailoverRace.MAX_IN_FLIGHT} counted in flight: $msg", got.maxInFlight <= FailoverRace.MAX_IN_FLIGHT)
            assertFalse("never two concurrent attempts against one host: $msg", got.hostOverlap)
            if (got.winner != null) {
                assertTrue("the winner was started: $msg", got.winner in got.startedAtMs.keys)
                assertFalse("the winner is not a cancelled loser: $msg", got.winner in got.cancelled)
                assertTrue("nothing starts after the decision: $msg", got.startedAtMs.values.all { it <= got.decidedAtMs })
            } else {
                assertEquals("main's error is surfaced when it ran: $msg", 0, got.surface ?: if (got.startedAtMs.keys.contains(0)) 0 else -1)
            }
        }
    }
}
