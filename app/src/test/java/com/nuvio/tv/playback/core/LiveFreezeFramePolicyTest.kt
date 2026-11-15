package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner rule (2026-10-03): live TV never shows a black screen. Whenever the picture is interrupted
 * — reconnect, engine handoff, zap, failure — the last frame stays up until a new frame is drawn.
 */
class LiveFreezeFramePolicyTest {
    private val policy = LiveFreezeFramePolicy

    @Test
    fun `a playing picture is captured and never covered`() {
        val playing = snapshot(PlaybackState.PLAYING, generation = 1, rendered = true, playing = true)
        assertEquals(LiveFreezeFramePolicy.Overlay.HIDDEN, policy.overlay(playing))
        assertTrue(policy.mayCapture(playing))
    }

    @Test
    fun `reconnect handoff and recovery hold the last frame`() {
        listOf(
            PlaybackState.LIVE_RECONNECTING,
            PlaybackState.HANDING_OFF_ONCE,
            PlaybackState.RECOVERING_IN_PLACE,
        ).forEach { state ->
            val interrupted = snapshot(state, generation = 1, rendered = true)
            assertEquals("$state", LiveFreezeFramePolicy.Overlay.FROZEN, policy.overlay(interrupted))
            assertFalse("$state must not capture a black surface", policy.mayCapture(interrupted))
        }
    }

    @Test
    fun `a zap holds the previous channel until the new one draws its first frame`() {
        val starting = snapshot(PlaybackState.STARTING_PRIMARY, generation = 2, rendered = false)
        assertEquals(LiveFreezeFramePolicy.Overlay.FROZEN, policy.overlay(starting))

        val firstFrame = snapshot(PlaybackState.PLAYING, generation = 2, rendered = true, playing = true)
        assertEquals(LiveFreezeFramePolicy.Overlay.HIDDEN, policy.overlay(firstFrame))
    }

    @Test
    fun `a stall while playing holds the frame without capturing it`() {
        val stalled = snapshot(PlaybackState.PLAYING, generation = 1, rendered = true, buffering = true)
        assertEquals(LiveFreezeFramePolicy.Overlay.FROZEN, policy.overlay(stalled))
        assertFalse(policy.mayCapture(stalled))
    }

    @Test
    fun `a final failure keeps the frame dimmed under the error`() {
        val failed = snapshot(PlaybackState.FAILED, generation = 1, rendered = true)
        assertEquals(LiveFreezeFramePolicy.Overlay.DIMMED, policy.overlay(failed))
    }

    @Test
    fun `a suspended session keeps the frame for the resume`() {
        // Device (Onn, 2026-10-03): Home then back suspends the session (LIFECYCLE_INACTIVE ->
        // STOPPED) and resumes the same channel; clearing on STOPPED showed black on return.
        // Really leaving disposes the host, which removes the frame anyway.
        assertEquals(
            LiveFreezeFramePolicy.Overlay.FROZEN,
            policy.overlay(snapshot(PlaybackState.STOPPED, generation = 3, rendered = false)),
        )
    }

    @Test
    fun `a session that never played has nothing to hold`() {
        assertEquals(
            LiveFreezeFramePolicy.Overlay.CLEARED,
            policy.overlay(snapshot(PlaybackState.IDLE, generation = 0, rendered = false)),
        )
    }

    @Test
    fun `a paused picture is a real picture and stays uncovered`() {
        val paused = snapshot(PlaybackState.PLAYING, generation = 1, rendered = true, playing = false)
        assertEquals(LiveFreezeFramePolicy.Overlay.HIDDEN, policy.overlay(paused))
    }

    @Test
    fun `a held frame lives only as long as its interruption`() {
        // Emulator (2026-11-15): a stall on channel 1 showed channel 2's picture from 24 s earlier.
        assertTrue(policy.keepsHeldFrame(LiveFreezeFramePolicy.Overlay.FROZEN))
        assertTrue("a failure keeps its frame under the error", policy.keepsHeldFrame(LiveFreezeFramePolicy.Overlay.DIMMED))
        assertFalse("real video ends the interruption", policy.keepsHeldFrame(LiveFreezeFramePolicy.Overlay.HIDDEN))
        assertFalse(policy.keepsHeldFrame(LiveFreezeFramePolicy.Overlay.CLEARED))

        val stallAfterVideo = listOf(
            snapshot(PlaybackState.STARTING_PRIMARY, generation = 2, rendered = false),
            snapshot(PlaybackState.PLAYING, generation = 2, rendered = true, playing = true),
            snapshot(PlaybackState.PLAYING, generation = 2, rendered = true, buffering = true),
        ).map(policy::overlay)
        val released = stallAfterVideo.indexOfFirst { !policy.keepsHeldFrame(it) }
        assertTrue("the first real frame releases the zap's frame before the later stall", released in 0 until 2)
    }

    @Test
    fun `a suspend keeps its frame for the resume`() {
        assertTrue(policy.keepsHeldFrame(policy.overlay(snapshot(PlaybackState.STOPPED, generation = 3, rendered = false))))
    }

    private fun snapshot(
        state: PlaybackState,
        generation: Long,
        rendered: Boolean,
        playing: Boolean = false,
        buffering: Boolean = false,
    ) = PlaybackSnapshot(
        generation = generation,
        state = state,
        isPlaying = playing,
        isBuffering = buffering,
        progress = PlaybackProgressEvidence(renderedVideoFrame = rendered),
    )
}
