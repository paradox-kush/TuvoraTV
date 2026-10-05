package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F08: on TV live, the remote's "last channel" jump returns to the channel watched before — the
 * zap-back every cable box has. It follows SETTLED channels (what actually played), so walking
 * through ten channels with UP/DOWN to reach one leaves the one you started on as "previous",
 * not the one you skipped past a second ago.
 */
class LiveZapBackTest {

    @Test
    fun `nothing to go back to before a second channel has played`() {
        val history = LiveZapBack().onSettled("bbc")
        assertNull("one channel only", history.target(lineup = setOf("bbc", "itv")))
    }

    @Test
    fun `back returns to the channel watched before`() {
        val history = LiveZapBack().onSettled("bbc").onSettled("itv")
        assertEquals("previous", "bbc", history.target(lineup = setOf("bbc", "itv")))
    }

    @Test
    fun `going back and forth toggles between the two`() {
        var history = LiveZapBack().onSettled("bbc").onSettled("itv")
        history = history.onSettled(history.target(setOf("bbc", "itv"))!!)
        assertEquals("toggles back", "itv", history.target(setOf("bbc", "itv")))
    }

    @Test
    fun `a re-settle on the same channel keeps the previous one`() {
        // A retry or a recovery re-tune reports the same channel again; it is not a zap.
        val history = LiveZapBack().onSettled("bbc").onSettled("itv").onSettled("itv")
        assertEquals("previous survives", "bbc", history.target(setOf("bbc", "itv")))
    }

    @Test
    fun `the previous channel must still be in the lineup on screen`() {
        // The viewer changed category since: tuning outside the published lineup is refused upstream,
        // so the jump is not offered rather than failing silently.
        val history = LiveZapBack().onSettled("bbc").onSettled("itv")
        assertNull("not in lineup", history.target(lineup = setOf("itv", "ch4")))
    }
}
