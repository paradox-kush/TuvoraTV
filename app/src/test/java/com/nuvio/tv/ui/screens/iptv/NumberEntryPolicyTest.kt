package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Redesign change 7 + B1 + B2. JUnit: (message, expected, actual). */
class NumberEntryPolicyTest {

    @Test
    fun `digits build up to four and a fifth starts over`() {
        var b = ""
        listOf(1, 2, 3, 4).forEach { b = NumberEntryPolicy.append(b, it) }
        assertEquals("four digits", "1234", b)
        assertEquals("fifth starts over", "5", NumberEntryPolicy.append(b, 5))
    }

    @Test
    fun `an exact number wins, else the first number starting with what was typed`() {
        assertEquals("exact 12 of 200", 11, NumberEntryPolicy.match("12", 200))
        assertEquals("exact 1 of 200 (the live match while typing 12)", 0, NumberEntryPolicy.match("1", 200))
        assertEquals("15 of 9 rows: none starts with 15", null, NumberEntryPolicy.match("15", 9))
        assertEquals("3 of 40", 2, NumberEntryPolicy.match("3", 40))
        assertEquals("45 of 40: prefix fallback finds nothing", null, NumberEntryPolicy.match("45", 40))
        assertEquals("prefix: 7 typed as 07", 6, NumberEntryPolicy.match("07", 40))
    }

    @Test
    fun `no match says so and tunes nothing`() {
        assertNull("99 of 20", NumberEntryPolicy.match("99", 20))
        assertNull("empty list", NumberEntryPolicy.match("1", 0))
        assertNull("0 is never a channel", NumberEntryPolicy.match("0", 20))
    }

    @Test
    fun `zero alone is the previous channel`() {
        assertEquals("0", true, NumberEntryPolicy.isPreviousChannel("0"))
        assertEquals("10", false, NumberEntryPolicy.isPreviousChannel("10"))
    }

    @Test
    fun `CH+ and CH- page one screen of rows and never wrap`() {
        assertEquals("down", 15, NumberEntryPolicy.pageTarget(current = 7, visibleRows = 8, count = 100, down = true))
        assertEquals("up", 0, NumberEntryPolicy.pageTarget(current = 5, visibleRows = 8, count = 100, down = false))
        assertEquals("bottom clamps", 99, NumberEntryPolicy.pageTarget(current = 95, visibleRows = 8, count = 100, down = true))
        assertEquals("zero visible rows still moves one", 6, NumberEntryPolicy.pageTarget(5, 0, 100, true))
    }
}
