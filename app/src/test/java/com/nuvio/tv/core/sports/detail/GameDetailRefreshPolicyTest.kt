package com.nuvio.tv.core.sports.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDetailRefreshPolicyTest {
    private val now = 1_000_000_000L
    private val cfg = GameDetailRefreshPolicy.Config()
    private fun d(
        state: GameState, visible: Boolean = true, lastBoard: Long? = null, lastSummary: Long? = null,
        startsAt: Long = now + 86_400_000, c: GameDetailRefreshPolicy.Config = cfg,
    ) = GameDetailRefreshPolicy.decide(state, visible, now, lastBoard, lastSummary, startsAt, c)

    @Test fun `first open of a live game fetches both parts`() =
        assertEquals("both", setOf(RefreshPart.SCOREBOARD, RefreshPart.SUMMARY), d(GameState.LIVE).fetch)
    @Test fun `live scoreboard is due every 15 seconds not before`() {
        assertEquals("not due at 14.9s", emptySet<RefreshPart>(), d(GameState.LIVE, lastBoard = now - 14_900, lastSummary = now - 1_000).fetch)
        assertEquals("due at 15s", setOf(RefreshPart.SCOREBOARD), d(GameState.LIVE, lastBoard = now - 15_000, lastSummary = now - 1_000).fetch)
    }
    @Test fun `summary rides a slower 60 second clock`() =
        assertEquals("summary only", setOf(RefreshPart.SUMMARY), d(GameState.LIVE, lastBoard = now - 1_000, lastSummary = now - 60_000).fetch)
    @Test fun `next delay is the soonest due part`() =
        assertEquals("delay", 5_000L, d(GameState.LIVE, lastBoard = now - 10_000, lastSummary = now - 10_000).nextDelayMs)
    @Test fun `finished game fetches once then makes zero requests`() {
        assertEquals("first", setOf(RefreshPart.SCOREBOARD, RefreshPart.SUMMARY), d(GameState.FINAL).fetch)
        val idle = d(GameState.FINAL, lastBoard = now - 10 * 60_000, lastSummary = now - 10 * 60_000)
        assertEquals("idle fetch", emptySet<RefreshPart>(), idle.fetch)
        assertNull("idle never wakes", idle.nextDelayMs)
    }
    @Test fun `upcoming game far from kickoff fetches once then idles`() {
        assertEquals("first", setOf(RefreshPart.SCOREBOARD, RefreshPart.SUMMARY), d(GameState.UPCOMING).fetch)
        val idle = d(GameState.UPCOMING, lastBoard = now - 1, lastSummary = now - 1)
        assertEquals("idle", emptySet<RefreshPart>(), idle.fetch)
        assertNull("no wake", idle.nextDelayMs)
    }
    @Test fun `upcoming game at kickoff polls the scoreboard each minute to catch the flip to live`() {
        val due = d(GameState.UPCOMING, lastBoard = now - 61_000, lastSummary = now - 1, startsAt = now - 30_000)
        assertEquals("scoreboard", setOf(RefreshPart.SCOREBOARD), due.fetch)
        assertEquals("not yet due", emptySet<RefreshPart>(), d(GameState.UPCOMING, lastBoard = now - 20_000, lastSummary = now - 1, startsAt = now - 30_000).fetch)
    }
    @Test fun `hidden screen never requests anything`() {
        val hidden = d(GameState.LIVE, visible = false)
        assertEquals("fetch", emptySet<RefreshPart>(), hidden.fetch)
        assertNull("delay", hidden.nextDelayMs)
    }
    @Test fun `postponed behaves like idle`() = assertNull("no wake", d(GameState.POSTPONED, lastBoard = now, lastSummary = now).nextDelayMs)
    @Test fun `remote config cannot push the live poll under the floor`() {
        val aggressive = GameDetailRefreshPolicy.Config.from(RefreshConfig(liveMs = 500, summaryMs = 1_000, idleMs = 0, minMs = 6_000))
        assertEquals("live floor", 6_000L, aggressive.liveMs)
        assertEquals("summary floor", 6_000L, aggressive.summaryMs)
        val bundled = GameDetailRefreshPolicy.Config.from(SportsFixtures.mapping.refresh)
        assertEquals("bundled live", 15_000L, bundled.liveMs)
        assertEquals("bundled summary", 60_000L, bundled.summaryMs)
    }

    // ---- Mobile-aligned plan() ----
    private fun plan(state: GameState, startsIn: Long = 86_400_000, board: Long? = null, summary: Long? = null, fails: Int = 0) =
        GameDetailRefreshPolicy.plan(state, now + startsIn, now, board, summary, fails)

    @Test fun `plan live fetches both then paces the event at 15s and summary at 60s`() {
        assertEquals("first", RefreshPlan(true, true, 0), plan(GameState.LIVE))
        val p = plan(GameState.LIVE, board = now - 15_000, summary = now - 5_000)
        assertEquals("event due, summary not", true to false, p.fetchEvent to p.fetchSummary)
    }
    @Test fun `plan upcoming within 30 minutes ticks the event only, far-off is idle`() {
        assertEquals("near", true to false, plan(GameState.UPCOMING, startsIn = 10 * 60_000).let { it.fetchEvent to it.fetchSummary })
        assertTrue("far", plan(GameState.UPCOMING, startsIn = 5 * 3_600_000).isIdle)
    }
    @Test fun `plan final and postponed are idle with zero requests`() {
        assertEquals("final", RefreshPlan(false, false, null), plan(GameState.FINAL))
        assertTrue("postponed", plan(GameState.POSTPONED).isIdle)
    }
    @Test fun `plan failures back off 2x 4x 8x`() {
        assertEquals("2x", 30_000L, plan(GameState.LIVE, board = now, summary = now, fails = 1).nextCheckInMs)
        assertEquals("8x cap", 120_000L, plan(GameState.LIVE, board = now, summary = now, fails = 9).nextCheckInMs)
    }
}
