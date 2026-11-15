package com.nuvio.tv.core.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileNowPlayingPolicyTest {
    private val news = TileProgramme("News", 1_000, 2_000)
    private val film = TileProgramme("Film", 2_000, 5_000)

    @Test
    fun `the airing programme is line two`() {
        assertEquals("News", TileNowPlayingPolicy.nowTitle(listOf(news, film), 1_500))
        assertEquals("the start counts, the end does not", "Film", TileNowPlayingPolicy.nowTitle(listOf(news, film), 2_000))
    }

    @Test
    fun `nothing airing means no line two, never the previous title`() {
        assertNull(TileNowPlayingPolicy.nowTitle(listOf(news, film), 5_000))
        assertNull(TileNowPlayingPolicy.nowTitle(listOf(news), 500))
        assertNull(TileNowPlayingPolicy.nowTitle(emptyList(), 0))
    }

    @Test
    fun `a blank title is no title`() {
        assertNull(TileNowPlayingPolicy.nowTitle(listOf(TileProgramme(" ", 0, 10)), 5))
    }

    @Test
    fun `the next change is when the airing programme ends or the next one starts`() {
        assertEquals(2_000L, TileNowPlayingPolicy.nextChangeAtMs(listOf(news, film), 1_500))
        assertEquals("gap before the next programme", 1_000L, TileNowPlayingPolicy.nextChangeAtMs(listOf(news), 500))
        assertNull("nothing left, nothing scheduled", TileNowPlayingPolicy.nextChangeAtMs(listOf(news, film), 9_000))
    }
}
