package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-pad time travel: LEFT past the window's edge scrolls the guide BACK through the provider's
 * catch-up window, RIGHT returns toward now. The clamps matter more than the stepping — travelling
 * past the provider's retention shows a wall of empty cells, which reads as a broken guide.
 */
class GuideTimeTravelTest {

    private val hour = 60 * 60_000L
    private val day = 24 * hour

    /** A time that is deliberately NOT on a slot boundary — the window must still land on one. */
    private val now = 1_710_000_000_000L + 7 * 60_000L + 13_000L

    @Test
    fun `the live window shows one slot of past by default`() {
        val start = GuideTimeTravel.liveWindowStartMs(now)
        assertEquals("lands on a slot", 0L, start % GuideTimeTravel.SLOT_MS)
        // The resting guide draws recent history (the approved mockups): the window opens one
        // lookback behind now, so a just-finished programme is visible without travelling.
        assertTrue("exposes the lookback", start <= now - GuideTimeTravel.LIVE_LOOKBACK_MS)
        assertTrue(
            "nearest boundary below the lookback point",
            now - GuideTimeTravel.LIVE_LOOKBACK_MS - start < GuideTimeTravel.SLOT_MS,
        )
    }

    @Test
    fun `left steps back one slot at a time`() {
        val live = GuideTimeTravel.liveWindowStartMs(now)
        assertEquals(
            "one step back",
            live - GuideTimeTravel.SLOT_MS,
            GuideTimeTravel.shift(live, slots = -1, nowMs = now, catchUpDays = 7),
        )
        assertEquals(
            "four steps back",
            live - 4 * GuideTimeTravel.SLOT_MS,
            GuideTimeTravel.shift(live, slots = -4, nowMs = now, catchUpDays = 7),
        )
    }

    @Test
    fun `right steps forward again`() {
        val live = GuideTimeTravel.liveWindowStartMs(now)
        val back = GuideTimeTravel.shift(live, slots = -6, nowMs = now, catchUpDays = 7)
        assertEquals(
            "one step forward",
            back + GuideTimeTravel.SLOT_MS,
            GuideTimeTravel.shift(back, slots = 1, nowMs = now, catchUpDays = 7),
        )
    }

    /** Travelling back stops at the provider's retention — past it there is nothing to play. */
    @Test
    fun `travel back is clamped to the provider window`() {
        val live = GuideTimeTravel.liveWindowStartMs(now)
        val floor = GuideTimeTravel.earliestWindowStartMs(now, catchUpDays = 3)
        val far = GuideTimeTravel.shift(live, slots = -10_000, nowMs = now, catchUpDays = 3)
        assertEquals("clamped at the floor", floor, far)
        assertTrue("three days back", floor <= now - 3 * day)
        assertEquals("the floor is a slot boundary", 0L, floor % GuideTimeTravel.SLOT_MS)
    }

    /**
     * A silent panel (no tv_archive_duration) is the normal case, so its travel range comes from
     * the same floor the parse window uses — the guide can reach everything it stored.
     */
    @Test
    fun `a silent panel still travels the stored history`() {
        val floor = GuideTimeTravel.earliestWindowStartMs(now, catchUpDays = 0)
        assertTrue("at least eight days", floor <= now - 8 * day)
    }

    @Test
    fun `travel forward is clamped to the stored horizon`() {
        val live = GuideTimeTravel.liveWindowStartMs(now)
        val ceiling = GuideTimeTravel.latestWindowStartMs(now)
        assertEquals(
            "clamped at the ceiling",
            ceiling,
            GuideTimeTravel.shift(live, slots = 10_000, nowMs = now, catchUpDays = 7),
        )
        assertTrue("never before the live window", ceiling >= live)
        assertTrue(
            "the window's right edge stays inside what we store",
            ceiling + GuideTimeTravel.WINDOW_MS <= now + 36 * hour + GuideTimeTravel.SLOT_MS,
        )
    }

    /**
     * Two different questions that read alike. "At the live edge" decides whether the minute tick
     * may roll the window forward — a viewer reading yesterday must not be yanked to now. "Contains
     * now" decides whether the now-line is drawn, and stays true for a window that has only
     * travelled back a little, because now is still inside its two hours.
     */
    @Test
    fun `the live edge is recognised`() {
        val live = GuideTimeTravel.liveWindowStartMs(now)
        assertTrue("the live window", GuideTimeTravel.isAtLiveEdge(live, now))
        assertFalse(
            "an hour back is not anchored at live",
            GuideTimeTravel.isAtLiveEdge(live - 2 * GuideTimeTravel.SLOT_MS, now),
        )

        assertTrue("the live window shows the now-line", GuideTimeTravel.containsNow(live, now))
        assertTrue(
            "an hour back still contains now",
            GuideTimeTravel.containsNow(live - 2 * GuideTimeTravel.SLOT_MS, now),
        )
        assertFalse(
            "yesterday does not",
            GuideTimeTravel.containsNow(live - day, now),
        )
        assertEquals(
            "the now-line sits where the window puts it",
            0.5f,
            GuideTimeTravel.nowFraction(live - 2 * GuideTimeTravel.SLOT_MS, live),
            0.001f,
        )
    }

    /**
     * One press of LEFT at the window's edge must PAGE, not step.
     *
     * Field report from the Onn 4K (2026-08-16): "click back on the EPG — it only shows today, the
     * date does not change." The edge press was wired to a single 30-minute slot, so from an
     * evening anchor, yesterday sat ~40 presses away: the day label was CORRECT every press a
     * human would actually make, and the feature read as broken. Mobile already pages two hours
     * per press; a page here is one full window, which is the same two hours.
     */
    @Test
    fun `an edge press pages a full window, so yesterday is reachable`() {
        assertEquals(
            "one edge press = one full window",
            GuideTimeTravel.WINDOW_MS,
            GuideTimeTravel.EDGE_TRAVEL_SLOTS * GuideTimeTravel.SLOT_MS,
        )
        // From a 21:30-ish evening anchor, a dozen presses must cross midnight.
        var anchor = GuideTimeTravel.liveWindowStartMs(now)
        repeat(12) {
            anchor = GuideTimeTravel.shift(
                anchor, slots = -GuideTimeTravel.EDGE_TRAVEL_SLOTS, nowMs = now, catchUpDays = 7,
            )
        }
        assertTrue(
            "12 presses reach back a full day",
            GuideTimeTravel.liveWindowStartMs(now) - anchor >= day,
        )
    }

    /** A window that has travelled back must not be quietly snapped forward by the minute tick. */
    @Test
    fun `a travelled window survives a shift of zero`() {
        val live = GuideTimeTravel.liveWindowStartMs(now)
        val back = live - 8 * GuideTimeTravel.SLOT_MS
        assertEquals(
            "unchanged",
            back,
            GuideTimeTravel.shift(back, slots = 0, nowMs = now, catchUpDays = 7),
        )
    }
}
