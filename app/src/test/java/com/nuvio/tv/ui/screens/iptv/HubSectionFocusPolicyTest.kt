package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B132. JUnit here, so the argument order is (message, expected, actual). */
class HubSectionFocusPolicyTest {

    @Test
    fun `focus arriving on another tab from outside the header keeps the section`() {
        assertNull(
            "Back from a Movies detail landing on the Live tab must not switch to Live",
            HubSectionFocusPolicy.sectionOnFocus(XtreamSection.LIVE, XtreamSection.MOVIES, cameFromHeader = false),
        )
    }

    @Test
    fun `arrowing along the header onto another tab switches to it`() {
        assertEquals(
            "travel inside the header is the TiViMate-style focus select",
            XtreamSection.LIVE,
            HubSectionFocusPolicy.sectionOnFocus(XtreamSection.LIVE, XtreamSection.MOVIES, cameFromHeader = true),
        )
    }

    @Test
    fun `a control that is not a tab never switches`() {
        assertNull(HubSectionFocusPolicy.sectionOnFocus(null, XtreamSection.MOVIES, cameFromHeader = true))
    }

    @Test
    fun `back from Movies lands on Live and the next arrows along the header still work`() {
        val header = HubHeaderFocus()
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
        val header = HubHeaderFocus()
        header.onControlFocused(XtreamSection.MOVIES, XtreamSection.MOVIES)
        header.onHeaderFocusLost()
        assertNull(
            "re-entering onto Series from the rows (or a restore) does not switch",
            header.onControlFocused(XtreamSection.SERIES, XtreamSection.MOVIES),
        )
    }

    @Test
    fun `travel that starts on Search and goes onto a tab switches`() {
        val header = HubHeaderFocus()
        assertNull(header.onControlFocused(null, XtreamSection.MOVIES))
        assertEquals(XtreamSection.SERIES, header.onControlFocused(XtreamSection.SERIES, XtreamSection.MOVIES))
    }
}
