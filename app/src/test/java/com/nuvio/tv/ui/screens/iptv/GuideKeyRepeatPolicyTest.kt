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

    /**
     * Device pass 1, finding 5 (report only): the held-key letter jump (change 8) reads a KeyDown with repeatCount > 0
     * AFTER this gate (the gate sits on the group LazyColumn, the jump on the row). `adb input keyevent --longpress`
     * sends ONE repeat at the long-press timeout; a remote sends a stream every ~50 ms. Both shapes must let a repeat
     * through, or the jump could never fire.
     */
    @Test
    fun `a long-press repeat and a remote's repeat stream both reach the letter jump`() {
        val adb = policy()
        adb.decide(Key.Vertical, 0, 0, 0)
        assertEquals("adb --longpress: one repeat at the 500 ms timeout", Decision.Accept, adb.decide(Key.Vertical, 1, 500, 500))

        val remote = policy()
        remote.decide(Key.Vertical, 0, 0, 0)
        // Android's first repeat comes at the key-repeat timeout (~400 ms), then every ~50 ms.
        val accepted = (1..10).count { i ->
            val t = 400L + 50L * (i - 1)
            remote.decide(Key.Vertical, i, t, t) == Decision.Accept
        }
        assertEquals("a held remote key gets a repeat through every gate period", 4, accepted)
    }
}
