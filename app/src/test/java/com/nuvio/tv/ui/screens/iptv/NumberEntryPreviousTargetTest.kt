package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Device pass 2 (2026-11-15). JUnit: (message, expected, actual). */
class NumberEntryPreviousTargetTest {
    /** D2: "0" said "Previous channel"; it now names where it goes ("Back to 12 · Sky Sports"). */
    @Test
    fun `0 names the row it goes back to`() {
        val rows = listOf("a", "b", "c", "d")
        assertEquals("the 3rd row", 2, NumberEntryPolicy.previousTarget("c", rows))
        assertNull("nothing watched before", NumberEntryPolicy.previousTarget(null, rows))
        assertNull("not in this list", NumberEntryPolicy.previousTarget("z", rows))
    }
}
