package com.nuvio.tv.core.iptv.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The incremental-sync diff: unchanged rows validate by fingerprint, only deltas re-index. */
class CatalogDiffTest {

    private fun item(sid: Int, name: String = "Movie $sid", tmdb: Int? = null) =
        IndexedItem(sid, name, 2020, tmdb, "mkv", "p$sid.jpg")

    private fun existing(vararg items: IndexedItem): Pair<IntArray, IntArray> {
        val sorted = items.sortedBy { it.sid }
        return IntArray(sorted.size) { sorted[it].sid } to
            IntArray(sorted.size) { sorted[it].let { i -> itemFp(i.name, i.year, i.tmdb, i.ext, i.poster) } }
    }

    @Test
    fun `unchanged catalog produces an empty diff`() {
        val items = listOf(item(1), item(2), item(3))
        val (sids, fps) = existing(*items.toTypedArray())
        val diff = diffCatalog(sids, fps, items.shuffled())
        assertTrue(diff.upserts.isEmpty())
        assertTrue(diff.changedSids.isEmpty())
        assertTrue(diff.goneSids.isEmpty())
    }

    @Test
    fun `new item is upserted without touching existing keys`() {
        val (sids, fps) = existing(item(1), item(2))
        val diff = diffCatalog(sids, fps, listOf(item(1), item(2), item(9)))
        assertEquals(listOf(9), diff.upserts.map { it.sid })
        assertTrue(diff.changedSids.isEmpty())
        assertTrue(diff.goneSids.isEmpty())
    }

    @Test
    fun `renamed item is upserted AND flagged for old-key deletion`() {
        val (sids, fps) = existing(item(1), item(2, name = "Old Title | TS"))
        val diff = diffCatalog(sids, fps, listOf(item(1), item(2, name = "Old Title | FHD")))
        assertEquals(listOf(2), diff.upserts.map { it.sid })
        assertEquals(listOf(2), diff.changedSids)
        assertTrue(diff.goneSids.isEmpty())
    }

    @Test
    fun `late tmdb backfill on the panel counts as a change`() {
        val (sids, fps) = existing(item(5, tmdb = null))
        val diff = diffCatalog(sids, fps, listOf(item(5, tmdb = 155)))
        assertEquals(listOf(5), diff.changedSids)
    }

    @Test
    fun `vanished item lands in goneSids`() {
        val (sids, fps) = existing(item(1), item(2), item(3))
        val diff = diffCatalog(sids, fps, listOf(item(1), item(3)))
        assertTrue(diff.upserts.isEmpty())
        assertEquals(listOf(2), diff.goneSids)
    }

    @Test
    fun `duplicate fetched sid does not double-report`() {
        val (sids, fps) = existing(item(1))
        val diff = diffCatalog(sids, fps, listOf(item(1), item(1, name = "Dup Retag")))
        // first occurrence decides: unchanged, the dup is ignored
        assertTrue(diff.upserts.isEmpty())
        assertTrue(diff.goneSids.isEmpty())
    }
}
