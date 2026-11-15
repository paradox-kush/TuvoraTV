package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B132 + A2. JUnit here, so the argument order is (message, expected, actual). */
class HubSectionFocusPolicyTest {

    // A2 (redesign 2026-11-15): tabs change on OK only. The B132 travel rules below still hold for the owner's
    // other choice, so they pin switchOnFocus = true explicitly.
    @Test
    fun `by default arrowing along the header never switches the section`() {
        assertEquals("A2 default is OK-only", false, HubSectionFocusPolicy.SWITCH_ON_FOCUS)
        assertNull(
            "walking RIGHT past Movies toward Search must not reload Movies",
            HubSectionFocusPolicy.sectionOnFocus(XtreamSection.MOVIES, XtreamSection.LIVE, cameFromHeader = true),
        )
        val header = HubHeaderFocus()
        assertNull(header.onControlFocused(XtreamSection.LIVE, XtreamSection.LIVE))
        assertNull("travel onto Movies is not a choice", header.onControlFocused(XtreamSection.MOVIES, XtreamSection.LIVE))
        assertNull("travel onto Series is not a choice", header.onControlFocused(XtreamSection.SERIES, XtreamSection.LIVE))
    }

    @Test
    fun `focus arriving on another tab from outside the header keeps the section`() {
        assertNull(
            "Back from a Movies detail landing on the Live tab must not switch to Live",
            HubSectionFocusPolicy.sectionOnFocus(XtreamSection.LIVE, XtreamSection.MOVIES, cameFromHeader = false, switchOnFocus = true),
        )
    }

    @Test
    fun `arrowing along the header onto another tab switches to it`() {
        assertEquals(
            "travel inside the header is the TiViMate-style focus select",
            XtreamSection.LIVE,
            HubSectionFocusPolicy.sectionOnFocus(XtreamSection.LIVE, XtreamSection.MOVIES, cameFromHeader = true, switchOnFocus = true),
        )
    }

    @Test
    fun `a control that is not a tab never switches`() {
        assertNull(HubSectionFocusPolicy.sectionOnFocus(null, XtreamSection.MOVIES, cameFromHeader = true, switchOnFocus = true))
    }

    @Test
    fun `back from Movies lands on Live and the next arrows along the header still work`() {
        val header = HubHeaderFocus(switchOnFocus = true)
        assertNull("landing on Live after Back", header.onControlFocused(XtreamSection.LIVE, XtreamSection.MOVIES))
        assertNull("RIGHT onto the selected Movies tab", header.onControlFocused(XtreamSection.MOVIES, XtreamSection.MOVIES))
        assertEquals(
            "LEFT onto Live is now deliberate travel",
            XtreamSection.LIVE,
            header.onControlFocused(XtreamSection.LIVE, XtreamSection.MOVIES),
        )
    }

    @Test
    fun `leaving the header makes the next landing an arrival again`() {
        val header = HubHeaderFocus(switchOnFocus = true)
        header.onControlFocused(XtreamSection.MOVIES, XtreamSection.MOVIES)
        header.onHeaderFocusLost()
        assertNull(
            "re-entering onto Series from the rows (or a restore) does not switch",
            header.onControlFocused(XtreamSection.SERIES, XtreamSection.MOVIES),
        )
    }

    /**
     * Integration regression (2026-11-12): an arrow between tabs reports the row as unfocused BEFORE
     * the next tab gains focus. Reading that as "left the header" made every arrow an arrival.
     */
    @Test
    fun `the row passing through no focus between two tabs is still travel`() {
        val header = HubHeaderFocus(switchOnFocus = true)
        val posted = mutableListOf<Runnable>()
        header.onHeaderFocusChanged(true) { posted += it }
        header.onControlFocused(XtreamSection.MOVIES, XtreamSection.MOVIES)
        header.onHeaderFocusChanged(false) { posted += it }
        assertEquals(
            "RIGHT onto Series inside one focus transaction",
            XtreamSection.SERIES,
            header.onControlFocused(XtreamSection.SERIES, XtreamSection.MOVIES),
        )
        header.onHeaderFocusChanged(true) { posted += it }
        posted.forEach(Runnable::run)
        posted.clear()
        header.onHeaderFocusChanged(false) { posted += it }
        posted.forEach(Runnable::run)
        assertNull(
            "a loss still unfocused on the next turn is a real exit",
            header.onControlFocused(XtreamSection.LIVE, XtreamSection.MOVIES),
        )
    }

    @Test
    fun `travel that starts on Search and goes onto a tab switches`() {
        val header = HubHeaderFocus(switchOnFocus = true)
        assertNull(header.onControlFocused(null, XtreamSection.MOVIES))
        assertEquals(XtreamSection.SERIES, header.onControlFocused(XtreamSection.SERIES, XtreamSection.MOVIES))
    }
}
