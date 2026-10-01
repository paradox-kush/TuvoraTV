package com.nuvio.tv.ui.screens.settings

/**
 * UX34: where focus starts in the Hidden channels & groups dialog. The first hidden item when there
 * is one (the reason the dialog was opened), Done when there is nothing to unhide, and nothing while
 * the list is still loading so focus is not parked on Done just before the rows appear.
 */
internal object HiddenItemsDialogFocusPolicy {
    enum class Target { NONE, FIRST_ROW, DONE }

    fun initialFocus(itemCount: Int?): Target = when {
        itemCount == null -> Target.NONE
        itemCount > 0 -> Target.FIRST_ROW
        else -> Target.DONE
    }
}
