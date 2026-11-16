package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sim pass 2026-11-16 (UX): a number jump to a channel that does not exist, or "0" / LAST with no channel watched
 * before, did nothing at all. Both now end in a short hint ("No channel 999" / "No previous channel"), in the guide and
 * in full screen. JUnit: (message, expected, actual).
 */
class NumberEntryCommitTest {

    @Test
    fun `a number that exists jumps to its row`() {
        assertEquals("12 of 40", NumberEntryPolicy.Commit.Jump(11), NumberEntryPolicy.commit("12", 40, hasPrevious = false))
    }

    @Test
    fun `a number that does not exist says No channel N`() {
        assertEquals("999 of 3", NumberEntryPolicy.Commit.NoChannel("999"), NumberEntryPolicy.commit("999", 3, hasPrevious = true))
    }

    @Test
    fun `0 with a previous channel goes back to it`() {
        assertEquals("0 with history", NumberEntryPolicy.Commit.Previous, NumberEntryPolicy.commit("0", 3, hasPrevious = true))
    }

    @Test
    fun `0 with no previous channel says so`() {
        assertEquals("0 with nothing watched before", NumberEntryPolicy.Commit.NoPrevious, NumberEntryPolicy.commit("0", 3, hasPrevious = false))
    }
}
