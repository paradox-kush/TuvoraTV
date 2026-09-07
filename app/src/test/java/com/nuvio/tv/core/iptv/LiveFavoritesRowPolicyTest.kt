package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.LiveFavoritesRowPolicy.FavoriteEntry
import com.nuvio.tv.core.iptv.LiveFavoritesRowPolicy.LocalRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression for "favourites made on mobile don't show in TV's live Favorites row". The row was built
 * by filtering each synced favourite through the device-local (never-synced) live store, so a
 * favourite made on another device — which has no local ref on TV — was dropped. The first test fails
 * on the old drop-when-no-ref behaviour and passes once every live favourite yields a row.
 *
 * JUnit arg order: assertEquals(message, expected, actual).
 */
class LiveFavoritesRowPolicyTest {

    private val prefix = XtreamItemRegistry.accountPrefix("acc1")   // "xtream:acc1:"
    private val liveUnderAccount = "xtream:acc1:live:100"
    private fun streamIdOf(id: String): Int = id.substringAfterLast(":live:").toIntOrNull() ?: 0

    @Test
    fun `a synced live favourite with no local ref still yields one row from the library entry`() {
        val rows = LiveFavoritesRowPolicy.rows(
            entries = listOf(FavoriteEntry(liveUnderAccount, "Sky Sports", "logo-sky")),
            accountPrefix = prefix,
            localRef = { null },   // favourite made on ANOTHER device: TV has no device-local ref
            streamIdOf = ::streamIdOf,
        )
        assertEquals("a mobile-made favourite must still produce a row", 1, rows.size)
        val row = rows.single()
        assertEquals("row carries the library entry's own id", liveUnderAccount, row.contentId)
        assertEquals("row carries the library entry's name", "Sky Sports", row.name)
        assertEquals("row carries the library entry's logo", "logo-sky", row.logo)
        assertEquals("no local ref => empty stream url (resolved from the id at play time)", "", row.streamUrl)
        assertEquals("stream id derived from the content id", 100, row.streamId)
    }

    @Test
    fun `a favourite under a different account prefix is excluded`() {
        val rows = LiveFavoritesRowPolicy.rows(
            entries = listOf(FavoriteEntry("xtream:acc2:live:200", "Other", null)),
            accountPrefix = prefix,
            localRef = { null },
            streamIdOf = ::streamIdOf,
        )
        assertEquals("only this account's favourites belong in its row", 0, rows.size)
    }

    @Test
    fun `a non-live vod favourite is excluded`() {
        val rows = LiveFavoritesRowPolicy.rows(
            entries = listOf(FavoriteEntry("xtream:acc1:vod:5", "A Movie", null)),
            accountPrefix = prefix,
            localRef = { null },
            streamIdOf = ::streamIdOf,
        )
        assertEquals("the live favourites row must not include VOD", 0, rows.size)
    }

    @Test
    fun `when a device-local ref exists its name logo and stream url win`() {
        val rows = LiveFavoritesRowPolicy.rows(
            entries = listOf(FavoriteEntry(liveUnderAccount, "Library Name", "library-logo")),
            accountPrefix = prefix,
            localRef = { LocalRef("Ref Name", "ref-logo", "http://host/live/100.ts") },
            streamIdOf = ::streamIdOf,
        )
        val row = rows.single()
        assertEquals("local ref name wins", "Ref Name", row.name)
        assertEquals("local ref logo wins", "ref-logo", row.logo)
        assertEquals("local ref stream url wins (fast-path playback)", "http://host/live/100.ts", row.streamUrl)
    }
}
