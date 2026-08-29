package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.playback.live.LiveZapDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideRapidZapPolicyTest {
    @Test
    fun `ten rapid next presses land exactly plus ten`() {
        val final = List(10) { LiveZapDirection.NEXT }.fold(4) { index, direction ->
            GuideRapidZapPolicy.advance(index, 30, direction)
        }
        assertEquals(14, final)
    }

    @Test
    fun `mixed rapid presses preserve every highlight step`() {
        val final = listOf(
            LiveZapDirection.NEXT,
            LiveZapDirection.NEXT,
            LiveZapDirection.PREVIOUS,
            LiveZapDirection.NEXT,
        ).fold(4) { index, direction -> GuideRapidZapPolicy.advance(index, 30, direction) }
        assertEquals(6, final)
    }
}
