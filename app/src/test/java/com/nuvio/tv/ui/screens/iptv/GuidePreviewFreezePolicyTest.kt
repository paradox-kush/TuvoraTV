package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for a live channel freezing in the guide's fullscreen preview with no recovery.
 *
 * Reproduced on an Onn 4K, 2026-08-18: after ~17 minutes the provider closed the socket, ExoPlayer
 * went to STATE_ENDED, AudioTrack stopped, and nothing else happened — no error was raised (so the
 * preview's only listener never fired), no freeze was reported, no re-tune was attempted. The
 * viewer was left on a frozen frame.
 *
 * The first test below is the one that regressed: the old preview had no notion of ENDED at all.
 *
 * NOTE: JUnit argument order is assertEquals(message, expected, actual) here — the opposite of
 * kotlin.test in the mobile/desktop twins. Do not regex-port between them.
 */
class GuidePreviewFreezePolicyTest {

    @Test
    fun `a live feed reaching ENDED is a dropped stream and must be re-tuned`() {
        assertTrue(
            "a live channel has no end — ENDED means the provider dropped us",
            GuidePreviewFreezePolicy.shouldRetune(
                playbackState = GuidePreviewFreezePolicy.STATE_ENDED,
                isLiveFeed = true,
                attemptsUsed = 0,
            )
        )
    }

    @Test
    fun `a live feed going IDLE is also a dropped stream`() {
        assertTrue(
            "IDLE without an error is the same silent death as ENDED",
            GuidePreviewFreezePolicy.shouldRetune(
                playbackState = GuidePreviewFreezePolicy.STATE_IDLE,
                isLiveFeed = true,
                attemptsUsed = 0,
            )
        )
    }

    @Test
    fun `healthy states never re-tune`() {
        assertFalse(
            "READY is playback working",
            GuidePreviewFreezePolicy.shouldRetune(
                GuidePreviewFreezePolicy.STATE_READY, isLiveFeed = true, attemptsUsed = 0
            )
        )
        assertFalse(
            "BUFFERING is a stream still arriving, not a dead one",
            GuidePreviewFreezePolicy.shouldRetune(
                GuidePreviewFreezePolicy.STATE_BUFFERING, isLiveFeed = true, attemptsUsed = 0
            )
        )
    }

    @Test
    fun `a recording is allowed to end`() {
        assertFalse(
            "catch-up really does finish — re-tuning it would restart the recording",
            GuidePreviewFreezePolicy.shouldRetune(
                GuidePreviewFreezePolicy.STATE_ENDED, isLiveFeed = false, attemptsUsed = 0
            )
        )
    }

    @Test
    fun `recovery is bounded so a dead channel cannot hammer the portal`() {
        assertTrue(
            "the second attempt is still allowed",
            GuidePreviewFreezePolicy.shouldRetune(
                GuidePreviewFreezePolicy.STATE_ENDED, isLiveFeed = true, attemptsUsed = 1
            )
        )
        assertFalse(
            "panels cap concurrent connections — stop after the budget is spent",
            GuidePreviewFreezePolicy.shouldRetune(
                GuidePreviewFreezePolicy.STATE_ENDED,
                isLiveFeed = true,
                attemptsUsed = GuidePreviewFreezePolicy.MAX_RECOVERY_ATTEMPTS,
            )
        )
    }

    @Test
    fun `the viewer is told once the automatic attempts are spent`() {
        assertFalse(
            "while recovery can still run, say nothing — it usually self-heals",
            GuidePreviewFreezePolicy.shouldSurfaceError(
                GuidePreviewFreezePolicy.STATE_ENDED, isLiveFeed = true, attemptsUsed = 0
            )
        )
        assertTrue(
            "a frozen frame with no message is the bug we are fixing",
            GuidePreviewFreezePolicy.shouldSurfaceError(
                GuidePreviewFreezePolicy.STATE_ENDED,
                isLiveFeed = true,
                attemptsUsed = GuidePreviewFreezePolicy.MAX_RECOVERY_ATTEMPTS,
            )
        )
    }
}

/**
 * The policy names ExoPlayer's states as its own constants so it stays pure and framework-free.
 * That is only safe while the numbers actually agree — this pins them to media3 itself, so a
 * library change that renumbered them fails here instead of silently disabling freeze recovery.
 */
class GuidePreviewFreezePolicyStateConstantsTest {

    @Test
    fun `policy state constants match media3`() {
        assertEquals(
            "STATE_IDLE must match media3",
            androidx.media3.common.Player.STATE_IDLE,
            GuidePreviewFreezePolicy.STATE_IDLE
        )
        assertEquals(
            "STATE_BUFFERING must match media3",
            androidx.media3.common.Player.STATE_BUFFERING,
            GuidePreviewFreezePolicy.STATE_BUFFERING
        )
        assertEquals(
            "STATE_READY must match media3",
            androidx.media3.common.Player.STATE_READY,
            GuidePreviewFreezePolicy.STATE_READY
        )
        assertEquals(
            "STATE_ENDED must match media3",
            androidx.media3.common.Player.STATE_ENDED,
            GuidePreviewFreezePolicy.STATE_ENDED
        )
    }
}
