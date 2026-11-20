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

    // Restyle "B" (2026-11-20): the guide LIST (group column + channel rows + programme lane) answers focus with a
    // solid fill instead of the ring. The side panel, overlay and hub keep look() above.

    private fun luminance(argb: Long): Double {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `list focus is a solid fill with dark content and no ring`() {
        val look = GuideFocusLanguage.listLook(focused = true, selected = false)
        assertEquals("solid fill", true, look.solidFill)
        assertEquals("content flips dark on the fill", true, look.darkContent)
        assertEquals("a focused row that is not selected has no quiet fill", false, look.quietFill)
        val rest = GuideFocusLanguage.listLook(focused = false, selected = false)
        assertEquals("rest: no solid fill", false, rest.solidFill)
        assertEquals("rest: content stays light", false, rest.darkContent)
    }

    @Test
    fun `list selected is a quiet fill and bar that yields to the solid focus fill`() {
        val shown = GuideFocusLanguage.listLook(focused = false, selected = true)
        assertEquals("quiet fill", true, shown.quietFill)
        assertEquals("bar", true, shown.selectedBar)
        val both = GuideFocusLanguage.listLook(focused = true, selected = true)
        assertEquals("focus wins the fill", true, both.solidFill)
        assertEquals("no quiet fill under the solid one", false, both.quietFill)
        assertEquals("the bar still marks the shown group", true, both.selectedBar)
    }

    @Test
    fun `list playing edge survives focus and selection`() {
        listOf(true to true, true to false, false to true, false to false).forEach { (f, s) ->
            assertEquals("focused=$f selected=$s", true, GuideFocusLanguage.listLook(f, s, playing = true).playingEdge)
        }
        assertEquals("not playing", false, GuideFocusLanguage.listLook(true, true, playing = false).playingEdge)
    }

    @Test
    fun `solid focus is unmistakable on a TV and on AMOLED black`() {
        val fill = GuideFocusLanguage.LIST_FOCUS_FILL_ARGB
        val content = GuideFocusLanguage.LIST_FOCUS_CONTENT_ARGB
        assertEquals("text on the fill >= 7:1", true, contrast(fill, content) >= 7.0)
        assertEquals("fill against pure black (AMOLED) >= 15:1", true, contrast(fill, 0xFF000000L) >= 15.0)
        assertEquals("fill against the panel grey >= 10:1", true, contrast(fill, 0xFF161616L) >= 10.0)
    }

    @Test
    fun `the quiet selected fill is quieter than the focus fill`() {
        assertEquals("alpha stays low", true, GuideFocusLanguage.QUIET_FILL_ALPHA <= 0.12f)
    }

    @Test
    fun `list rows keep hairline dividers`() {
        assertEquals("focused", true, GuideFocusLanguage.listLook(true, false).hairline)
        assertEquals("rest", true, GuideFocusLanguage.listLook(false, false).hairline)
    }
}
