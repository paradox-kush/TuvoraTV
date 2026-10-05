package com.nuvio.tv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B119 (Onn pass 2026-10-03): with "All" active (stored as the empty set), every region row drew
 * UNCHECKED, so one OK on a region turned "all 23" into "just this one" and Done dropped the other
 * 22 regions' guide data. "All" now draws every row checked, and OK removes that one region.
 */
class EpgRegionSelectionTest {

    private val all = listOf("United Kingdom", "India", "United States")

    @Test
    fun `with All active every region reads as checked`() {
        all.forEach { assertTrue("$it must read checked under All", EpgRegionSelection.isChecked(emptySet(), it)) }
    }

    @Test
    fun `OK on a region while All is active removes only that region`() {
        assertEquals(
            "All minus India, not just India",
            setOf("United Kingdom", "United States"),
            EpgRegionSelection.toggle(emptySet(), all, "India"),
        )
    }

    @Test
    fun `toggling a region back in that completes the set collapses to All`() {
        assertEquals(
            "every region chosen is stored as All (the empty set)",
            emptySet<String>(),
            EpgRegionSelection.toggle(setOf("United Kingdom", "United States"), all, "India"),
        )
    }

    @Test
    fun `a narrowed selection still toggles normally`() {
        assertEquals("add", setOf("India", "United Kingdom"), EpgRegionSelection.toggle(setOf("India"), all, "United Kingdom"))
        assertEquals("remove", setOf("India"), EpgRegionSelection.toggle(setOf("India", "United Kingdom"), all, "United Kingdom"))
        assertFalse("unselected reads unchecked", EpgRegionSelection.isChecked(setOf("India"), "United Kingdom"))
    }

    @Test
    fun `the last checked region cannot be unchecked (an empty set would silently mean All)`() {
        assertEquals("no-op", setOf("India"), EpgRegionSelection.toggle(setOf("India"), all, "India"))
    }

    @Test
    fun `regions that vanished from the catalog do not block the collapse to All`() {
        assertEquals(
            "a stale name is not a region the user can see",
            emptySet<String>(),
            EpgRegionSelection.toggle(setOf("United Kingdom", "United States", "Atlantis"), all, "India"),
        )
    }
}
