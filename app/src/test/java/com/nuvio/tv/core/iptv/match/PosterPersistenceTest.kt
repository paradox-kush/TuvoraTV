package com.nuvio.tv.core.iptv.match

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The enrichment-survival contract (research/iptv-catalog-loading.md §12): posters written by
 * PosterEnricher live in the same `items.poster` column the bulk sync writes, and MUST survive
 * every later sync against a panel whose bulk list ships no icons.
 *
 * @Config mirrors IptvContentDbTest: plain Application (the @HiltAndroidApp app can't boot
 * here), sdk 35, Conscrypt off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PosterPersistenceTest {

    private val index = XtreamMatchIndex(RuntimeEnvironment.getApplication())
    private val provider = "acct-1"

    private fun bare(sid: Int, name: String = "Movie $sid") =
        IndexedItem(sid = sid, name = name, year = 2020, tmdb = null, ext = "mp4", poster = null, categoryId = "7")

    @Test
    fun `updatePoster is served back by every read path`() = runBlocking {
        index.rebuild(provider, MatchKind.MOVIE, listOf(bare(1), bare(2)))
        index.updatePoster(provider, MatchKind.MOVIE, 1, "https://img/1.jpg")
        assertEquals("https://img/1.jpg", index.item(provider, MatchKind.MOVIE, 1)?.poster)
        assertEquals(
            listOf("https://img/1.jpg", null),
            index.itemsFor(provider, MatchKind.MOVIE, "7", 0, 10).sortedBy { it.sid }.map { it.poster }
        )
    }

    @Test
    fun `an icon-less re-sync does not wipe enrichment`() = runBlocking {
        index.rebuild(provider, MatchKind.MOVIE, listOf(bare(1)))
        index.updatePoster(provider, MatchKind.MOVIE, 1, "https://img/1.jpg")
        // Same catalog, still no bulk icon — the everyday B-style panel refresh.
        val stats = index.sync(provider, MatchKind.MOVIE, listOf(bare(1)))
        assertEquals(0, stats.changed) // poster is not part of the fingerprint
        assertEquals("https://img/1.jpg", index.item(provider, MatchKind.MOVIE, 1)?.poster)
    }

    @Test
    fun `a renamed row keeps its enriched poster through the rewrite`() = runBlocking {
        index.rebuild(provider, MatchKind.MOVIE, listOf(bare(1, name = "Old Name")))
        index.updatePoster(provider, MatchKind.MOVIE, 1, "https://img/1.jpg")
        val stats = index.sync(provider, MatchKind.MOVIE, listOf(bare(1, name = "New Name")))
        assertEquals(1, stats.changed)
        val row = index.item(provider, MatchKind.MOVIE, 1)
        assertEquals("New Name", row?.name)
        // The rewrite carried a null poster; COALESCE must have kept the enrichment.
        assertEquals("https://img/1.jpg", row?.poster)
    }

    @Test
    fun `a renamed row keeps its enriched poster through the STREAMED sync too`() = runBlocking {
        index.rebuild(provider, MatchKind.MOVIE, listOf(bare(1, name = "Old Name")))
        index.updatePoster(provider, MatchKind.MOVIE, 1, "https://img/1.jpg")
        val session = index.beginSync(provider, MatchKind.MOVIE)
        session.accept(bare(1, name = "New Name"))
        session.finish()
        val row = index.item(provider, MatchKind.MOVIE, 1)
        assertEquals("New Name", row?.name)
        assertEquals("https://img/1.jpg", row?.poster)
    }

    @Test
    fun `a fresh bulk icon still lands on a changed row`() = runBlocking {
        index.rebuild(provider, MatchKind.MOVIE, listOf(bare(1, name = "Old Name")))
        val withIcon = bare(1, name = "New Name").copy(poster = "https://bulk/new.jpg")
        index.sync(provider, MatchKind.MOVIE, listOf(withIcon))
        assertEquals("https://bulk/new.jpg", index.item(provider, MatchKind.MOVIE, 1)?.poster)
    }

    @Test
    fun `unchanged rows stay unchanged across a full sync round-trip`() = runBlocking {
        // Guards the stored-fp/incoming-fp alignment: rows carrying category, epg id and
        // archive flags must fingerprint identically when read back from disk.
        val items = listOf(
            IndexedItem(1, "A", 2020, 7, "mp4", poster = "p", categoryId = "9", epgId = "a.uk", hasArchive = true),
            IndexedItem(2, "B", null, null, null, poster = null, categoryId = null, epgId = null, hasArchive = false),
        )
        index.rebuild(provider, MatchKind.LIVE, items)
        val stats = index.sync(provider, MatchKind.LIVE, items)
        assertEquals(0, stats.changed)
        assertEquals(0, stats.added)
        assertEquals(0, stats.removed)
    }

    @Test
    fun `categories serve in the panel's arrival order, never alphabetized`() = runBlocking {
        // Deliberately anti-alphabetical arrival order.
        val arrival = listOf(bare(1, "Zebra"), bare(2, "Apple"), bare(3, "Mango"))
        index.rebuild(provider, MatchKind.MOVIE, arrival)
        assertEquals(
            listOf("Zebra", "Apple", "Mango"),
            index.itemsFor(provider, MatchKind.MOVIE, "7", 0, 10).map { it.name }
        )
        // The panel reorders (new arrival at the front) — the next sync must serve the NEW order.
        val reordered = listOf(bare(3, "Mango"), bare(1, "Zebra"), bare(2, "Apple"))
        index.sync(provider, MatchKind.MOVIE, reordered)
        assertEquals(
            listOf("Mango", "Zebra", "Apple"),
            index.itemsFor(provider, MatchKind.MOVIE, "7", 0, 10).map { it.name }
        )
        // And the STREAMED sync path stamps arrival order the same way.
        val session = index.beginSync(provider, MatchKind.MOVIE)
        listOf(bare(2, "Apple"), bare(3, "Mango"), bare(1, "Zebra")).forEach { session.accept(it) }
        session.finish()
        assertEquals(
            listOf("Apple", "Mango", "Zebra"),
            index.itemsFor(provider, MatchKind.MOVIE, "7", 0, 10).map { it.name }
        )
    }

    @Test
    fun `no cross-talk between kinds or providers`() = runBlocking {
        index.rebuild(provider, MatchKind.MOVIE, listOf(bare(1)))
        index.rebuild(provider, MatchKind.SERIES, listOf(bare(1)))
        index.updatePoster(provider, MatchKind.MOVIE, 1, "https://img/movie.jpg")
        assertNull(index.item(provider, MatchKind.SERIES, 1)?.poster)
    }
}
