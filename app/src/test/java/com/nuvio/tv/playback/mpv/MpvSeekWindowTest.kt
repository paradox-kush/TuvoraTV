package com.nuvio.tv.playback.mpv

import com.nuvio.tv.core.contracts.LivePauseWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvSeekWindowTest {
    @Test
    fun `no seekable range means no window`() {
        assertNull("empty", MpvSeekWindow.fromCacheState(emptyList(), 10.0))
    }

    @Test
    fun `an unknown play position means no window`() {
        assertNull("no time-pos", MpvSeekWindow.fromCacheState(listOf(MpvCacheRange(0.0, 30.0)), null))
        assertNull("NaN time-pos", MpvSeekWindow.fromCacheState(listOf(MpvCacheRange(0.0, 30.0)), Double.NaN))
    }

    @Test
    fun `one range is the window in milliseconds on mpv's own timeline`() {
        val window = MpvSeekWindow.fromCacheState(listOf(MpvCacheRange(100.5, 130.25)), 120.0)
        assertEquals("window", LivePauseWindow(startMs = 100_500, endMs = 130_250, positionMs = 120_000), window)
    }

    @Test
    fun `with several ranges the one holding the play position wins`() {
        val ranges = listOf(MpvCacheRange(0.0, 10.0), MpvCacheRange(40.0, 70.0))
        assertEquals("first", LivePauseWindow(0, 10_000, 5_000), MpvSeekWindow.fromCacheState(ranges, 5.0))
        assertEquals("second", LivePauseWindow(40_000, 70_000, 55_000), MpvSeekWindow.fromCacheState(ranges, 55.0))
    }

    @Test
    fun `a position outside every range uses the last range and is clamped into it`() {
        val ranges = listOf(MpvCacheRange(0.0, 10.0), MpvCacheRange(40.0, 70.0))
        assertEquals("after", LivePauseWindow(40_000, 70_000, 70_000), MpvSeekWindow.fromCacheState(ranges, 99.0))
        assertEquals("gap", LivePauseWindow(40_000, 70_000, 40_000), MpvSeekWindow.fromCacheState(ranges, 20.0))
    }

    @Test
    fun `broken ranges are ignored`() {
        val ranges = listOf(MpvCacheRange(5.0, 5.0), MpvCacheRange(Double.NaN, 9.0), MpvCacheRange(10.0, 20.0))
        assertEquals("only the sane one", LivePauseWindow(10_000, 20_000, 15_000), MpvSeekWindow.fromCacheState(ranges, 15.0))
        assertNull("all broken", MpvSeekWindow.fromCacheState(listOf(MpvCacheRange(5.0, 4.0)), 4.5))
    }

    @Test
    fun `the position tolerance keeps a position a hair before the range inside it`() {
        // mpv reports time-pos a few ms before a freshly started range; that is still this range.
        val window = MpvSeekWindow.fromCacheState(listOf(MpvCacheRange(10.0, 20.0)), 9.9)
        assertEquals(LivePauseWindow(10_000, 20_000, 10_000), window)
    }
}
