package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class StartDeadlineClockTest {
    private fun StartDeadlineClock.run(fromMs: Long, seconds: Int, suspended: Boolean = false): Long {
        var now = fromMs
        repeat(seconds) { now += 1_000L; tick(now, suspended) }
        return now
    }

    @Test
    fun counts_active_time_only() {
        val c = StartDeadlineClock()
        c.onAttempt("a", 0L)
        var now = c.run(0L, 10)
        now = c.run(now, 50, suspended = true)
        c.run(now, 5)
        assertEquals("paused/backgrounded time is not counted", 15_000L, c.activeMs)
    }

    @Test
    fun a_new_source_restarts_the_clock_but_the_same_source_keeps_it() {
        val c = StartDeadlineClock()
        c.onAttempt("a", 0L)
        val now = c.run(0L, 60)
        c.onAttempt("a", now) // auto-retry / engine failover: same key
        assertEquals(60_000L, c.activeMs)
        c.onAttempt("b", now) // source pick at 60 s gets a full window
        assertEquals(0L, c.activeMs)
    }

    @Test
    fun a_shown_failure_or_viewer_restart_forces_a_fresh_clock_for_the_same_source() {
        val c = StartDeadlineClock()
        c.onAttempt("a", 0L)
        val now = c.run(0L, 80)
        c.onAttempt("a", now, force = true)
        assertEquals(0L, c.activeMs)
        c.run(now, 3)
        c.reset(now + 3_000L) // Start from beginning
        assertEquals(0L, c.activeMs)
    }

    @Test
    fun a_gap_with_no_ticks_is_not_counted_as_waiting() {
        val c = StartDeadlineClock()
        c.onAttempt("a", 0L)
        c.tick(1_000L, false)
        c.tick(121_000L, false) // e.g. a 2 minute re-resolve with the loop parked
        assertEquals(1_000L + StartDeadlineClock.MAX_COUNTED_GAP_MS, c.activeMs)
    }

    @Test
    fun cleared_clock_starts_over_on_the_next_attempt() {
        val c = StartDeadlineClock()
        c.onAttempt("a", 0L)
        c.run(0L, 20)
        c.clear()
        c.onAttempt("a", 50_000L)
        assertEquals(0L, c.activeMs)
    }

    @Test
    fun slow_ticks_of_three_seconds_still_reach_the_deadline() {
        val c = StartDeadlineClock()
        c.onAttempt("a", 0L)
        var now = 0L
        var ticks = 0
        while (c.activeMs < PlayerLoadDeadlinePolicy.HARD_DEADLINE_MS && ticks < 100) {
            now += 3_000L; c.tick(now, false); ticks++
        }
        assertEquals("3 s ticks count in full", 25, ticks)
    }
}
