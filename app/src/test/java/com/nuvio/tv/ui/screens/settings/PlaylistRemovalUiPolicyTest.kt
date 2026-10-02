package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistRemovalUiPolicyTest {

    // UX74: the confirm said "on all your devices" to a signed-out TV, where nothing syncs.

    @Test
    fun `signed out the confirm uses the phone's conditional sync wording`() {
        assertEquals(
            "a signed-out TV must not promise an all-devices delete",
            PlaylistRemovalUiPolicy.ConfirmWording.IF_YOU_SYNC,
            PlaylistRemovalUiPolicy.confirmWording(signedIn = false),
        )
    }

    @Test
    fun `signed in the confirm says the delete reaches every device`() {
        assertEquals(
            "signed in, the delete syncs",
            PlaylistRemovalUiPolicy.ConfirmWording.ALL_DEVICES,
            PlaylistRemovalUiPolicy.confirmWording(signedIn = true),
        )
    }

    // UX79: after a remove, focus fell out of the list to the left menu.

    @Test
    fun `focus moves to the row that slid into the removed one's place`() {
        assertEquals("middle row removed", 1, PlaylistRemovalUiPolicy.focusIndexAfterRemoval(removedIndex = 1, remainingCount = 3))
        assertEquals("first row removed", 0, PlaylistRemovalUiPolicy.focusIndexAfterRemoval(removedIndex = 0, remainingCount = 2))
    }

    @Test
    fun `removing the last row focuses the new last row`() {
        assertEquals("last row removed", 1, PlaylistRemovalUiPolicy.focusIndexAfterRemoval(removedIndex = 2, remainingCount = 2))
    }

    @Test
    fun `removing the only playlist focuses the Add row`() {
        assertNull("no rows left", PlaylistRemovalUiPolicy.focusIndexAfterRemoval(removedIndex = 0, remainingCount = 0))
    }
}
