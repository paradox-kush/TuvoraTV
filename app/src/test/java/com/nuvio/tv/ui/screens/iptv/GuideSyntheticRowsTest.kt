package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamCatchUp.ProgrammeAction
import com.nuvio.tv.core.iptv.XtreamProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B120 / UX148: the guide's Favourites and Recent rows were built from the Library ★ entry and the
 * device-local recents ref, which know a channel's name and logo but not its archive flag, its
 * archive window or its overlay identity. So catch-up worked from the channel list but a past
 * programme on the SAME channel was dead from Favourites/Recent, and MENU-hide silently did nothing.
 * The rows now take the real channel's identity from the catalog the guide already loaded.
 */
class GuideSyntheticRowsTest {

    private val nowMs = 1_800_000_000_000L
    private val hour = 3_600_000L

    private val catalogBbc = GuideChannel(
        contentId = "xtream:acc:live:7",
        name = "UK: BBC One HD",
        logo = "https://logo/bbc.png",
        streamUrl = "http://panel/live/u/p/7.ts",
        streamId = 7,
        categoryId = "12",
        hasArchive = true,
        catchUpDays = 7,
        entityId = "fp:1:bbc-one",
    )

    /** What the VM built for a Favourites row: the Library ★ entry has a name and logo, nothing else. */
    private val favouriteBbc = GuideChannel("xtream:acc:live:7", "BBC One", "https://logo/fav.png", streamUrl = "", streamId = 7)

    private val pastProgramme = XtreamProgram(
        title = "News at Six",
        description = "",
        startMs = nowMs - 3 * hour,
        endMs = nowMs - 2 * hour,
        nowPlaying = false,
    )

    @Test
    fun `a favourite of an archive channel offers replay for a past programme`() {
        val rows = GuideSyntheticRows.withCatalogIdentity(listOf(favouriteBbc)) { id ->
            catalogBbc.takeIf { it.contentId == id }
        }
        assertEquals(
            "the same past programme that replays from the channel list replays from Favourites",
            ProgrammeAction.REPLAY,
            guideActionFor(pastProgramme, rows.single(), nowMs, catchUpSupported = true),
        )
        assertEquals("the archive window travels with it (time travel depth)", 7, rows.single().catchUpDays)
    }

    @Test
    fun `a favourite row takes the channel identity so MENU-hide has something to hide`() {
        val row = GuideSyntheticRows.withCatalogIdentity(listOf(favouriteBbc)) { catalogBbc }.single()
        assertEquals("overlay identity from the catalog", "fp:1:bbc-one", row.entityId)
        assertNotNull(
            "MENU on the row now produces a hide",
            GuideHideUndoPolicy.onChannelMenu(row.entityId, "acc", alreadyHidden = false, name = row.name, playlistName = null),
        )
    }

    @Test
    fun `the row keeps what the viewer saw and only fills what it lacked`() {
        val row = GuideSyntheticRows.withCatalogIdentity(listOf(favouriteBbc)) { catalogBbc }.single()
        assertEquals("content id unchanged", "xtream:acc:live:7", row.contentId)
        assertEquals("name as favourited", "BBC One", row.name)
        assertEquals("logo as favourited", "https://logo/fav.png", row.logo)
        assertEquals("a favourite made on another device has no URL; the catalog's fills it", "http://panel/live/u/p/7.ts", row.streamUrl)
        assertEquals("never category-filtered: no category stamped", null, row.categoryId)
    }

    @Test
    fun `a row the catalog does not know is left as it was`() {
        val row = GuideSyntheticRows.withCatalogIdentity(listOf(favouriteBbc)) { null }.single()
        assertEquals("unchanged", favouriteBbc, row)
        assertEquals("counted as unresolved", 1, GuideSyntheticRows.unresolved(listOf(row)))
    }

    @Test
    fun `a catalog row without identity is not used`() {
        val anonymous = catalogBbc.copy(entityId = "")
        val row = GuideSyntheticRows.withCatalogIdentity(listOf(favouriteBbc)) { anonymous }.single()
        assertEquals("unchanged", favouriteBbc, row)
    }

    @Test
    fun `matches are picked from whatever lists are already loaded, first identity wins`() {
        val other = catalogBbc.copy(contentId = "xtream:acc:live:8", streamId = 8, entityId = "fp:1:itv")
        val matches = GuideSyntheticRows.catalogMatches(
            wanted = setOf("xtream:acc:live:7"),
            sources = sequenceOf(listOf(favouriteBbc, other), listOf(catalogBbc), listOf(catalogBbc.copy(entityId = "fp:1:later"))),
        )
        assertEquals("only wanted ids, only rows with identity", mapOf("xtream:acc:live:7" to catalogBbc), matches)
    }

    @Test
    fun `the whole lineup is fetched at most once and only when a row is still unknown`() {
        assertTrue("unknown rows, not tried yet", GuideSyntheticRows.shouldFetchLineup(unresolved = 2, alreadyTried = false))
        assertFalse("everything resolved from memory: no request", GuideSyntheticRows.shouldFetchLineup(unresolved = 0, alreadyTried = false))
        assertFalse("tried this session: a dead favourite must not refetch the catalog on every visit", GuideSyntheticRows.shouldFetchLineup(unresolved = 1, alreadyTried = true))
    }
}
