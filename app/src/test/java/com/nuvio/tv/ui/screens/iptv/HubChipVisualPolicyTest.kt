package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** JUnit here, so the argument order is (message, expected, actual). */
class HubChipVisualPolicyTest {

    @Test
    fun `a resting tab has no container`() {
        assertEquals(
            "an unselected unfocused tab draws nothing",
            HubChipSurface.None,
            hubChipSurface(selected = false, focused = false)
        )
    }

    @Test
    fun `the active section is a soft fill while focus is elsewhere`() {
        assertEquals(
            "selected but not focused",
            HubChipSurface.SoftFill,
            hubChipSurface(selected = true, focused = false)
        )
    }

    @Test
    fun `focus outranks selection`() {
        assertEquals(
            "the focused tab wins even when it is also the active section",
            HubChipSurface.AccentFill,
            hubChipSurface(selected = true, focused = true)
        )
        assertEquals(
            "focus on a tab that is not the active section still reads as focused",
            HubChipSurface.AccentFill,
            hubChipSurface(selected = false, focused = true)
        )
    }

    @Test
    fun `selected and focused never share a treatment`() {
        // The regression this guards: both states used to be Primary, differing only in alpha.
        assertNotEquals(
            "a D-pad user must be able to tell the active section from the focused one",
            hubChipSurface(selected = true, focused = false),
            hubChipSurface(selected = false, focused = true)
        )
    }

    @Test
    fun `every state is distinguishable from the others`() {
        val surfaces = listOf(
            hubChipSurface(selected = false, focused = false),
            hubChipSurface(selected = true, focused = false),
            hubChipSurface(selected = false, focused = true),
        )

        assertEquals("three states, three distinct surfaces", 3, surfaces.toSet().size)
    }
}
