package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UX389: the IPTV hub opened on an empty tab ("Nothing here"): the default section was MOVIES and a remembered
 * tab was restored without checking it had anything. Entry now lands on the first tab with content, Live first.
 */
class HubEntrySectionPolicyTest {
    private val all = setOf(XtreamSection.LIVE, XtreamSection.MOVIES, XtreamSection.SERIES)

    @Test
    fun `nothing remembered opens on Live TV`() {
        assertEquals("Live TV is the first tab", XtreamSection.LIVE, HubEntrySectionPolicy.initial(null, all, emptySet()))
    }

    @Test
    fun `a remembered tab with content is kept`() {
        assertEquals("sticky tab", XtreamSection.MOVIES, HubEntrySectionPolicy.initial(XtreamSection.MOVIES, all, emptySet()))
    }

    @Test
    fun `a remembered tab known to be empty falls to the first tab with content`() {
        assertEquals(
            "a playlist with no series must not reopen on Series",
            XtreamSection.LIVE,
            HubEntrySectionPolicy.initial(XtreamSection.SERIES, all, setOf(XtreamSection.SERIES)),
        )
    }

    @Test
    fun `a disabled remembered tab is skipped`() {
        assertEquals(
            "Live is disabled, Movies is the first with content",
            XtreamSection.MOVIES,
            HubEntrySectionPolicy.initial(XtreamSection.LIVE, setOf(XtreamSection.MOVIES, XtreamSection.SERIES), emptySet()),
        )
    }

    @Test
    fun `when every tab is empty the first enabled tab is used`() {
        assertEquals("nothing better exists", XtreamSection.LIVE, HubEntrySectionPolicy.initial(null, all, all))
    }

    @Test
    fun `an entry tab that proves empty advances to the next untried tab with Live first`() {
        assertEquals(XtreamSection.LIVE, HubEntrySectionPolicy.advanceFrom(XtreamSection.MOVIES, all, setOf(XtreamSection.MOVIES)))
        assertEquals(
            XtreamSection.SERIES,
            HubEntrySectionPolicy.advanceFrom(XtreamSection.MOVIES, setOf(XtreamSection.MOVIES, XtreamSection.SERIES), setOf(XtreamSection.MOVIES)),
        )
    }

    @Test
    fun `advancing stops when every enabled tab was tried`() {
        assertNull("no loop", HubEntrySectionPolicy.advanceFrom(XtreamSection.SERIES, all, all))
    }

    @Test
    fun `a fresh hub state starts on Live TV, not Movies`() {
        assertEquals("the default section was MOVIES (UX389)", XtreamSection.LIVE, XtreamHubUiState().section)
    }
}
