package com.nuvio.tv.core.iptv

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T5 (W2 device pass): an Xtream movie with a TMDB id made no OpenSubtitles request and showed no
 * subtitle button — the TMDB id was read from the local match index only, which a playlist added this
 * session (or a panel still indexing) does not have yet. The panel's own item info is the fallback.
 */
class IptvSubtitleIdResolverTest {

    private val account = "http://panel.example:8080|alice"
    private val matrix = XtreamItemRegistry.vodId(account, 100000)

    @Test
    fun `a movie missing from the index is looked up through the panel's own info`() = runTest {
        val tmdbAsked = mutableListOf<Int>()
        val resolver = IptvSubtitleIdResolver(
            indexTmdb = { _, _, _ -> null },
            panelTmdb = { acc, isSeries, sid -> if (acc == account && !isSeries && sid == 100000) 603 else null },
            imdbOf = { tmdb, _ -> tmdbAsked += tmdb; if (tmdb == 603) "tt0133093" else null },
        )

        assertEquals("public IMDb id", "tt0133093", resolver.publicSubtitleVideoId(matrix, null, null))
        assertEquals("only the TMDB id goes to TMDB", listOf(603), tmdbAsked)
    }

    @Test
    fun `the index answer wins and the panel is not asked`() = runTest {
        var panelCalls = 0
        val resolver = IptvSubtitleIdResolver(
            indexTmdb = { _, _, _ -> 603 },
            panelTmdb = { _, _, _ -> panelCalls++; 999 },
            imdbOf = { tmdb, _ -> if (tmdb == 603) "tt0133093" else null },
        )
        assertEquals("index id used", "tt0133093", resolver.publicSubtitleVideoId(matrix, null, null))
        assertEquals("no panel call", 0, panelCalls)
    }

    @Test
    fun `a series episode gets its season and episode, and no id means no request`() = runTest {
        val resolver = IptvSubtitleIdResolver(
            indexTmdb = { _, _, _ -> null },
            panelTmdb = { _, isSeries, _ -> if (isSeries) 1399 else null },
            imdbOf = { tmdb, isSeries -> if (tmdb == 1399 && isSeries) "tt0944947" else null },
        )
        assertEquals(
            "episode id",
            "tt0944947:1:2",
            resolver.publicSubtitleVideoId(XtreamItemRegistry.seriesId(account, 77), 1, 2),
        )
        val none = IptvSubtitleIdResolver({ _, _, _ -> null }, { _, _, _ -> null }, { _, _ -> "tt1" })
        assertNull("no TMDB id anywhere -> no public id", none.publicSubtitleVideoId(matrix, null, null))
        assertNull("live ids never resolve", none.publicSubtitleVideoId(XtreamItemRegistry.liveId(account, 1), null, null))
    }
}
