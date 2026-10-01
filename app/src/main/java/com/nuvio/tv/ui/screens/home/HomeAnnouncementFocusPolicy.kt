package com.nuvio.tv.ui.screens.home

/**
 * UX80: the announcement card floats over the top-right of Home's rows, so the geometric D-pad
 * search often finds nothing "above" the focused card and the card could not be reached. An Up press
 * that moved focus nowhere lands on the card instead.
 */
internal object HomeAnnouncementFocusPolicy {
    fun focusCardOnUp(cardVisible: Boolean, cardHasFocus: Boolean, movedUp: Boolean): Boolean =
        cardVisible && !cardHasFocus && !movedUp
}
