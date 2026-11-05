package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvLiveDurationPolicyTest {
    private fun growth(vararg readings: Pair<Long, Boolean>, mediaKey: String = "a") =
        readings.fold(MpvLiveDurationPolicy.DurationGrowth()) { state, (durationMs, fromRequested) ->
            MpvLiveDurationPolicy.nextDurationGrowth(state, mediaKey, durationMs, fromRequested)
        }

    @Test
    fun fixedVodDurationDoesNotCountAsGrowing() {
        assertFalse("settling by seconds is not growth", growth(1_320_000L to true, 1_320_000L to true, 1_325_000L to true).grew)
    }

    @Test
    fun durationGrowingPastTheThresholdMarksTheStreamLive() {
        assertTrue(growth(60_000L to true, 75_000L to true).grew)
    }

    @Test
    fun readingsBeforeTheRequestedMediaAreIgnored() {
        // mpv can still report the previous file's long duration while the new one loads.
        assertFalse(growth(5_400_000L to false, 60_000L to true, 62_000L to true).grew)
    }

    @Test
    fun newStreamStartsOver() {
        val grown = growth(60_000L to true, 90_000L to true, mediaKey = "a")
        assertTrue(grown.isLiveFor("a"))
        val next = MpvLiveDurationPolicy.nextDurationGrowth(grown, "b", 90_000L, true)
        assertFalse(next.grew)
        assertEquals(90_000L, next.firstDurationMs)
        assertFalse("stale live flag must not leak onto another stream", grown.isLiveFor("b"))
    }
}
