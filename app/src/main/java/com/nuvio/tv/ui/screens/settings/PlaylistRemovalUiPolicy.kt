package com.nuvio.tv.ui.screens.settings

/** Decisions of the TV "Remove playlist" flow, kept out of the Composable so they test without UI. */
internal object PlaylistRemovalUiPolicy {

    enum class ConfirmWording {
        /** Signed in: the delete syncs, so it definitely reaches every device. */
        ALL_DEVICES,

        /** Signed out: nothing syncs now — the phone's conditional wording ("If you sync, …"). */
        IF_YOU_SYNC,
    }

    /** UX74: only a signed-in TV may promise the delete reaches all devices. */
    fun confirmWording(signedIn: Boolean): ConfirmWording =
        if (signedIn) ConfirmWording.ALL_DEVICES else ConfirmWording.IF_YOU_SYNC

    /**
     * UX79: the playlist row to focus after removing the row at [removedIndex], given
     * [remainingCount] rows left — the row that slid into its place, else the new last row; null
     * when none are left (focus the "Add" row instead). Keeps focus in the list, not the side menu.
     */
    fun focusIndexAfterRemoval(removedIndex: Int, remainingCount: Int): Int? {
        if (remainingCount <= 0) return null
        return removedIndex.coerceIn(0, remainingCount - 1)
    }
}
