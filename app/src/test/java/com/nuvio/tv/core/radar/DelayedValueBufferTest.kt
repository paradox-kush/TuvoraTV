package com.nuvio.tv.core.radar

import org.junit.Assert.assertEquals
import org.junit.Test

class DelayedValueBufferTest {
    @Test
    fun `a snapshot every 5 s for 3 minutes still serves the 60 s delay`() {
        val buf = DelayedValueBuffer<String, Int>()
        var t = 0L
        var i = 0
        while (t <= 180_000L) { buf.record("k", i, t); i++; t += 5_000L }
        // The last record was at 180 s; the newest snapshot at least 60 s old was taken at 120 s (i = 24).
        assertEquals("60 s old value, not a permanent null", 24, buf.valueAt("k", 180_000L, 60_000L))
        assertEquals("30 s old value", 30, buf.valueAt("k", 180_000L, 30_000L))
    }

    @Test
    fun `a burst of snapshots inside one window never drops the anchor`() {
        val buf = DelayedValueBuffer<String, Int>()
        buf.record("k", 0, 0L)
        for (i in 1..20) buf.record("k", i, 50_000L + i * 1_000L)   // 20 changes in 20 s
        assertEquals("anchor from t=0 still serves a 60 s delay", 0, buf.valueAt("k", 70_000L, 60_000L))
    }
}
