package com.nuvio.tv.core.iptv.match

import com.nuvio.tv.core.iptv.CategorySelections
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.iptv.XtreamEpisode
import com.nuvio.tv.core.iptv.XtreamMovie
import com.nuvio.tv.core.iptv.XtreamSeriesDetail
import com.nuvio.tv.core.iptv.stalker.StalkerClient
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tmdb.TmdbTitleBundle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B19: a title's IPTV source list honours the playlist's in-app content-type and category settings. */
class XtreamStreamSourceCategoryTest {

    private val client = mockk<XtreamClient>()
    private val stalker = mockk<StalkerClient>()
    private val resolver = mockk<XtreamTmdbResolver>()
    private val index = mockk<XtreamMatchIndex>()
    private val tmdb = mockk<TmdbService>()
    private val source = XtreamStreamSource(client, stalker, resolver, index, tmdb)

    private fun account(
        selections: CategorySelections = CategorySelections(),
        types: Set<String> = XtreamAccount.DEFAULT_CONTENT_TYPES,
        sourceType: String = XtreamAccount.SOURCE_XTREAM,
    ) = XtreamAccount(
        id = "http://h|u", name = "P", baseUrl = "http://h", username = "u", password = "p",
        sourceType = sourceType, contentTypes = types, categorySelections = selections,
    )

    private fun stubTmdb(type: String, title: String, year: Int?) {
        coEvery { tmdb.ensureTmdbId("tt1", type, any()) } returns "603"
        coEvery { tmdb.titleBundle(603, type) } returns TmdbTitleBundle(title, title, emptyList(), year)
    }

    @Test
    fun `a movie edition in a hidden category is not offered`() = runTest {
        val acc = account(CategorySelections(movies = listOf("10")))
        stubTmdb("movie", "The Matrix", 1999)
        val hidden = IndexedItem(sid = 1, name = "The Matrix (HIDDEN)", year = 1999, tmdb = 603, ext = "mkv", categoryId = "30")
        val allowed = IndexedItem(sid = 2, name = "The Matrix 4K", year = 1999, tmdb = 603, ext = "mkv", categoryId = "10")
        coEvery { resolver.resolve(acc, MatchKind.MOVIE, 603, any()) } returns XtreamMatch(hidden, "tmdb")
        coEvery { index.byTmdb(acc.id, MatchKind.MOVIE, 603) } returns listOf(hidden, allowed)
        every { client.buildStreamUrl(acc, "movie", any(), any()) } answers { "url${thirdArg<Int>()}" }

        val streams = source.streamsFor(acc, "movie", "tt1", null, null)

        assertEquals("only the allowed edition is a source", listOf("url2"), streams.map { it.url })
    }

    @Test
    fun `a disabled content type yields no sources and makes no calls`() = runTest {
        val acc = account(types = setOf(XtreamAccount.TYPE_LIVE, XtreamAccount.TYPE_SERIES))

        val streams = source.streamsFor(acc, "movie", "tt1", null, null)

        assertTrue("movies switched off offers nothing", streams.isEmpty())
        coVerify(exactly = 0) { tmdb.ensureTmdbId(any(), any(), any()) }
    }

    @Test
    fun `hidden series editions cannot crowd allowed ones out of the cap`() = runTest {
        val acc = account(CategorySelections(series = listOf("10")))
        stubTmdb("series", "Show", 2010)
        // Five hidden editions ahead of three allowed: capping (5) before filtering would keep
        // only hidden ones.
        val editions = (1..5).map { IndexedItem(sid = it, name = "Show HIDDEN $it", year = 2010, tmdb = 603, ext = null, categoryId = "30") } +
            (6..8).map { IndexedItem(sid = it, name = "Show OK $it", year = 2010, tmdb = 603, ext = null, categoryId = "10") }
        coEvery { resolver.resolve(acc, MatchKind.SERIES, 603, any()) } returns null
        coEvery { index.byTmdb(acc.id, MatchKind.SERIES, 603) } returns editions
        coEvery { index.probe(acc.id, MatchKind.SERIES, any()) } returns emptyList()
        coEvery { client.seriesInfo(acc, any()) } answers {
            val sid = secondArg<Int>()
            Result.success(
                XtreamSeriesDetail(
                    tmdbId = null, plot = null, backdrop = null,
                    episodes = listOf(XtreamEpisode("e$sid", 1, 1, "Ep", null, null, "url$sid")),
                )
            )
        }

        val streams = source.streamsFor(acc, "series", "tt1", 1, 1)

        assertEquals("the three allowed editions are offered", listOf("url6", "url7", "url8"), streams.map { it.url })
    }

    @Test
    fun `a Stalker movie in a hidden category is not offered`() = runTest {
        val acc = account(CategorySelections(movies = listOf("10")), sourceType = XtreamAccount.SOURCE_STALKER)
        stubTmdb("movie", "The Matrix", 1999)
        coEvery { stalker.searchMovies(acc, "The Matrix") } returns listOf(
            XtreamMovie(streamId = 31, name = "The Matrix", poster = null, categoryId = "30", rating = null, streamUrl = ""),
            XtreamMovie(streamId = 11, name = "The Matrix", poster = null, categoryId = "10", rating = null, streamUrl = ""),
        )

        val streams = source.streamsFor(acc, "movie", "tt1", null, null)

        assertEquals("one Stalker source", 1, streams.size)
        assertTrue("it is the allowed edition", streams.single().url!!.contains("|movie|11|"))
    }
}
