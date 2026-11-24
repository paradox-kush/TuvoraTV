package com.nuvio.tv.core.radar

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreDueLoopTest {
    @Test
    fun `the chain re-arms until nothing is pending`() = runBlocking {
        val buf = DelayedValueBuffer<String, Int>()
        buf.record("k", 1, 0L); buf.record("k", 2, 10_000L); buf.record("k", 3, 20_000L)
        var now = 5_000L
        var fired = 0
        ScoreDueLoop.run(
            nowMs = { now },
            nextDueAtMs = { buf.nextChangeAtMs(it, 30_000L) },
            wait = { ms -> now += ms },
            onDue = { fired++ },
        )
        assertEquals("three snapshots fall due one after another (30 s, 40 s, 50 s)", 3, fired)
    }

    @Test
    fun `nothing pending means no wait and no signal`() = runBlocking {
        var fired = 0
        ScoreDueLoop.run({ 0L }, { null }, { error("must not wait") }, { fired++ })
        assertEquals("idle", 0, fired)
    }
}
