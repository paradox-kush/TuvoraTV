package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B59 (GitHub #23): a resume whose source is swapped before the first frame (IPTV link refresh,
 * auto-retry) reopened at 0:00 because the swap read the player position, which is 0 until
 * playback starts, and the resume target had already been handed to the first load.
 */
class PlaybackStartPositionPolicyTest {

    @Test
    fun `a swap before the first frame keeps the requested resume`() {
        assertEquals(
            "player still at 0 before the first frame",
            822_000L,
            PlaybackStartPositionPolicy.targetAfterSourceSwap(
                isLive = false,
                firstFrameShown = false,
                currentPositionMs = 0L,
                requestedStartMs = 822_000L,
            ),
        )
        assertEquals(
            "position unknown (engine not ready)",
            822_000L,
            PlaybackStartPositionPolicy.targetAfterSourceSwap(
                isLive = false,
                firstFrameShown = false,
                currentPositionMs = null,
                requestedStartMs = 822_000L,
            ),
        )
    }

    @Test
    fun `once playing the swap resumes where the viewer is`() {
        assertEquals(
            "watched past the resume point",
            900_000L,
            PlaybackStartPositionPolicy.targetAfterSourceSwap(
                isLive = false,
                firstFrameShown = true,
                currentPositionMs = 900_000L,
                requestedStartMs = 822_000L,
            ),
        )
        assertEquals(
            "sought back before the resume point",
            10_000L,
            PlaybackStartPositionPolicy.targetAfterSourceSwap(
                isLive = false,
                firstFrameShown = true,
                currentPositionMs = 10_000L,
                requestedStartMs = 822_000L,
            ),
        )
    }

    @Test
    fun `live always rejoins at the edge`() {
        assertEquals(
            "live",
            0L,
            PlaybackStartPositionPolicy.targetAfterSourceSwap(
                isLive = true,
                firstFrameShown = false,
                currentPositionMs = 5_000L,
                requestedStartMs = 822_000L,
            ),
        )
    }

    @Test
    fun `a fresh start with no resume stays at zero`() {
        assertEquals(
            "no resume requested",
            0L,
            PlaybackStartPositionPolicy.targetAfterSourceSwap(
                isLive = false,
                firstFrameShown = false,
                currentPositionMs = null,
                requestedStartMs = 0L,
            ),
        )
    }
}
