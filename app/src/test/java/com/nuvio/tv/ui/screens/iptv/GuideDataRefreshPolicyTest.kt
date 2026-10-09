package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F4 (2026-10-08): a guide opened while a playlist's XMLTV ingest was still downloading asked its
 * rows, got empty answers, and kept "No information" after the ingest landed until the viewer moved
 * focus again. The decision "which rows to ask again when new guide data lands" is pinned here.
 */
class GuideDataRefreshPolicyTest {

    private val ids = (1..40).toList()

    @Test
    fun `new data for the playlist on screen applies`() {
        assertTrue("same playlist", GuideDataRefreshPolicy.appliesTo("acc-1", "acc-1"))
    }

    @Test
    fun `new data for another playlist is ignored`() {
        assertFalse("other playlist", GuideDataRefreshPolicy.appliesTo("acc-2", "acc-1"))
    }

    @Test
    fun `a whole-lineup commit (mirror) applies to any playlist`() {
        assertTrue("mirror commit", GuideDataRefreshPolicy.appliesTo(null, "acc-1"))
    }

    @Test
    fun `no playlist on screen means nothing to refresh`() {
        assertFalse("no account", GuideDataRefreshPolicy.appliesTo("acc-1", null))
    }

    @Test
    fun `empty visible rows are re-asked, focused row first`() {
        val reask = GuideDataRefreshPolicy.streamIdsToReask(focusedIndex = 10, streamIds = ids) { false }
        assertEquals("focused first, then the prefetch window nearest-first", 11, reask.first())
        assertEquals(
            "focused row plus a full prefetch window, no duplicates",
            GuideEpgPrefetchPolicy.indexesAround(10, ids.size).map { ids[it] },
            reask,
        )
    }

    @Test
    fun `rows that already show a programme are not re-asked`() {
        val have = setOf(13, 14)
        val reask = GuideDataRefreshPolicy.streamIdsToReask(focusedIndex = 10, streamIds = ids) { it in have }
        assertFalse("stream 13 already has a programme", 13 in reask)
        assertFalse("stream 14 already has a programme", 14 in reask)
        assertTrue("an empty neighbour is re-asked", 12 in reask)
    }

    @Test
    fun `the focused row is re-asked even if it shows a programme`() {
        val reask = GuideDataRefreshPolicy.streamIdsToReask(focusedIndex = 3, streamIds = ids) { true }
        assertEquals("only the focused row", listOf(4), reask)
    }

    @Test
    fun `rows outside the window are not touched`() {
        val reask = GuideDataRefreshPolicy.streamIdsToReask(focusedIndex = 0, streamIds = ids) { false }
        assertFalse("far-away row", 40 in reask)
        assertEquals("window size", GuideEpgPrefetchPolicy.RADIUS + 1, reask.size)
    }

    @Test
    fun `an empty or unfocused list asks nothing`() {
        assertEquals("empty list", emptyList<Int>(), GuideDataRefreshPolicy.streamIdsToReask(0, emptyList()) { false })
        assertEquals("no focus", emptyList<Int>(), GuideDataRefreshPolicy.streamIdsToReask(-1, ids) { false })
    }

    @Test
    fun `non-positive stream ids (synthetic rows) are skipped`() {
        val reask = GuideDataRefreshPolicy.streamIdsToReask(focusedIndex = 1, streamIds = listOf(0, -1, 7)) { false }
        assertEquals("only the real row", listOf(7), reask)
    }
}
