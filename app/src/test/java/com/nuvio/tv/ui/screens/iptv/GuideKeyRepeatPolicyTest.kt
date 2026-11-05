package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.ui.screens.iptv.GuideKeyRepeatPolicy.Decision
import com.nuvio.tv.ui.screens.iptv.GuideKeyRepeatPolicy.Key
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideKeyRepeatPolicyTest {
    private fun policy() = GuideKeyRepeatPolicy()

    @Test
    fun `first press is always accepted`() {
        assertEquals(Decision.Accept, policy().decide(Key.Vertical, repeatCount = 0, eventTimeMs = 1_000, nowMs = 1_000))
    }

    @Test
    fun `B56 a burst of vertical repeats delivered at once yields at most one move`() {
        val p = policy()
        p.decide(Key.Vertical, 0, 1_000, 1_000)
        var accepted = 0
        // 40 repeats stamped 50 ms apart, but the main thread delivers them within 5 ms.
        for (i in 1..40) {
            val d = p.decide(Key.Vertical, repeatCount = i, eventTimeMs = 1_000L + 50L * i, nowMs = 3_100L + i / 8)
            if (d == Decision.Accept) accepted++
        }
        assertEquals("backlog bounded", 1, accepted)
    }

    @Test
    fun `repeats inside the gate are dropped and one after the gate is accepted`() {
        val p = policy()
        p.decide(Key.Vertical, 0, 0, 0)
        assertEquals(Decision.Drop, p.decide(Key.Vertical, 1, 50, 50))
        assertEquals(Decision.Accept, p.decide(Key.Vertical, 2, GuideKeyRepeatPolicy.VERTICAL_GATE_MS, GuideKeyRepeatPolicy.VERTICAL_GATE_MS))
    }

    @Test
    fun `a repeat that sat in the queue too long is dropped even outside the gate`() {
        val p = policy()
        assertEquals(
            Decision.Drop,
            p.decide(Key.Vertical, repeatCount = 5, eventTimeMs = 100, nowMs = 100 + GuideKeyRepeatPolicy.MAX_STALE_MS + 1),
        )
    }

    @Test
    fun `horizontal repeats pass through untouched so timeline travel keeps working`() {
        val p = policy()
        repeat(20) { i ->
            assertEquals(Decision.Pass, p.decide(Key.Horizontal, i, 10L * i, 10L * i))
        }
    }
}
