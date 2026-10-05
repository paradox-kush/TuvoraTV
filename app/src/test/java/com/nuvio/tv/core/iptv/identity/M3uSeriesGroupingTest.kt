package com.nuvio.tv.core.iptv.identity

import com.nuvio.tv.core.iptv.identity.M3uSeriesGrouping.Promotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B64/D2 — the one series grouping every platform shares. Twin: NuvioMobile/NuvioDesktop `M3uSeriesGroupingTest`. */
class M3uSeriesGroupingTest {

    @Test
    fun `promotes episode-named VOD rows`() {
        assertEquals(Promotion("breaking bad", "Breaking Bad", 1, 2), M3uSeriesGrouping.promotion("Breaking Bad S01E02", null, "Drama"))
        assertEquals(Promotion("friends", "Friends", 3, 7), M3uSeriesGrouping.promotion("Friends 3x07", null, null))
        assertEquals(Promotion("show", "Show", 5, 14), M3uSeriesGrouping.promotion("Show - s05e14 [FHD]", null, null))
        assertEquals(Promotion("the office", "The Office (US)", 1, 1), M3uSeriesGrouping.promotion("The Office (US) S01E01 HD", null, null))
    }

    @Test
    fun `keeps genuine movies and bare markers`() {
        assertNull(M3uSeriesGrouping.promotion("Alien: Romulus (2024)", null, null))
        assertNull(M3uSeriesGrouping.promotion("2001: A Space Odyssey", null, null))
        assertNull(M3uSeriesGrouping.promotion("S01E02", null, null))
        assertNull(M3uSeriesGrouping.promotion("1920x1080 Test Card", null, null))
    }

    @Test
    fun `series id is the shared hash of the key`() {
        assertEquals(M3uIdentity.sidOf("series:breaking bad").toLong(), M3uSeriesGrouping.seriesSid("breaking bad").toLong())
        assertEquals("the grand tour", M3uSeriesGrouping.seriesKeyOf("The Grand Tour S01E02", null, "SERIES"))
        assertEquals("news", M3uSeriesGrouping.seriesKeyOf("S01E02", null, "News"))
    }
}
