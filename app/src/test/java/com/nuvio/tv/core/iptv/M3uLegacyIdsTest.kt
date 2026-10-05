package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.content.ContentCategory
import com.nuvio.tv.core.iptv.content.ContentChannel
import com.nuvio.tv.core.iptv.content.ContentEpisode
import com.nuvio.tv.core.iptv.content.ContentSeries
import com.nuvio.tv.core.iptv.content.ContentVod
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.identity.M3uIdentity
import com.nuvio.tv.data.local.LiveChannelRef
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B64 phase 3 (TV) — where each pre-B64 id moves ([M3uLegacyIds]) and how saved refs follow ([M3uSavedIdRewrite]). */
class M3uLegacyIdsTest {

    private val login = M3uIdentity.Login("alice", "s3cret")
    private val p = "m3u|http://h.com/get.php?type=m3u_plus|u872213e7"
    private val prefix = XtreamItemRegistry.accountPrefix(p)

    @Test
    fun `an ordinal channel moves to the login-free id`() {
        val move = M3uLegacyIds.channel(ContentChannel(12, "BBC One", null, "bbc.uk", "UK", "http://h.com/live/alice/s3cret/42.ts"), login)!!
        assertEquals("live:12", move.oldId)
        assertEquals("live:${M3uIdentity.sidOf("http://h.com/live/42.ts")}", move.newId)
    }

    @Test
    fun `an episode-named movie moves to the episode under its show`() {
        val move = M3uLegacyIds.movie(ContentVod(3, "Breaking Bad S01E02", null, "Drama", "http://h.com/movie/alice/s3cret/55.mp4", "mp4"), login)!!
        assertEquals("vod:3", move.oldId)
        assertEquals("episode:${M3uIdentity.sidOf("http://h.com/movie/55.mp4").toString(16)}", move.newId)
        assertEquals("series:${M3uIdentity.sidOf("series:breaking bad")}", move.seriesId)
        assertEquals(1, move.season); assertEquals(2, move.episode)
    }

    @Test
    fun `an episode and its ordinal series move together`() {
        val moves = M3uLegacyIds.episode(
            ContentEpisode(4, "e17", 1, 3, "The Grand Tour S01E03", null, "http://h.com/series/alice/s3cret/11.mkv", "mkv"),
            ContentSeries(4, "The Grand Tour", null, "SERIES"),
            login,
        )
        assertEquals(
            listOf(
                M3uLegacyIds.Move("episode:e17", "episode:${M3uIdentity.sidOf("http://h.com/series/11.mkv").toString(16)}"),
                M3uLegacyIds.Move("series:4", "series:${M3uIdentity.sidOf("series:the grand tour")}"),
            ),
            moves,
        )
    }

    @Test
    fun `a raw-name category moves to the phone's hash`() {
        assertEquals(
            M3uLegacyIds.Move("cat:live:UK NEWS", "cat:live:${M3uIdentity.sidOf("UK NEWS")}"),
            M3uLegacyIds.category(IptvContentDb.TYPE_LIVE, ContentCategory("UK NEWS", "UK NEWS")),
        )
    }

    private val chMove = M3uLegacyIds.Move("live:12", "live:999")
    private val promoted = M3uLegacyIds.Move("vod:3", "episode:abc", seriesId = "series:77", season = 1, episode = 2, seriesName = "Breaking Bad")
    private val epMove = M3uLegacyIds.Move("episode:e17", "episode:def")
    private val seriesMove = M3uLegacyIds.Move("series:4", "series:88")
    private val cat = M3uLegacyIds.Move("cat:live:UK", "cat:live:123")
    private val rewrite = M3uSavedIdRewrite(p, listOf(chMove, promoted, epMove, seriesMove, cat).associateBy { it.oldId })

    private fun lib(id: String, type: String) = SavedLibraryItem(id, type, "n", null, PosterShape.POSTER, null, null, null, null, emptyList(), null)

    @Test
    fun `saved library refs follow - a promoted movie becomes its show`() {
        assertEquals("${prefix}live:999", rewrite.library(lib("${prefix}live:12", "tv"))?.id)
        val show = rewrite.library(lib("${prefix}vod:3", "movie"))!!
        assertEquals("${prefix}series:77", show.id); assertEquals("series", show.type); assertEquals("Breaking Bad", show.name)
        assertNull(rewrite.library(lib("tt0111161", "movie")))
        assertNull(rewrite.library(lib("xtream:other|x:live:12", "tv")))
    }

    private fun progress(contentId: String, videoId: String, season: Int? = null, episode: Int? = null) = WatchProgress(
        contentId = contentId, contentType = "movie", name = "Breaking Bad S01E02", poster = null, backdrop = null, logo = null,
        videoId = videoId, season = season, episode = episode, episodeTitle = null, position = 60_000, duration = 3_000_000, lastWatched = 1,
    )

    @Test
    fun `progress follows - promoted movie progress becomes episode progress`() {
        val moved = rewrite.progress(progress("${prefix}vod:3", "${prefix}vod:3"))!!
        assertEquals("${prefix}series:77", moved.contentId)
        assertEquals("${prefix}episode:abc", moved.videoId)
        assertEquals("series", moved.contentType)
        assertEquals(1, moved.season); assertEquals(2, moved.episode)
        assertEquals(60_000L, moved.position)
        val ep = rewrite.progress(progress("${prefix}series:4", "${prefix}episode:e17", 1, 3))!!
        assertEquals("${prefix}series:88", ep.contentId); assertEquals("${prefix}episode:def", ep.videoId)
    }

    @Test
    fun `watched marks, live refs and category selections follow`() {
        val mark = WatchedItem(contentId = "${prefix}series:4", contentType = "series", title = "Tour", season = 1, episode = 3, watchedAt = 1)
        assertEquals("${prefix}series:88", rewrite.watched(mark)?.contentId)
        assertEquals("${prefix}live:999", rewrite.liveRef(LiveChannelRef("${prefix}live:12", "BBC", null, "u"))?.id)
        assertEquals(listOf("123", "Sports"), rewrite.categories(XtreamAccount.TYPE_LIVE, listOf("UK", "Sports")))
        assertNull("nothing to move", rewrite.categories(XtreamAccount.TYPE_LIVE, listOf("Sports")))
        assertNull("all stays all", rewrite.categories(XtreamAccount.TYPE_LIVE, null))
    }

    @Test
    fun `a row without a group gets the phone's Uncategorized category`() {
        val entry = com.nuvio.tv.core.iptv.content.M3UEntry("X", null, null, null, null, "http://h/live/a/b/1.ts", com.nuvio.tv.core.iptv.content.M3UKind.LIVE, "ts")
        val row = M3uIngestMapping.map(entry, null) as M3uIngestRow.Channel
        assertEquals("0", row.row.categoryId)
        assertEquals("Uncategorized", M3uIngestMapping.categoryName(null))
    }
}
