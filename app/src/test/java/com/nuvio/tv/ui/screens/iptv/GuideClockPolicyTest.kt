package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideClockPolicyTest {
    @Test
    fun `mid-minute waits only for the remainder to the next boundary`() {
        assertEquals("12 s into a minute", 48_000L, GuideClockPolicy.delayToNextTickMs(120_000L + 12_000L))
    }

    @Test
    fun `exactly on a boundary waits a full minute`() {
        assertEquals("on the boundary", 60_000L, GuideClockPolicy.delayToNextTickMs(180_000L))
    }

    @Test
    fun `one millisecond past a boundary waits just under a minute`() {
        assertEquals("1 ms past", 59_999L, GuideClockPolicy.delayToNextTickMs(180_001L))
    }

    @Test
    fun `delay is always between 1 ms and one minute`() {
        for (now in listOf(0L, 1L, 59_999L, 60_000L, 1_700_000_000_123L, -1L)) {
            val d = GuideClockPolicy.delayToNextTickMs(now)
            assertTrue("delay $d for $now", d in 1L..GuideClockPolicy.TICK_MS)
            assertEquals("lands on a boundary for $now", 0L, Math.floorMod(now + d, GuideClockPolicy.TICK_MS))
        }
    }
}
