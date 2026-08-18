package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JUnit here, so the argument order is (message, expected, actual). */
class LiveChannelZapPolicyTest {

    @Test
    fun `steps to the neighbour in each direction`() {
        assertEquals("down", 3, LiveChannelZapPolicy.targetIndex(currentIndex = 2, delta = 1, size = 5))
        assertEquals("up", 1, LiveChannelZapPolicy.targetIndex(currentIndex = 2, delta = -1, size = 5))
    }

    @Test
    fun `wraps past the last channel`() {
        assertEquals(
            "down from the last returns to the first",
            0,
            LiveChannelZapPolicy.targetIndex(currentIndex = 4, delta = 1, size = 5)
        )
    }

    @Test
    fun `wraps before the first channel`() {
        assertEquals(
            "up from the first lands on the last",
            4,
            LiveChannelZapPolicy.targetIndex(currentIndex = 0, delta = -1, size = 5)
        )
    }

    @Test
    fun `a delta larger than the list still lands inside it`() {
        assertEquals("many steps down", 2, LiveChannelZapPolicy.targetIndex(currentIndex = 0, delta = 12, size = 5))
        assertEquals("many steps up", 3, LiveChannelZapPolicy.targetIndex(currentIndex = 0, delta = -12, size = 5))
    }

    @Test
    fun `a single channel resolves to itself`() {
        assertEquals("down", 0, LiveChannelZapPolicy.targetIndex(currentIndex = 0, delta = 1, size = 1))
        assertEquals("up", 0, LiveChannelZapPolicy.targetIndex(currentIndex = 0, delta = -1, size = 1))
    }

    @Test
    fun `an empty list has nowhere to go`() {
        assertNull("no channels", LiveChannelZapPolicy.targetIndex(currentIndex = 0, delta = 1, size = 0))
        assertNull("negative size is still no channels", LiveChannelZapPolicy.targetIndex(currentIndex = 0, delta = 1, size = -3))
    }

    @Test
    fun `a zero delta is not a zap`() {
        assertNull("no movement asked for", LiveChannelZapPolicy.targetIndex(currentIndex = 2, delta = 0, size = 5))
    }

    @Test
    fun `an unknown current channel enters from the end it is heading for`() {
        assertEquals(
            "down with nothing tuned starts at the top of the list",
            0,
            LiveChannelZapPolicy.targetIndex(currentIndex = -1, delta = 1, size = 5)
        )
        assertEquals(
            "up with nothing tuned starts at the bottom of the list",
            4,
            LiveChannelZapPolicy.targetIndex(currentIndex = -1, delta = -1, size = 5)
        )
        assertEquals(
            "an index past the end is just as unknown",
            0,
            LiveChannelZapPolicy.targetIndex(currentIndex = 99, delta = 1, size = 5)
        )
    }

    @Test
    fun `every result is a valid list index`() {
        val size = 7
        for (start in -2..(size + 1)) {
            for (delta in -20..20) {
                val target = LiveChannelZapPolicy.targetIndex(start, delta, size) ?: continue
                assertTrue("start=$start delta=$delta produced $target", target in 0 until size)
            }
        }
    }

    @Test
    fun `the commit delay swallows a held key without feeling slow`() {
        assertTrue("long enough to coalesce a key repeat", LiveChannelZapPolicy.COMMIT_DELAY_MS >= 300L)
        assertTrue("short enough that one press still feels immediate", LiveChannelZapPolicy.COMMIT_DELAY_MS <= 800L)
    }
}
