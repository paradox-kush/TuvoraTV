package com.nuvio.tv.ui.screens.home

/**
 * UX80: the announcement card floats over the top-right of Home's rows, so the geometric D-pad
 * search often finds nothing "above" the focused card and the card could not be reached. An Up press
 * that moved focus nowhere lands on the card instead.
 */
internal object HomeAnnouncementFocusPolicy {
    fun focusCardOnUp(cardVisible: Boolean, cardHasFocus: Boolean, movedUp: Boolean): Boolean =
        cardVisible && !cardHasFocus && !movedUp

    /** What an Up press on Modern's first row does while its focused card is expanded. */
    enum class ExpandedTopRowUp {
        /** Consume it and stay put (nothing above the rows to go to). */
        STAY,

        /** Consume it and focus the announcement card directly. */
        FOCUS_CARD,
    }

    /**
     * Modern Home consumes Up on its first row while a card there is expanded (upstream #2328: a
     * geometric focus search from an expanded card with a playing trailer crashed), so that press
     * never reached HomeScreen's fallback in [focusCardOnUp]. With a card showing, the press now
     * focuses it directly — a FocusRequester, not a search, so the #2328 guard still holds. Modern's
     * hero takes no focus, so the order is: first row → card.
     */
    fun upFromExpandedTopRow(cardVisible: Boolean): ExpandedTopRowUp =
        if (cardVisible) ExpandedTopRowUp.FOCUS_CARD else ExpandedTopRowUp.STAY
}
