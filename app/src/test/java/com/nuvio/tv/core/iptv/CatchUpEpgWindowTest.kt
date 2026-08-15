package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounds on the historical-EPG fetch. Every number here exists to keep a full-window guide off
 * the heap of a 1 GB box — the one part of catch-up that can OOM a user.
 */
class CatchUpEpgWindowTest {

    private val now = 1_710_000_000_000L
    private val hour = 60 * 60_000L
    private val day = 24 * hour

    @Test
    fun `the parse window reaches back the provider window when it is longer than the floor`() {
        assertEquals(
            "14-day panel keeps 14 days",
            now - 14 * day,
            CatchUpEpgWindow.parseFromMs(now, catchUpDays = 14),
        )
    }

    /**
     * Panels understate (and usually omit) tv_archive_duration, and per-programme has_archive can
     * mark rows older than the stated window — so a short or absent window still keeps the floor.
     */
    @Test
    fun `the parse window never reaches back less than the floor`() {
        assertEquals(
            "7-day panel still keeps 8",
            now - 8 * day,
            CatchUpEpgWindow.parseFromMs(now, catchUpDays = 7),
        )
        assertEquals(
            "silent panel keeps 8",
            now - 8 * day,
            CatchUpEpgWindow.parseFromMs(now, catchUpDays = 0),
        )
    }

    @Test
    fun `the parse window reaches forward a day and a half`() {
        assertEquals("forward horizon", now + 36 * hour, CatchUpEpgWindow.parseToMs(now))
    }

    /** Rows outside the window are dropped AT PARSE — that is what keeps the body streaming. */
    @Test
    fun `rows outside the window are not kept`() {
        assertFalse(
            "older than the window",
            CatchUpEpgWindow.keeps(now - 20 * day, now - 20 * day + hour, now, catchUpDays = 7),
        )
        assertFalse(
            "beyond the forward horizon",
            CatchUpEpgWindow.keeps(now + 40 * hour, now + 41 * hour, now, catchUpDays = 7),
        )
        assertTrue(
            "yesterday is kept",
            CatchUpEpgWindow.keeps(now - day, now - day + hour, now, catchUpDays = 7),
        )
        assertTrue(
            "tonight is kept",
            CatchUpEpgWindow.keeps(now + hour, now + 2 * hour, now, catchUpDays = 7),
        )
    }

    /** A row straddling the boundary is still worth keeping — half of it is inside. */
    @Test
    fun `a row straddling the window edge is kept`() {
        val from = CatchUpEpgWindow.parseFromMs(now, catchUpDays = 7)
        assertTrue(
            "straddles the back edge",
            CatchUpEpgWindow.keeps(from - hour, from + hour, now, catchUpDays = 7),
        )
    }

    /**
     * Degenerate rows are refused here as well as in actionFor: storing them costs rows the guide
     * can never offer, and their replay URL is guaranteed dead.
     */
    @Test
    fun `degenerate rows are never kept`() {
        assertFalse("epoch start", CatchUpEpgWindow.keeps(0L, now, now, catchUpDays = 7))
        assertFalse("negative start", CatchUpEpgWindow.keeps(-hour, now, now, catchUpDays = 7))
        assertFalse("zero length", CatchUpEpgWindow.keeps(now - hour, now - hour, now, catchUpDays = 7))
        assertFalse("end before start", CatchUpEpgWindow.keeps(now - hour, now - 2 * hour, now, catchUpDays = 7))
    }

    /** Prune drops exactly what parse would have refused, so the two can never disagree. */
    @Test
    fun `the prune cutoff matches the parse window`() {
        assertEquals(
            "cutoff tracks the window",
            CatchUpEpgWindow.parseFromMs(now, catchUpDays = 14),
            CatchUpEpgWindow.pruneCutoffMs(now, catchUpDays = 14),
        )
    }

    @Test
    fun `a channel never fetched is fetched`() {
        assertTrue("never fetched", CatchUpEpgWindow.shouldFetch(null, now))
    }

    @Test
    fun `a channel fetched inside the ttl is not fetched again`() {
        assertFalse("just fetched", CatchUpEpgWindow.shouldFetch(now - 60_000L, now))
        assertFalse(
            "one minute short of the ttl",
            CatchUpEpgWindow.shouldFetch(now - CatchUpEpgWindow.FETCH_TTL_MS + 60_000L, now),
        )
    }

    @Test
    fun `a channel fetched beyond the ttl is fetched again`() {
        assertTrue(
            "ttl expired",
            CatchUpEpgWindow.shouldFetch(now - CatchUpEpgWindow.FETCH_TTL_MS, now),
        )
    }

    /**
     * A stamp in the future (the device clock moved back, or a panel clock wrote it) must not pin
     * a channel fresh forever — that is a guide the viewer can never refresh.
     */
    @Test
    fun `a future fetch stamp is treated as stale`() {
        assertTrue("future stamp", CatchUpEpgWindow.shouldFetch(now + 10 * day, now))
    }
}
