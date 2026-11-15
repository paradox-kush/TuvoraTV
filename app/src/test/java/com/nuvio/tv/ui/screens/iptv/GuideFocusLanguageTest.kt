package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Redesign change 2: focused, selected and playing are three different marks. JUnit: (message, expected, actual). */
class GuideFocusLanguageTest {

    @Test
    fun `only the focused element wears the ring`() {
        assertEquals("focused", true, GuideFocusLanguage.look(focused = true, selected = false).ring)
        assertEquals("selected only", false, GuideFocusLanguage.look(focused = false, selected = true).ring)
        assertEquals("playing only", false, GuideFocusLanguage.look(focused = false, selected = false, playing = true).ring)
    }

    @Test
    fun `selected keeps its soft fill and bold text with or without focus`() {
        val shown = GuideFocusLanguage.look(focused = false, selected = true)
        assertEquals("fill", true, shown.softFill)
        assertEquals("bold", true, shown.boldText)
        val both = GuideFocusLanguage.look(focused = true, selected = true)
        assertEquals("focused and selected: ring", true, both.ring)
        assertEquals("focused and selected: still filled, so the two stay readable", true, both.softFill)
        assertEquals("a focused row that is not selected has no fill", false, GuideFocusLanguage.look(true, false).softFill)
    }

    @Test
    fun `playing is its own mark and survives focus and selection`() {
        listOf(true to true, true to false, false to true, false to false).forEach { (focused, selected) ->
            assertEquals("focused=$focused selected=$selected", true, GuideFocusLanguage.look(focused, selected, playing = true).playingMarks)
        }
        assertEquals("not playing", false, GuideFocusLanguage.look(true, true, playing = false).playingMarks)
    }

    @Test
    fun `every element at rest keeps a hairline so the grid survives AMOLED black`() {
        assertEquals("rest", true, GuideFocusLanguage.look(focused = false, selected = false).restOutline)
        assertEquals("the ring replaces the hairline", false, GuideFocusLanguage.look(focused = true, selected = false).restOutline)
    }
}
