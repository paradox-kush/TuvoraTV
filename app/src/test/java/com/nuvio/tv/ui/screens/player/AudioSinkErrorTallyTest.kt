package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [tallyAudioSinkError], the trip logic behind the wedged-passthrough
 * PCM fallback.
 *
 * A silently wedged sink (Amlogic HAL rejecting an AC3 bitstream under Force optical
 * passthrough) errors continuously — roughly one per 800ms — so a burst inside one window
 * must trip. Isolated errors, which do happen benignly, must NOT: tripping on those would
 * kick a healthy passthrough session down to PCM for no reason.
 */
class AudioSinkErrorTallyTest {

    private val windowMs = PlayerRuntimeController.AUDIO_SINK_ERROR_WINDOW_MS
    private val threshold = PlayerRuntimeController.AUDIO_SINK_ERROR_THRESHOLD

    @Test
    fun `first error opens the window and does not trip`() {
        val tally = tallyAudioSinkError(nowMs = 10_000L, windowStartMs = 0L, count = 0)
        assertEquals(10_000L, tally.windowStartMs)
        assertEquals(1, tally.count)
        assertFalse(tally.tripped)
    }

    @Test
    fun `a burst inside the window trips at the threshold`() {
        var start = 0L
        var count = 0
        var tripped = false
        repeat(threshold) { i ->
            val tally = tallyAudioSinkError(nowMs = 10_000L + i * 800L, windowStartMs = start, count = count)
            start = tally.windowStartMs
            count = tally.count
            tripped = tally.tripped
        }
        assertEquals(threshold, count)
        assertTrue(tripped)
    }

    @Test
    fun `errors spaced beyond the window never trip`() {
        var start = 0L
        var count = 0
        repeat(10) { i ->
            val tally = tallyAudioSinkError(
                nowMs = 10_000L + i * (windowMs + 1_000L),
                windowStartMs = start,
                count = count
            )
            start = tally.windowStartMs
            count = tally.count
            assertFalse("isolated error #$i must not trip the PCM rebuild", tally.tripped)
            assertEquals(1, tally.count)
        }
    }

    @Test
    fun `an expired window restarts the count`() {
        val first = tallyAudioSinkError(nowMs = 10_000L, windowStartMs = 0L, count = 0)
        val second = tallyAudioSinkError(nowMs = 10_800L, windowStartMs = first.windowStartMs, count = first.count)
        assertEquals(2, second.count)

        val afterGap = tallyAudioSinkError(
            nowMs = 10_800L + windowMs + 1L,
            windowStartMs = second.windowStartMs,
            count = second.count
        )
        assertEquals(1, afterGap.count)
        assertFalse(afterGap.tripped)
    }
}
