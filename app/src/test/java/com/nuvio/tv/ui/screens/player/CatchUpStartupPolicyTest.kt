package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.CatchUpStartupPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A replay link that accepts the connection and never answers used to hold "Starting stream…" for
 * minutes (the source's own retries re-asked the same dead link every ~17 s). Every link now has a
 * startup deadline, the ladder advances on it, and the whole walk has a bound.
 */
class CatchUpStartupPolicyTest {

    private val s = 1_000L

    @Test
    fun `each link gets the per-link deadline`() {
        assertEquals("first link", CatchUpStartupPolicy.LINK_DEADLINE_MS, CatchUpStartupPolicy.deadlineMs(elapsedMs = 0L))
    }

    @Test
    fun `the last deadline is trimmed so the total never overruns`() {
        val nearEnd = CatchUpStartupPolicy.TOTAL_BUDGET_MS - 4 * s
        assertEquals("only the remaining budget is waited", 4 * s, CatchUpStartupPolicy.deadlineMs(nearEnd))
        assertTrue(
            "a spent budget still yields a positive wait so the timer fires",
            CatchUpStartupPolicy.deadlineMs(CatchUpStartupPolicy.TOTAL_BUDGET_MS + 1) > 0L,
        )
    }

    @Test
    fun `a timeout advances the ladder while budget and attempts remain`() {
        assertEquals(Decision.ADVANCE, CatchUpStartupPolicy.onTimeout(timeoutCount = 1, elapsedMs = 15 * s))
    }

    @Test
    fun `the walk ends after the maximum number of timeouts`() {
        assertEquals(
            Decision.GIVE_UP,
            CatchUpStartupPolicy.onTimeout(CatchUpStartupPolicy.MAX_TIMEOUTS, elapsedMs = 20 * s),
        )
    }

    @Test
    fun `the walk ends when the total budget is spent however few timeouts fired`() {
        assertEquals(
            Decision.GIVE_UP,
            CatchUpStartupPolicy.onTimeout(timeoutCount = 1, elapsedMs = CatchUpStartupPolicy.TOTAL_BUDGET_MS),
        )
    }

    @Test
    fun `adapter - an ever-changing url cannot reset the attempt count`() {
        // The count lives on the replay, not the link: each rung is a new URL string.
        val adapter = CatchUpReplayAdapter(isCatchUp = true, programmeStartMs = 1L, programmeEndMs = 2L)
        var now = 1_000_000L
        assertEquals(CatchUpStartupPolicy.LINK_DEADLINE_MS, adapter.startupDeadlineMs(now))
        val decisions = mutableListOf<Decision>()
        repeat(CatchUpStartupPolicy.MAX_TIMEOUTS) {
            now += CatchUpStartupPolicy.LINK_DEADLINE_MS
            decisions += adapter.onStartupTimeout(now)
            adapter.startupDeadlineMs(now) // re-armed for the next url
        }
        assertEquals(Decision.GIVE_UP, decisions.last())
        assertTrue("advances before the last", decisions.dropLast(1).all { it == Decision.ADVANCE })
    }

    @Test
    fun `adapter - total time is bounded across engine restarts that re-arm`() {
        val adapter = CatchUpReplayAdapter(isCatchUp = true, programmeStartMs = 1L, programmeEndMs = 2L)
        val t0 = 5_000_000L
        adapter.startupDeadlineMs(t0)
        // Re-arming later (a recovery rung reinitialises) must not restart the clock.
        val rearmed = adapter.startupDeadlineMs(t0 + CatchUpStartupPolicy.TOTAL_BUDGET_MS - 2 * s)
        assertEquals(2 * s, rearmed)
    }

    @Test
    fun `adapter - not armed for VOD or once the replay has played`() {
        val vod = CatchUpReplayAdapter(isCatchUp = false, programmeStartMs = null, programmeEndMs = null)
        assertNull(vod.startupDeadlineMs(1L))
        val played = CatchUpReplayAdapter(isCatchUp = true, programmeStartMs = 1L, programmeEndMs = 2L)
        played.onFirstFrame()
        assertNull(played.startupDeadlineMs(1L))
    }

    @Test
    fun `adapter - a user retry grants a fresh budget`() {
        val adapter = CatchUpReplayAdapter(isCatchUp = true, programmeStartMs = 1L, programmeEndMs = 2L)
        adapter.startupDeadlineMs(0L)
        repeat(CatchUpStartupPolicy.MAX_TIMEOUTS) { adapter.onStartupTimeout(CatchUpStartupPolicy.TOTAL_BUDGET_MS) }
        adapter.resetStartupBudget()
        assertEquals(CatchUpStartupPolicy.LINK_DEADLINE_MS, adapter.startupDeadlineMs(9_000_000L))
        assertEquals(Decision.ADVANCE, adapter.onStartupTimeout(9_000_000L + CatchUpStartupPolicy.LINK_DEADLINE_MS))
    }
}
