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

    /**
     * Device pass 1, finding 5: the Stalker mock names every group "ITV category N". With one letter for the whole
     * groups part there is no next letter, so a held DOWN runs to the last group: on screen that looks like plain
     * travel, which is why the device pass could not tell the jump fired.
     */
    @Test
    fun `one letter for every group runs a held key to the far end`() {
        val mock = listOf("Search", "All channels") + (1..12).map { "ITV category $it" }
        val inGroups = listOf(false, false) + List(12) { true }
        assertEquals("DOWN runs to the last group", 13, GuideLetterJumpPolicy.target(mock, inGroups, current = 2, down = true))
        assertEquals("UP runs to the first group", 2, GuideLetterJumpPolicy.target(mock, inGroups, current = 9, down = false))
    }
}
