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

    private val lineup = listOf("a", "b", "c", "d")

    @Test
    fun `aimed channel in the lineup is the origin`() {
        assertEquals("c", lineup[GuideRapidZapPolicy.target(lineup, "b", "a", LiveZapDirection.NEXT)])
        assertEquals("a", lineup[GuideRapidZapPolicy.target(lineup, "b", "d", LiveZapDirection.PREVIOUS)])
    }

    @Test
    fun `B180 aim outside the lineup zaps from the playing channel not from row one`() {
        // Old behaviour: unknown aim fell back to index 0, so NEXT landed on "b" (row 1) and "a" was skipped.
        assertEquals("d", lineup[GuideRapidZapPolicy.target(lineup, "gone", "c", LiveZapDirection.NEXT)])
        assertEquals("b", lineup[GuideRapidZapPolicy.target(lineup, null, "c", LiveZapDirection.PREVIOUS)])
    }

    @Test
    fun `B180 no aim and no playing channel enters the lineup at its near end`() {
        assertEquals("a", lineup[GuideRapidZapPolicy.target(lineup, null, null, LiveZapDirection.NEXT)])
        assertEquals("d", lineup[GuideRapidZapPolicy.target(lineup, "gone", "alsoGone", LiveZapDirection.PREVIOUS)])
    }

    @Test
    fun `B180 three presses yield three distinct ids even when names repeat`() {
        var aimed: String? = null
        val seen = List(3) {
            lineup[GuideRapidZapPolicy.target(lineup, aimed, "b", LiveZapDirection.NEXT)].also { aimed = it }
        }
        assertEquals(listOf("c", "d", "a"), seen)
    }
}
