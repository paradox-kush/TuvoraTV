package com.nuvio.tv.core.analytics

import com.nuvio.tv.core.analytics.LivePlaybackFreezePolicy.Decision
import com.nuvio.tv.core.analytics.LivePlaybackFreezePolicy.Input
import com.nuvio.tv.core.analytics.LivePlaybackFreezePolicy.Kind
import com.nuvio.tv.core.analytics.LivePlaybackFreezePolicy.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePlaybackFreezePolicyTest {

    private val threshold = LivePlaybackFreezePolicy.FREEZE_THRESHOLD_MS

    /** Defaults describe a healthy channel: both the playhead and the buffered edge moving. */
    private fun input(
        state: PlaybackState = PlaybackState.READY,
        wantsToPlay: Boolean = true,
        positionMs: Long = 60_000L,
        lastAdvancedPositionMs: Long = 59_000L,
        sinceLastAdvanceMs: Long = 0L,
        bufferedPositionMs: Long = 70_000L,
        lastAdvancedBufferedPositionMs: Long = 69_000L,
        sinceBufferedAdvanceMs: Long = 0L,
        freezeActive: Boolean = false,
        activeKind: Kind? = null,
        // Defaults describe a healthy picture too: the frame counter moving, well past startup.
        hasVideoTrack: Boolean = true,
        videoProgressTicks: Long = 1_000L,
        lastAdvancedVideoTicks: Long = 999L,
        sinceVideoAdvanceMs: Long = 0L,
        sincePlaybackStartMs: Long = 60_000L,
    ) = Input(
        state = state,
        wantsToPlay = wantsToPlay,
        positionMs = positionMs,
        lastAdvancedPositionMs = lastAdvancedPositionMs,
        sinceLastAdvanceMs = sinceLastAdvanceMs,
        bufferedPositionMs = bufferedPositionMs,
        lastAdvancedBufferedPositionMs = lastAdvancedBufferedPositionMs,
        sinceBufferedAdvanceMs = sinceBufferedAdvanceMs,
        freezeActive = freezeActive,
        activeKind = activeKind,
        hasVideoTrack = hasVideoTrack,
        videoProgressTicks = videoProgressTicks,
        lastAdvancedVideoTicks = lastAdvancedVideoTicks,
        sinceVideoAdvanceMs = sinceVideoAdvanceMs,
        sincePlaybackStartMs = sincePlaybackStartMs,
    )

    /**
     * The reported symptom: audio playing (so the playhead advances normally) while the video
     * output has produced nothing for [frozenForMs].
     */
    private fun videoFrozen(
        frozenForMs: Long,
        hasVideoTrack: Boolean = true,
        sincePlaybackStartMs: Long = 60_000L,
        freezeActive: Boolean = false,
        activeKind: Kind? = null,
    ) = input(
        state = PlaybackState.READY,
        hasVideoTrack = hasVideoTrack,
        videoProgressTicks = 4_200L,
        lastAdvancedVideoTicks = 4_200L,
        sinceVideoAdvanceMs = frozenForMs,
        sincePlaybackStartMs = sincePlaybackStartMs,
        freezeActive = freezeActive,
        activeKind = activeKind,
    )

    /** Both the playhead and the buffered edge pinned in place, for `stalledFor` ms. */
    private fun stalled(stalledForMs: Long, state: PlaybackState = PlaybackState.BUFFERING) = input(
        state = state,
        positionMs = 60_000L,
        lastAdvancedPositionMs = 60_000L,
        sinceLastAdvanceMs = stalledForMs,
        bufferedPositionMs = 62_000L,
        lastAdvancedBufferedPositionMs = 62_000L,
        sinceBufferedAdvanceMs = stalledForMs,
    )

    @Test
    fun `advancing playhead is not a freeze`() {
        assertEquals(Decision.Idle, LivePlaybackFreezePolicy.evaluate(input()))
    }

    @Test
    fun `stall below the threshold is not yet a freeze`() {
        assertEquals(Decision.Idle, LivePlaybackFreezePolicy.evaluate(stalled(threshold - 1)))
    }

    @Test
    fun `stall past the threshold starts a stall freeze`() {
        assertEquals(Decision.Start(Kind.STALLED), LivePlaybackFreezePolicy.evaluate(stalled(threshold)))
    }

    @Test
    fun `a rebuffer whose buffered edge still advances is never a freeze`() {
        // The connection is alive and filling up; reconnecting would throw away a stream that
        // was about to resume on its own.
        val decision = LivePlaybackFreezePolicy.evaluate(
            input(
                state = PlaybackState.BUFFERING,
                positionMs = 60_000L,
                lastAdvancedPositionMs = 60_000L,
                sinceLastAdvanceMs = 10 * threshold,
                bufferedPositionMs = 64_000L,
                lastAdvancedBufferedPositionMs = 62_000L,
                sinceBufferedAdvanceMs = 0L,
            )
        )
        assertEquals(Decision.Idle, decision)
    }

    @Test
    fun `ENDED on a live channel is a freeze immediately`() {
        // A live channel has no end; reaching ENDED means the upstream connection closed and
        // Media3 treated the clean EOF as the content finishing. No threshold wait needed.
        assertEquals(
            Decision.Start(Kind.ENDED),
            LivePlaybackFreezePolicy.evaluate(input(state = PlaybackState.ENDED, sinceLastAdvanceMs = 0L)),
        )
    }

    @Test
    fun `paused playback is never a freeze`() {
        val decision = LivePlaybackFreezePolicy.evaluate(
            stalled(10 * threshold, state = PlaybackState.READY).copy(wantsToPlay = false)
        )
        assertEquals(Decision.Idle, decision)
    }

    @Test
    fun `sub-tolerance jitter does not count as movement`() {
        val decision = LivePlaybackFreezePolicy.evaluate(
            stalled(threshold).copy(
                positionMs = 60_000L + LivePlaybackFreezePolicy.POSITION_TOLERANCE_MS,
                bufferedPositionMs = 62_000L + LivePlaybackFreezePolicy.POSITION_TOLERANCE_MS,
            )
        )
        assertEquals(Decision.Start(Kind.STALLED), decision)
    }

    @Test
    fun `open freeze clears when the playhead moves again`() {
        val decision = LivePlaybackFreezePolicy.evaluate(
            input(positionMs = 62_000L, lastAdvancedPositionMs = 60_000L, freezeActive = true)
        )
        assertEquals(Decision.Recover, decision)
    }

    @Test
    fun `open freeze stays open while the playhead is still stuck`() {
        val decision = LivePlaybackFreezePolicy.evaluate(
            stalled(30_000L).copy(freezeActive = true)
        )
        assertEquals(Decision.Continue(Kind.STALLED), decision)
    }

    @Test
    fun `a reconnected stream restarting near zero reads as recovery`() {
        // Media3's deferred retry, and our own re-prepare, both restart an unknown-length live
        // source near zero. Treating a backwards jump as "not moving" would wedge this policy
        // in a false freeze for the rest of the channel.
        val decision = LivePlaybackFreezePolicy.evaluate(
            input(positionMs = 0L, lastAdvancedPositionMs = 300_000L, freezeActive = true)
        )
        assertEquals(Decision.Recover, decision)
    }

    @Test
    fun `ENDED never self-recovers even if the position reads differently`() {
        val decision = LivePlaybackFreezePolicy.evaluate(
            input(state = PlaybackState.ENDED, positionMs = 65_000L, lastAdvancedPositionMs = 60_000L, freezeActive = true)
        )
        assertEquals(Decision.Continue(Kind.ENDED), decision)
    }

    // --- video-only freezes: picture dead, audio alive -------------------------------------

    @Test
    fun `video output stopping while audio plays on is a freeze`() {
        // The exact user report, and the case this policy was blind to until 2026-08-14: audio
        // keeps the playhead advancing, so every pre-existing check reads this as healthy.
        assertEquals(
            Decision.Start(Kind.VIDEO_STALLED),
            LivePlaybackFreezePolicy.evaluate(videoFrozen(threshold)),
        )
    }

    @Test
    fun `a video stall below the threshold is not yet a freeze`() {
        assertEquals(Decision.Idle, LivePlaybackFreezePolicy.evaluate(videoFrozen(threshold - 1)))
    }

    @Test
    fun `a channel with no picture never reports a video freeze`() {
        // IPTV radio stations render no frames by design and would otherwise freeze permanently.
        assertEquals(
            Decision.Idle,
            LivePlaybackFreezePolicy.evaluate(videoFrozen(threshold * 10, hasVideoTrack = false)),
        )
    }

    @Test
    fun `a video stall during startup is not a freeze`() {
        // Decoders render nothing while warming up, and mpv's FPS estimate needs frames first.
        assertEquals(
            Decision.Idle,
            LivePlaybackFreezePolicy.evaluate(
                videoFrozen(threshold, sincePlaybackStartMs = LivePlaybackFreezePolicy.VIDEO_STARTUP_GRACE_MS - 1)
            ),
        )
    }

    @Test
    fun `an open video freeze stays open while the playhead keeps moving`() {
        // The regression that matters most: resolving on playhead movement would clear this
        // freeze on the very next sample, because audio never stopped.
        assertEquals(
            Decision.Continue(Kind.VIDEO_STALLED),
            LivePlaybackFreezePolicy.evaluate(
                videoFrozen(threshold * 3, freezeActive = true, activeKind = Kind.VIDEO_STALLED)
            ),
        )
    }

    @Test
    fun `an open video freeze clears when frames return`() {
        val decision = LivePlaybackFreezePolicy.evaluate(
            input(
                freezeActive = true,
                activeKind = Kind.VIDEO_STALLED,
                videoProgressTicks = 4_260L,
                lastAdvancedVideoTicks = 4_200L,
                sinceVideoAdvanceMs = 0L,
            )
        )
        assertEquals(Decision.Recover, decision)
    }

    @Test
    fun `everything stopping is still a plain stall, not a video stall`() {
        // A dead pipe already had a kind; a stopped picture only reclassifies it when the
        // playhead is genuinely still moving.
        val decision = LivePlaybackFreezePolicy.evaluate(
            stalled(threshold).copy(
                hasVideoTrack = true,
                videoProgressTicks = 4_200L,
                lastAdvancedVideoTicks = 4_200L,
                sinceVideoAdvanceMs = threshold,
                sincePlaybackStartMs = 60_000L,
            )
        )
        assertEquals(Decision.Start(Kind.STALLED), decision)
    }
}
