package com.nuvio.tv.core.iptv.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV twin of NuvioMobile's `IndexKeyBatchTest`.
 *
 * The `keys` table is `WITHOUT ROWID` with `PRIMARY KEY(provider, kind, k, sid)`, so its primary key
 * IS the storage B-tree. Inserting a catalog's key rows in catalog order lands them at random
 * leaves — page splits, no write locality. Sorting each batch by `(k, sid)` makes the writes arrive
 * in B-tree order, which shortens the transaction and so the writer lock the hub's reads queue on.
 *
 * NOTE: JUnit argument order here — `assertEquals(message, expected, actual)` — NOT kotlin.test's
 * `(expected, actual, message)`. The mobile twin uses the other one.
 */
class IndexKeyBatchTest {

    private fun item(sid: Int, name: String) =
        IndexedItem(sid = sid, name = name, year = null, tmdb = null, ext = null)

    @Test
    fun `key rows are emitted in primary-key order`() {
        val rows = sortedKeyRows(listOf(item(3, "Zulu"), item(1, "Alpha"), item(2, "Mike")))

        assertEquals("rows must ascend by key", rows.map { it.key }.sorted(), rows.map { it.key })
        assertTrue(rows.isNotEmpty())
    }

    @Test
    fun `rows with the same key ascend by sid`() {
        val rows = sortedKeyRows(listOf(item(9, "Heat"), item(2, "Heat"), item(5, "Heat")))
            .filter { it.key == "heat" }

        assertEquals("same key must ascend by sid", listOf(2, 5, 9), rows.map { it.sid })
    }

    @Test
    fun `sorting preserves exactly the pairs keysOf produces`() {
        val items = listOf(item(1, "The Matrix (1999)"), item(2, "[REC] (2007)"), item(3, "96.Ikiru.1952"))

        val expected = items.flatMap { i -> TitleNormalizer.keysOf(i.name).map { it to i.sid } }.toSet()
        val actual = sortedKeyRows(items).map { it.key to it.sid }.toSet()

        assertEquals("sorting must reorder, never change the set", expected, actual)
    }

    @Test
    fun `an empty batch yields no rows`() {
        assertEquals(emptyList<IndexKeyRow>(), sortedKeyRows(emptyList()))
    }

    @Test
    fun `items with no keys contribute nothing`() {
        val rows = sortedKeyRows(listOf(item(1, "   "), item(2, "Dune")))

        assertTrue("blank name must not emit a row", rows.none { it.sid == 1 })
        assertTrue("a real name still does", rows.any { it.sid == 2 })
        assertTrue("no empty keys", rows.none { it.key.isEmpty() })
    }
}
