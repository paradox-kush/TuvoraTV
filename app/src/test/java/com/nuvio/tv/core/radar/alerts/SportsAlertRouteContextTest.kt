package com.nuvio.tv.core.radar.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

class SportsAlertRouteContextTest {
    private fun ctx(route: String?, type: String? = null, content: String? = null, game: String? = null) =
        SportsAlertRouteContext.from(route, type, content, game)

    @Test
    fun `the player is Vod unless the content is live`() {
        assertEquals("movie", PlaybackContext.Vod, ctx("player/{streamUrl}/{title}?x={x}", "movie", "tt1").playback)
        assertEquals("live channel on the legacy player", PlaybackContext.LiveTv, ctx("player/{streamUrl}/{title}?x={x}", "live", "xtream:1").playback)
        assertEquals("content id kept", "tt1", ctx("player/{streamUrl}/{title}", "movie", "tt1").playingContentId)
    }

    @Test
    fun `the clean live player and multiview are live tv`() {
        assertEquals("clean live", PlaybackContext.LiveTv, ctx("clean-live/{launchToken}").playback)
        assertEquals("multiview", PlaybackContext.LiveTv, ctx("multiview?start={start}").playback)
    }

    @Test
    fun `the game page reports its game and is not playback`() {
        val c = ctx("game_detail/{gameId}?watching={watching}", game = "evt9")
        assertEquals("game", "evt9", c.openGameEventId)
        assertEquals("no playback", PlaybackContext.None, c.playback)
    }

    @Test
    fun `everything else is nothing`() {
        assertEquals("home", SportsAlertRouteContext(), ctx("home"))
        assertEquals("null", SportsAlertRouteContext(), ctx(null))
        assertEquals("blank game id", null, ctx("game_detail/{gameId}", game = " ").openGameEventId)
    }

    @Test
    fun `the clean live player's channel fills the playing content id`() {
        val clean = ctx("clean-live/{launchToken}")
        assertEquals("route alone knows nothing", null, clean.playingContentId)
        assertEquals("filled from the session", "xtream:7", clean.withCleanLiveChannel("xtream:7").playingContentId)
        assertEquals("still live tv", PlaybackContext.LiveTv, clean.withCleanLiveChannel("xtream:7").playback)
        assertEquals("a film route ignores it", null, ctx("player/{a}", "movie", null).withCleanLiveChannel("xtream:7").playingContentId)
        assertEquals("a player route's own id wins", "tt1", ctx("player/{a}", "live", "tt1").withCleanLiveChannel("xtream:7").playingContentId)
    }
}
