package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: live playback stopped dead and only a manual restart brought it back
 * (Onn 4K, 2026-08-17). A provider closing the socket mid-stream surfaces as STATE_ENDED;
 * `onIsPlayingChanged(false)` then called `stopProgressUpdates()`, cancelling the progress job
 * — which is the only thing that samples the live-freeze detector. The reconnect ladder existed,
 * was wired, and could never fire for the one case it was built for.
 */
class PlayerLiveSamplingPolicyTest {

    @Test
    fun `live keeps sampling when the feed ends - that IS the freeze`() {
        assertTrue(
            "cancelling the sampler on ENDED is what made live freezes unrecoverable",
            PlayerLiveSamplingPolicy.shouldKeepSamplingWhileNotPlaying(isLiveFeed = true, isEndedOrIdle = true),
        )
    }

    @Test
    fun `vod stops sampling when the episode ends`() {
        assertFalse(
            "a finished episode has nothing left to sample",
            PlayerLiveSamplingPolicy.shouldKeepSamplingWhileNotPlaying(isLiveFeed = false, isEndedOrIdle = true),
        )
    }

    @Test
    fun `a paused stream keeps sampling either way`() {
        for (live in listOf(true, false)) {
            assertTrue(
                "pause is not ENDED/IDLE, so the loop must survive it",
                PlayerLiveSamplingPolicy.shouldKeepSamplingWhileNotPlaying(isLiveFeed = live, isEndedOrIdle = false),
            )
        }
    }

    @Test
    fun `a live feed reaching ENDED is never an episode finishing`() {
        assertFalse(
            "marking a dropped live feed 'watched' and auto-playing next is wrong",
            PlayerLiveSamplingPolicy.isNaturalCompletionCandidate(isLiveFeed = true),
        )
        assertTrue(PlayerLiveSamplingPolicy.isNaturalCompletionCandidate(isLiveFeed = false))
    }
}
