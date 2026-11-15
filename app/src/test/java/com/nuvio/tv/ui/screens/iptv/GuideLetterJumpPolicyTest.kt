package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Redesign change 8 (02-guide-normal "Groups that scale"). JUnit: (message, expected, actual). */
class GuideLetterJumpPolicyTest {
    private val names = listOf("Search", "Favorites", "All channels", "Arts", "Asia", "BBC", "Bein", "Cinema", "  4K", "Zed")
    private val groups = listOf(false, false, false, true, true, true, true, true, true, true)

    @Test
    fun `a held DOWN jumps to the next letter`() {
        assertEquals("A to B", 5, GuideLetterJumpPolicy.target(names, groups, current = 3, down = true))
        assertEquals("B to C", 7, GuideLetterJumpPolicy.target(names, groups, current = 5, down = true))
    }

    @Test
    fun `a held UP jumps to the previous letter and stops at the first group`() {
        assertEquals("C to the first B", 5, GuideLetterJumpPolicy.target(names, groups, current = 7, down = false))
        assertEquals("A stays in the groups part (never jumps into the pinned rows)", 3, GuideLetterJumpPolicy.target(names, groups, current = 4, down = false))
    }

    @Test
    fun `the last letter runs to the end and a lone row stays`() {
        assertEquals("Z is last", 9, GuideLetterJumpPolicy.target(names, groups, current = 8, down = true))
        assertNull("nothing below the last row", GuideLetterJumpPolicy.target(names, groups, current = 9, down = true))
    }

    @Test
    fun `pinned rows and Search move row by row`() {
        assertNull("Favorites", GuideLetterJumpPolicy.target(names, groups, current = 1, down = true))
    }

    @Test
    fun `letters ignore leading spaces and symbols and digits count`() {
        assertEquals("4K", '4', GuideLetterJumpPolicy.letterOf("  4K"))
        assertEquals("lower case", 'B', GuideLetterJumpPolicy.letterOf("bein"))
        assertEquals("no letter", '#', GuideLetterJumpPolicy.letterOf("★★"))
    }
}
