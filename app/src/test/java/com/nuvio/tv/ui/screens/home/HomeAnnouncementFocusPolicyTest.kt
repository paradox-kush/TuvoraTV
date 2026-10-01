package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX80: the Home announcement card floats over the top-right of the rows, so geometric D-pad search
 * often found nothing "above" the focused card (or a layout consumed Up) and the card was unreachable.
 * Up that has nowhere else to go now lands on the card.
 */
class HomeAnnouncementFocusPolicyTest {

    @Test
    fun `up with nowhere to go reaches a visible card`() {
        assertTrue(HomeAnnouncementFocusPolicy.focusCardOnUp(cardVisible = true, cardHasFocus = false, movedUp = false))
    }

    @Test
    fun `up that moved focus normally is left alone`() {
        assertFalse("normal move", HomeAnnouncementFocusPolicy.focusCardOnUp(cardVisible = true, cardHasFocus = false, movedUp = true))
    }

    @Test
    fun `no card or already on the card does nothing`() {
        assertFalse("no card", HomeAnnouncementFocusPolicy.focusCardOnUp(cardVisible = false, cardHasFocus = false, movedUp = false))
        assertFalse("on card", HomeAnnouncementFocusPolicy.focusCardOnUp(cardVisible = true, cardHasFocus = true, movedUp = false))
    }

    // On device (Modern layout, expanded first card) Up was swallowed by the rows list before the
    // fallback above ever ran, so the card stayed unreachable.
    @Test
    fun `up from an expanded first-row card reaches a visible card`() {
        assertEquals(
            "card showing",
            HomeAnnouncementFocusPolicy.ExpandedTopRowUp.FOCUS_CARD,
            HomeAnnouncementFocusPolicy.upFromExpandedTopRow(cardVisible = true),
        )
    }

    @Test
    fun `up from an expanded first-row card stays put without a card`() {
        assertEquals(
            "no card",
            HomeAnnouncementFocusPolicy.ExpandedTopRowUp.STAY,
            HomeAnnouncementFocusPolicy.upFromExpandedTopRow(cardVisible = false),
        )
    }
}
