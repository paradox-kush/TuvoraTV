package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.match.IndexedItem
import com.nuvio.tv.core.iptv.match.MatchKind
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.iptv.match.XtreamTmdbResolver
import com.nuvio.tv.core.iptv.stalker.StalkerClient
import com.nuvio.tv.data.local.XtreamAccountStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B12 (Stalker VOD in search) + B19 (search honours the in-app category settings for movies/series,
 * not only for live channels).
 */
class XtreamSearchIndexCategoryTest {

    private val store = mockk<XtreamAccountStore>()
    private val factory = mockk<IptvClientFactory>()
    private val xtreamClient = mockk<XtreamClient>()
    private val stalker = mockk<StalkerClient>()
    private val matchIndex = mockk<XtreamMatchIndex>()
    private val resolver = mockk<XtreamTmdbResolver>()
    private val contentDb = mockk<IptvContentDb>()
    private val registry = XtreamItemRegistry()
    private val overlayRepository = mockk<com.nuvio.tv.core.iptv.overlay.IptvOverlayRepository> {
        io.mockk.coEvery { freshSnapshot() } returns com.nuvio.tv.core.iptv.overlay.OverlaySnapshot()
    }
    private val index = XtreamSearchIndex(store, factory, xtreamClient, registry, matchIndex, resolver, contentDb, overlayRepository)

    // Live is switched off so the tests need no live-channel fetch.
    private fun account(sourceType: String, selections: CategorySelections) = XtreamAccount(
        id = "acc", name = "P", baseUrl = "http://h", username = "u", password = "p",
        sourceType = sourceType,
        contentTypes = setOf(XtreamAccount.TYPE_MOVIES, XtreamAccount.TYPE_SERIES),
        categorySelections = selections,
    )

    @Test
    fun `Stalker movies and series are searched through the portal and honour categories`() = runTest {
        val acc = account(XtreamAccount.SOURCE_STALKER, CategorySelections(movies = listOf("10")))
        every { store.accounts } returns flowOf(listOf(acc))
        every { factory.stalker() } returns stalker
        coEvery { stalker.searchMovies(acc, "Matrix") } returns listOf(
            XtreamMovie(streamId = 31, name = "Matrix HIDDEN", poster = null, categoryId = "30", rating = null, streamUrl = ""),
            XtreamMovie(streamId = 11, name = "Matrix", poster = null, categoryId = "10", rating = null, streamUrl = ""),
        )
        coEvery { stalker.searchSeries(acc, "Matrix") } returns listOf(
            XtreamSeriesItem(seriesId = 5, name = "Matrix Show", poster = null, categoryId = "7", plot = null, rating = null),
        )

        val results = index.search("Matrix")

        assertEquals("allowed Stalker movie only", listOf(XtreamItemRegistry.vodId("acc", 11)), results.movies.map { it.contentId })
        assertEquals("Stalker series (no series selection = all)", listOf(XtreamItemRegistry.seriesId("acc", 5)), results.series.map { it.contentId })
        assertEquals("hit is registered for click-through", "Matrix", registry.get(XtreamItemRegistry.vodId("acc", 11))?.name)
    }

    @Test
    fun `Xtream match-index movies in a hidden category are not search hits`() = runTest {
        val acc = account(XtreamAccount.SOURCE_XTREAM, CategorySelections(movies = listOf("10")))
        every { store.accounts } returns flowOf(listOf(acc))
        coEvery { resolver.ensureIndexed(acc, any(), any()) } just runs
        coEvery { matchIndex.searchByName("acc", MatchKind.MOVIE, "Matrix", any()) } returns listOf(
            IndexedItem(sid = 1, name = "Matrix HIDDEN", year = null, tmdb = null, ext = "mkv", categoryId = "30"),
            IndexedItem(sid = 2, name = "Matrix", year = null, tmdb = null, ext = "mkv", categoryId = "10"),
        )
        coEvery { matchIndex.searchByName("acc", MatchKind.SERIES, "Matrix", any()) } returns emptyList()
        every { xtreamClient.buildStreamUrl(acc, "movie", any(), any()) } returns "u"

        val results = index.search("Matrix")

        assertEquals("allowed movie only", listOf(XtreamItemRegistry.vodId("acc", 2)), results.movies.map { it.contentId })
    }

    // F01: a group hidden on a device or the website stays out of search.
    @Test
    fun `movies in a group the viewer hid are not search hits`() = runTest {
        val acc = account(XtreamAccount.SOURCE_STALKER, CategorySelections())
        every { store.accounts } returns flowOf(listOf(acc))
        every { factory.stalker() } returns stalker
        val sourceClient = mockk<IptvClient>()
        every { factory.clientFor(acc) } returns sourceClient
        coEvery { sourceClient.vodCategories(acc) } returns Result.success(listOf(XtreamCategory("30", "Horror"), XtreamCategory("10", "Action")))
        coEvery { overlayRepository.freshSnapshot() } returns com.nuvio.tv.core.iptv.overlay.OverlaySnapshot(
            categories = mapOf(
                com.nuvio.tv.core.iptv.identity.IptvIdentity.categoryKey("acc", XtreamAccount.TYPE_MOVIES, "Horror") to
                    com.nuvio.tv.core.iptv.overlay.CategoryOverlay(hidden = true),
            ),
        )
        coEvery { stalker.searchMovies(acc, "Night") } returns listOf(
            XtreamMovie(streamId = 31, name = "Night Horror", poster = null, categoryId = "30", rating = null, streamUrl = ""),
            XtreamMovie(streamId = 11, name = "Night Action", poster = null, categoryId = "10", rating = null, streamUrl = ""),
        )
        coEvery { stalker.searchSeries(acc, "Night") } returns emptyList()

        val results = index.search("Night")

        assertEquals("the hidden group's movie is left out", listOf(XtreamItemRegistry.vodId("acc", 11)), results.movies.map { it.contentId })
    }
}
