package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.playback.live.LiveZapDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T4 (W2 device pass): zapping from an "All favorites" channel walked into ANOTHER playlist's
 * favourite (the list mixes playlists; the playback lineup is the guide playlist's alone).
 */
class GuideZapLineupPolicyTest {

    private val a1 = XtreamItemRegistry.liveId("m3u|http://a.example/list.m3u", 1)
    private val b1 = XtreamItemRegistry.liveId("http://b.example|bob", 1)
    private val a2 = XtreamItemRegistry.liveId("m3u|http://a.example/list.m3u", 2)
    private val allFavourites = listOf(a1, b1, a2)

    @Test
    fun `an all-favourites zap never lands on another playlist's channel`() {
        val lineup = GuideZapLineupPolicy.zappable(allFavourites, "m3u|http://a.example/list.m3u") { it }

        assertEquals("only this playlist's favourites are zappable", listOf(a1, a2), lineup)
        val next = lineup[GuideRapidZapPolicy.advance(lineup.indexOf(a1), lineup.size, LiveZapDirection.NEXT)]
        assertEquals("DOWN from A1 goes to A2, skipping B's favourite", a2, next)
        val previous = lineup[GuideRapidZapPolicy.advance(lineup.indexOf(a1), lineup.size, LiveZapDirection.PREVIOUS)]
        assertEquals("UP from A1 wraps to A2, never to B", a2, previous)
    }

    @Test
    fun `a single-playlist list is unchanged`() {
        val one = listOf(a1, a2)
        assertEquals("nothing filtered", one, GuideZapLineupPolicy.zappable(one, "m3u|http://a.example/list.m3u") { it })
    }

    @Test
    fun `with no playlist known the list is used as it is`() {
        assertEquals("no account -> unchanged", allFavourites, GuideZapLineupPolicy.zappable(allFavourites, null) { it })
    }
}
