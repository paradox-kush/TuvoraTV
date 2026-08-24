package com.nuvio.tv.core.iptv.match

import com.nuvio.tv.core.iptv.XtreamEpisode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Season/episode selection across whole-series and split-season panel shapes. */
class XtreamSeriesEpisodePolicyTest {

    private fun item(sid: Int, name: String) = IndexedItem(sid = sid, name = name, year = null, tmdb = null, ext = null)
    private fun ep(season: Int, num: Int, id: String = "e$season-$num") =
        XtreamEpisode(episodeId = id, season = season, episodeNum = num, title = "EP", plot = null, still = null, streamUrl = "http://x/$id")

    @Test
    fun seasonInNameReadsTheSplitSeasonBranding() {
        assertEquals(5, XtreamSeriesEpisodePolicy.seasonInName("Breaking Bad S5 En"))
        assertEquals(4, XtreamSeriesEpisodePolicy.seasonInName("Stranger Things Season 4"))
        assertEquals(12, XtreamSeriesEpisodePolicy.seasonInName("Grey's Anatomy S12 Fr"))
        // whole-series entries carry no season marker
        assertEquals(null, XtreamSeriesEpisodePolicy.seasonInName("Breaking Bad"))
        assertEquals(null, XtreamSeriesEpisodePolicy.seasonInName("Severance"))
    }

    @Test
    fun editionsForSeasonKeepsRequestedSeasonAndWholeSeriesOnly() {
        val entries = listOf(
            item(1, "Breaking Bad S1 En"),
            item(3, "Breaking Bad S3 En"),
            item(5, "Breaking Bad S5 En"),
            item(9, "Breaking Bad (Hindi)"), // no season marker -> whole-series fallback
        )
        val picked = XtreamSeriesEpisodePolicy.editionsForSeason(entries, 3).map { it.sid }
        assertEquals(listOf(3, 9), picked) // S1/S5 dropped, S3 + the unmarked entry kept
    }

    @Test
    fun splitSeasonEntryMatchesEpisodeByNumberIgnoringFlattenedInternalSeason() {
        // xsc.loruhon.com: "Breaking Bad S5 En" reports every episode as internal season 1
        val s5 = item(66373123, "Breaking Bad S5 En")
        val flattened = listOf(ep(1, 1), ep(1, 2), ep(1, 3), ep(1, 4))
        val hit = XtreamSeriesEpisodePolicy.pickEpisodes(s5, flattened, season = 5, episode = 3)
        assertEquals(listOf("e1-3"), hit.map { it.episodeId })
        // asking a split entry for a season it does NOT represent yields nothing
        assertEquals(emptyList<String>(), XtreamSeriesEpisodePolicy.pickEpisodes(s5, flattened, season = 4, episode = 3).map { it.episodeId })
    }

    @Test
    fun wholeSeriesEntryStillMatchesOnRealInternalSeason() {
        val show = item(100, "Breaking Bad")
        val eps = listOf(ep(1, 1), ep(3, 7), ep(5, 3))
        assertEquals(listOf("e3-7"), XtreamSeriesEpisodePolicy.pickEpisodes(show, eps, season = 3, episode = 7).map { it.episodeId })
        assertEquals(emptyList<String>(), XtreamSeriesEpisodePolicy.pickEpisodes(show, eps, season = 2, episode = 7).map { it.episodeId })
    }
}
