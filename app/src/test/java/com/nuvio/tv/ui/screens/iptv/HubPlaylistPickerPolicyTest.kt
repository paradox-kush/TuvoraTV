package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Sim pass 2026-11-16 (UX): the playlist picker opens on the "Current" row. JUnit: (message, expected, actual). */
class HubPlaylistPickerPolicyTest {

    @Test
    fun `the picker opens on the current playlist`() {
        assertEquals("current is the second playlist", 1, HubPlaylistPickerPolicy.focusIndex(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `with no current playlist it opens on the first row`() {
        assertNull("nothing selected", HubPlaylistPickerPolicy.focusIndex(listOf("a", "b"), null))
        assertNull("selected playlist gone", HubPlaylistPickerPolicy.focusIndex(listOf("a", "b"), "z"))
    }
}
