package com.nuvio.tv.core.mediaserver.policy

import com.nuvio.tv.core.mediaserver.policy.ProgressReportPolicy.Kind
import com.nuvio.tv.core.mediaserver.policy.ProgressReportPolicy.Report
import com.nuvio.tv.core.mediaserver.policy.ProgressReportPolicy.State
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class ProgressReportPolicyTest {
    /** Drives the policy with a script of (nowMs, positionMs, paused) ticks; returns the reports it emitted. */
    private fun run(vararg ticks: Triple<Long, Long, Boolean>): List<Report> {
        var state = State()
        val out = mutableListOf<Report>()
        val first = ticks.first()
        ProgressReportPolicy.start(state, first.first, first.second).also { state = it.state; it.report?.let(out::add) }
        for (t in ticks.drop(1)) ProgressReportPolicy.progress(state, t.first, t.second, t.third).also { state = it.state; it.report?.let(out::add) }
        return out
    }

    @Test
    fun startSendsOneStartReport() {
        val s = ProgressReportPolicy.start(State(), 1_000, 5_000)
        assertEquals(Report(Kind.START, 5_000, false), s.report)
        assertNull("a second start of the same session is not a second START", ProgressReportPolicy.start(s.state, 1_100, 5_100).report)
    }

    @Test
    fun playingReportsEveryFifteenSecondsAndNotBefore() {
        // owner decision 2026-10-06: ~15 s while playing (the player ticks every 5 s; the policy decides what is sent)
        val reports = run(
            Triple(0L, 0L, false),
            Triple(5_000L, 5_000L, false), Triple(10_000L, 10_000L, false), Triple(14_999L, 14_999L, false),
            Triple(15_000L, 15_000L, false), Triple(20_000L, 20_000L, false), Triple(25_000L, 25_000L, false),
            Triple(30_000L, 30_000L, false),
        )
        assertEquals(listOf(Kind.START, Kind.PROGRESS, Kind.PROGRESS), reports.map { it.kind })
        assertEquals(listOf(0L, 15_000L, 30_000L), reports.map { it.positionMs })
    }

    @Test
    fun aPauseReportsImmediatelyOnceThenOnlyCheckInsEverySixtySeconds() {
        val reports = run(
            Triple(0L, 0L, false),
            Triple(3_000L, 3_000L, true),             // pause edge: sent now
            Triple(20_000L, 3_000L, true),            // still paused: quiet
            Triple(62_999L, 3_000L, true),
            Triple(63_000L, 3_000L, true),            // >= 60 s since the pause report: check-in
            Triple(100_000L, 3_000L, true),           // quiet again
        )
        assertEquals(listOf(Kind.START, Kind.PROGRESS, Kind.PROGRESS), reports.map { it.kind })
        assertTrue(reports[1].paused && reports[2].paused)
        assertEquals(3_000L, reports[1].positionMs)
    }

    @Test
    fun resumingAfterAPauseReportsAtOnce() {
        val reports = run(Triple(0L, 0L, false), Triple(1_000L, 1_000L, true), Triple(5_000L, 1_000L, false))
        assertEquals(listOf(false, true, false), reports.map { it.paused })
    }

    @Test
    fun aSeekThatLandsIsReportedWithoutWaitingForTheInterval() {
        val reports = run(Triple(0L, 0L, false), Triple(2_000L, 2_000L, false), Triple(3_000L, 600_000L, false))
        assertEquals(listOf(0L, 600_000L), reports.map { it.positionMs })
        // ordinary drift (a laggy position update) is not a seek
        val calm = run(Triple(0L, 0L, false), Triple(3_000L, 6_500L, false))
        assertEquals(1, calm.size)
    }

    @Test
    fun stopAlwaysReportsExactlyOnce() {
        val started = ProgressReportPolicy.start(State(), 0, 0).state
        val s = ProgressReportPolicy.stop(started, 4_000, 4_000)
        assertEquals(Report(Kind.STOP, 4_000, false), s.report)
        assertNull("teardown after stop must not stop twice", ProgressReportPolicy.stop(s.state, 5_000, 5_000).report)
        assertNull("nothing after the stop", ProgressReportPolicy.progress(s.state, 90_000, 90_000, false).report)
    }

    @Test
    fun aStopWithoutAStartStillReports() {
        // the player may only learn about the session at teardown (external player result)
        assertEquals(Kind.STOP, ProgressReportPolicy.stop(State(), 0, 123_000).report?.kind)
    }

    @Test
    fun aProgressEventBeforeAnyStartIsTheStart() {
        assertEquals(Kind.START, ProgressReportPolicy.progress(State(), 0, 0, false).report?.kind)
    }

    @Test
    fun noDoubleScrobbleTheServerMarksPlayedOnItsOwnThreshold() {
        // Tuvora finished it at 95%: the stop report crossed the server's 90% -> no explicit mark
        assertFalse(ProgressReportPolicy.shouldMarkPlayedExplicitly(true, 5_700_000, 6_000_000))
        // Tuvora's completion threshold is lower than the server's: stopped at 85% -> mark explicitly
        assertTrue(ProgressReportPolicy.shouldMarkPlayedExplicitly(true, 5_100_000, 6_000_000))
        // not finished in Tuvora -> never mark
        assertFalse(ProgressReportPolicy.shouldMarkPlayedExplicitly(false, 100_000, 6_000_000))
        // unknown duration: cannot prove the server crossed its threshold; the mark is idempotent
        assertTrue(ProgressReportPolicy.shouldMarkPlayedExplicitly(true, 100_000, 0))
        // exactly at the threshold is crossed
        assertFalse(ProgressReportPolicy.shouldMarkPlayedExplicitly(true, 5_400_000, 6_000_000))
    }
}
