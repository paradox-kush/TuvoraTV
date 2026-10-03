package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide's two loaders write one row (B11 / UX54). A now/next result that lands after the stored
 * window must not wipe the past off the row, and a travelled window follows every row on screen.
 */
class GuideWindowRowsTest {

    private val hour = 60 * 60_000L
    private val now = 1_710_000_000_000L

    private fun prog(title: String, startMs: Long, endMs: Long) = XtreamProgram(
        title = title,
        description = "",
        startMs = startMs,
        endMs = endMs,
        nowPlaying = now in startMs until endMs,
    )

    private val past = prog("Earlier", now - 2 * hour, now - hour)
    private val airing = prog("Airing", now - hour, now + hour)
    private val later = prog("Later", now + hour, now + 2 * hour)

    @Test
    fun `a late now-next never replaces the stored window already on the row`() {
        val stored = GuideWindowRows.mergeStoredWindow(existing = null, programmes = listOf(past, airing, later))
        val merged = GuideWindowRows.mergeNowNext(stored, now = airing, next = later, programmes = listOf(airing, later))
        assertEquals("the past survives", listOf(past, airing, later), merged.programmes)
        assertEquals("now refreshed", airing, merged.now)
        assertEquals("next refreshed", later, merged.next)
    }

    @Test
    fun `now-next is the row's first paint when nothing is stored`() {
        val merged = GuideWindowRows.mergeNowNext(existing = null, now = airing, next = later, programmes = listOf(airing, later))
        assertEquals("cells", listOf(airing, later), merged.programmes)
        assertTrue("not marked stored", !merged.storedWindow)
    }

    @Test
    fun `a stored window keeps the ladder's now and next`() {
        val first = GuideWindowRows.mergeNowNext(existing = null, now = airing, next = later, programmes = listOf(airing, later))
        val stored = GuideWindowRows.mergeStoredWindow(first, listOf(past))
        assertEquals("now kept", airing, stored.now)
        assertEquals("next kept", later, stored.next)
        assertEquals("cells replaced by the window", listOf(past), stored.programmes)
        assertTrue("marked stored", stored.storedWindow)
    }

    @Test
    fun `the channel's catch-up table beats the playlist guide store, which fills unfocused rows`() {
        assertEquals("table wins", listOf(1), GuideWindowRows.pick(listOf(1), listOf(2)))
        assertEquals("store fills", listOf(2), GuideWindowRows.pick(emptyList(), listOf(2)))
        assertNull("nothing stored keeps the row", GuideWindowRows.pick(emptyList<Int>(), emptyList()))
    }

    @Test
    fun `a moved window republishes the whole screenful around the focused row, not just it`() {
        val rows = GuideWindowRows.rowsFollowingWindow(focusedIndex = 10, rowCount = 100)
        assertEquals("focused first", 10, rows.first())
        assertTrue("neighbours above", 9 in rows && 2 in rows)
        assertTrue("neighbours below", 11 in rows && 18 in rows)
        assertEquals("bounded", 2 * GuideEpgPrefetchPolicy.RADIUS + 1, rows.size)
    }

    @Test
    fun `a read in flight still paints after a slot roll or one travel step, not after two`() {
        val start = now - now % GuideTimeTravel.SLOT_MS
        val from = GuideWindowRows.readFromMs(start)
        val to = GuideWindowRows.readToMs(start)
        assertTrue("same window", GuideWindowRows.readCovers(from, to, start))
        assertTrue("minute tick rolled a slot", GuideWindowRows.readCovers(from, to, start + GuideTimeTravel.SLOT_MS))
        assertTrue("one page back", GuideWindowRows.readCovers(from, to, start - GuideTimeTravel.WINDOW_MS))
        assertTrue("one page forward", GuideWindowRows.readCovers(from, to, start + GuideTimeTravel.WINDOW_MS))
        assertTrue("two pages back is stale", !GuideWindowRows.readCovers(from, to, start - 2 * GuideTimeTravel.WINDOW_MS))
        assertTrue("two pages forward is stale", !GuideWindowRows.readCovers(from, to, start + 2 * GuideTimeTravel.WINDOW_MS))
    }
}
