package com.nuvio.tv.core.analytics

import com.nuvio.tv.core.analytics.LivePlaybackFreezePolicy.Decision
import com.nuvio.tv.core.analytics.LivePlaybackFreezePolicy.Input
import com.nuvio.tv.core.analytics.LivePlaybackFreezePolicy.Kind
import com.nuvio.tv.core.analytics.LivePlaybackFreezePolicy.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the shipped mpv false-`VIDEO_STALLED` bug (review pass 3, F1/F2).
 *
 * The mpv video-liveness tick used to be incremented from `estimated-vf-fps` property-change
 * callbacks. Those stop firing once the estimate settles to a steady value (device-proven on the
 * Onn 4K: the count sat at 11 for 60s while 7TV played fine on mpv), so the count plateaued on
 * healthy steady-state playback exactly as it does on a real freeze — the policy could not tell
 * them apart and reported a false video freeze, i.e. a spurious live reconnect (509 risk on
 * `max_connections=1`). [MpvVideoOutputSignal] advances the tick at READ time from the mirrored
 * value instead. These tests drive the real [LivePlaybackFreezePolicy] with ticks from both
 * approaches and prove the new one does not trip on a healthy channel yet still catches a real
 * freeze.
 */
class MpvVideoOutputSignalTest {

    @Test
    fun `constant fps advances the tick every sample`() {
        var ticks = 0L
        repeat(10) { ticks = MpvVideoOutputSignal.advance(ticks, 25.0) }
        // The whole point: a steady fps that emits no further callbacks must still advance.
        assertEquals("steady 25fps must keep advancing the tick", 10L, ticks)
    }

    @Test
    fun `zero fps holds the tick so a freeze stays observable`() {
        var ticks = 7L
        repeat(10) { ticks = MpvVideoOutputSignal.advance(ticks, 0.0) }
        assertEquals("a stopped filter chain must hold the tick", 7L, ticks)
    }

    @Test
    fun `a decayed sub-one-fps estimate counts as frozen`() {
        assertEquals(
            "0.4fps is below the liveness floor and must not advance",
            7L,
            MpvVideoOutputSignal.advance(7L, 0.4),
        )
    }

    // --- End-to-end through the real freeze policy ---

    @Test
    fun `healthy steady playback never trips VIDEO_STALLED with the read-time signal`() {
        val decisions = runChannel(useReadTimeSignal = true) { 25.0 }
        assertTrue(
            "read-time signal must not report a freeze on a healthy channel: $decisions",
            decisions.none { it is Decision.Start && it.kind == Kind.VIDEO_STALLED },
        )
    }

    @Test
    fun `the old callback-count plateaus and trips a false VIDEO_STALLED - the shipped bug`() {
        val decisions = runChannel(useReadTimeSignal = false) { 25.0 }
        assertTrue(
            "the callback-count approach is exactly what shipped the false freeze: $decisions",
            decisions.any { it is Decision.Start && it.kind == Kind.VIDEO_STALLED },
        )
    }

    @Test
    fun `a real freeze - fps falls to zero - is still detected by the read-time signal`() {
        // Healthy for 4s, then the picture dies (fps→0) while audio keeps the playhead moving.
        val decisions = runChannel(useReadTimeSignal = true) { i -> if (i < 4) 25.0 else 0.0 }
        assertTrue(
            "fps→0 must still surface a video freeze: $decisions",
            decisions.any { it is Decision.Start && it.kind == Kind.VIDEO_STALLED },
        )
    }

    /**
     * Runs a live channel with healthy audio (the playhead advances every 1s sample, as it does
     * when only the picture is frozen) and a chosen fps per sample, feeding the policy the tick
     * produced by either the read-time signal or the old callback-count. Returns every decision.
     */
    private fun runChannel(useReadTimeSignal: Boolean, fpsAt: (sampleIndex: Int) -> Double): List<Decision> {
        val out = mutableListOf<Decision>()
        var ticks = 0L
        var lastFpsForOld = Double.NaN
        var lastAdvancedVideoTicks = ticks
        var lastVideoAdvanceAtMs = 0L
        var lastAdvancedPositionMs = 0L
        for (i in 0..20) {
            val t = i * 1000L
            val fps = fpsAt(i)
            ticks = if (useReadTimeSignal) {
                MpvVideoOutputSignal.advance(ticks, fps)
            } else {
                // OLD: the property-change callback fired (and incremented) only when the fps VALUE
                // changed. A settled, steady fps emits nothing → the count plateaus.
                if (fps != lastFpsForOld) { lastFpsForOld = fps; ticks + 1 } else ticks
            }
            val positionMs = t // audio advances the playhead every sample
            if (ticks != lastAdvancedVideoTicks) {
                lastAdvancedVideoTicks = ticks
                lastVideoAdvanceAtMs = t
            }
            out += LivePlaybackFreezePolicy.evaluate(
                Input(
                    state = PlaybackState.READY,
                    wantsToPlay = true,
                    positionMs = positionMs,
                    lastAdvancedPositionMs = lastAdvancedPositionMs,
                    sinceLastAdvanceMs = 0L,
                    // mpv reports the playhead as the buffered position → 0 ahead → full-threshold path.
                    bufferedPositionMs = positionMs,
                    lastAdvancedBufferedPositionMs = lastAdvancedPositionMs,
                    sinceBufferedAdvanceMs = 0L,
                    freezeActive = false,
                    hasVideoTrack = true,
                    videoProgressTicks = ticks,
                    lastAdvancedVideoTicks = lastAdvancedVideoTicks,
                    sinceVideoAdvanceMs = t - lastVideoAdvanceAtMs,
                    sincePlaybackStartMs = t,
                ),
            )
            lastAdvancedPositionMs = positionMs
        }
        return out
    }
}
