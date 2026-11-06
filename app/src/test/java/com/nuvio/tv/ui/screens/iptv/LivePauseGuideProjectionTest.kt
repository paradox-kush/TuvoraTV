package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.contracts.LivePausePhase
import com.nuvio.tv.core.contracts.LivePauseView
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePauseGuideProjectionTest {
    private fun distinct(views: List<LivePauseView>) =
        views.map { it.toGuideProjection() }.fold(emptyList<LivePauseGuideProjection>()) { acc, p ->
            if (acc.lastOrNull() == p) acc else acc + p
        }

    private val shifted = LivePauseView(phase = LivePausePhase.SHIFTED, positionMs = 1_000L, behindLiveMs = 5_000L)

    @Test
    fun `a position only change does not produce a new value`() {
        val ticks = listOf(
            shifted,
            shifted.copy(positionMs = 1_500L, behindLiveMs = 5_500L),
            shifted.copy(positionMs = 2_000L, windowEndMs = 9_000L, bufferFull = true),
        )
        assertEquals("one value for three position ticks", 1, distinct(ticks).size)
    }

    @Test
    fun `phase paused and scrubbable changes each produce a new value`() {
        val views = listOf(
            LivePauseView.OFF,
            LivePauseView(phase = LivePausePhase.PREPARING),
            shifted,
            shifted.copy(paused = true),
            LivePauseView(phase = LivePausePhase.FAILED),
        )
        assertEquals("every step differs", 5, distinct(views).size)
        val scrubbable = distinct(views).map { it.scrubbable }
        assertEquals("scrubbable follows the phase", listOf(false, false, true, true, false), scrubbable)
    }
}
