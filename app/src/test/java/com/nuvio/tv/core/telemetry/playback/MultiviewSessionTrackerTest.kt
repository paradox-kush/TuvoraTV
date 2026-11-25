package com.nuvio.tv.core.telemetry.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewSessionTrackerTest {
    private var now = 0L
    private val tracker = MultiviewSessionTracker { now }

    @Test
    fun `nothing closes when nothing opened`() {
        assertNull(tracker.close())
        assertEquals("tiles before open are ignored", 0, tracker.onTiles(2, 2, setOf(1)))
    }

    @Test
    fun `a normal session ends as user with its peaks`() {
        tracker.open()
        tracker.onTiles(2, 2, emptySet())
        tracker.onTiles(4, 4, emptySet())
        tracker.onTiles(3, 3, emptySet())
        now = 95_000
        val s = tracker.close()!!
        assertEquals(4, s.tilesMax)
        assertEquals(4, s.hwDecodersInUse)
        assertEquals(95L, s.durationSeconds)
        assertEquals(0, s.tileFailures)
        assertEquals(MultiviewEndReason.USER, s.endReason)
        assertFalse("closed", tracker.isOpen())
    }

    @Test
    fun `a failing tile is counted once per entry into failed and again after it recovers and fails anew`() {
        tracker.open()
        assertEquals(1, tracker.onTiles(3, 2, setOf(5)))
        assertEquals("still failed", 0, tracker.onTiles(3, 2, setOf(5)))
        assertEquals("recovered", 0, tracker.onTiles(3, 3, emptySet()))
        assertEquals("failed again", 1, tracker.onTiles(3, 2, setOf(5)))
        assertEquals(2, tracker.close()!!.tileFailures)
    }

    @Test
    fun `end reasons follow memory then background then error then user`() {
        // memory beats everything
        tracker.open(); tracker.onTiles(2, 0, setOf(1, 2)); tracker.onBackgrounded(); tracker.onMemoryTrim()
        assertEquals(MultiviewEndReason.MEMORY, tracker.close()!!.endReason)
        // background
        tracker.open(); tracker.onTiles(2, 2, emptySet()); tracker.onBackgrounded()
        assertEquals(MultiviewEndReason.APP_BACKGROUND, tracker.close()!!.endReason)
        // coming back clears the background flag
        tracker.open(); tracker.onTiles(2, 2, emptySet()); tracker.onBackgrounded(); tracker.onForegrounded()
        assertEquals(MultiviewEndReason.USER, tracker.close()!!.endReason)
        // every tile failed
        tracker.open(); tracker.onTiles(2, 0, setOf(1, 2))
        assertEquals(MultiviewEndReason.ERROR, tracker.close()!!.endReason)
        // never had a tile
        tracker.open()
        assertEquals(MultiviewEndReason.OTHER, tracker.close()!!.endReason)
    }

    @Test
    fun `a second session starts clean`() {
        tracker.open(); tracker.onTiles(4, 4, setOf(1)); tracker.onMemorySample(900); tracker.close()
        assertTrue(tracker.open())
        tracker.onTiles(1, 1, emptySet())
        assertEquals(0, tracker.pssPeakMb())
        val s = tracker.close()!!
        assertEquals(1, s.tilesMax)
        assertEquals(0, s.tileFailures)
    }
}
