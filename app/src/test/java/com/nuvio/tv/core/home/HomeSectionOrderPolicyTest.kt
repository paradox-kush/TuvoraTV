package com.nuvio.tv.core.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSectionOrderPolicyTest {
    private fun cfg(customized: Boolean, vararg entries: Pair<String, Boolean>) =
        HomeSectionsConfig(customized, entries.map { HomeSectionEntry(it.first, it.second) })

    private fun ids(list: List<HomeSectionEntry>) = list.map { it.id }

    @Test
    fun noConfigGivesTodaysOrderWithRecentChannelsUnderContinueWatching() {
        assertEquals(
            "default order",
            listOf("continue_watching", "live_recents", "upcoming", "catalogs"),
            HomeSectionOrderPolicy.renderOrder(null)
        )
    }

    @Test
    fun notCustomisedIgnoresTheStoredList() {
        val stored = cfg(false, "catalogs" to true, "hero" to true, "continue_watching" to false)
        assertEquals(
            "a list with customized=false must not change the default",
            listOf("continue_watching", "live_recents", "upcoming", "catalogs"),
            HomeSectionOrderPolicy.renderOrder(stored)
        )
    }

    @Test
    fun customisedOrderIsFollowed() {
        val stored = cfg(
            true,
            "hero" to true, "catalogs" to true, "live_recents" to true, "continue_watching" to true, "upcoming" to true
        )
        assertEquals(
            listOf("catalogs", "live_recents", "continue_watching", "upcoming"),
            HomeSectionOrderPolicy.renderOrder(stored)
        )
    }

    @Test
    fun hiddenSectionIsNotRenderedButStaysListed() {
        val stored = cfg(
            true,
            "hero" to true, "continue_watching" to true, "live_recents" to false, "upcoming" to true, "catalogs" to true
        )
        assertEquals(listOf("continue_watching", "upcoming", "catalogs"), HomeSectionOrderPolicy.renderOrder(stored))
        val rows = HomeSectionOrderPolicy.movableRows(stored)
        assertEquals(listOf("continue_watching", "live_recents", "upcoming", "catalogs"), ids(rows))
        assertFalse("live_recents stays in the reorder list, marked off", rows[1].enabled)
    }

    @Test
    fun unknownIdIsSkippedWhenDrawingAndKeptInPlaceOnWrite() {
        val stored = cfg(
            true,
            "hero" to true, "continue_watching" to true, "future_row" to true, "live_recents" to true,
            "upcoming" to true, "catalogs" to true
        )
        assertEquals(
            "unknown id is not drawn",
            listOf("continue_watching", "live_recents", "upcoming", "catalogs"),
            HomeSectionOrderPolicy.renderOrder(stored)
        )
        val moved = HomeSectionOrderPolicy.move(stored, "continue_watching", +1)!!
        assertEquals(
            "unknown id keeps its slot while its neighbours swap",
            listOf("hero", "live_recents", "future_row", "continue_watching", "upcoming", "catalogs"),
            ids(moved.sections)
        )
        assertTrue(moved.customized)
    }

    @Test
    fun idsTvCannotDrawArePreservedAndSkipped() {
        val stored = cfg(
            true,
            "hero" to true, "new_episodes" to true, "continue_watching" to true, "sports" to false,
            "live_recents" to true, "upcoming" to true, "catalogs" to true
        )
        assertEquals(
            listOf("continue_watching", "live_recents", "upcoming", "catalogs"),
            HomeSectionOrderPolicy.renderOrder(stored)
        )
        val hidden = HomeSectionOrderPolicy.setEnabled(stored, "upcoming", false)!!
        assertEquals(
            "a write keeps new_episodes and sports exactly as they were",
            listOf("hero", "new_episodes", "continue_watching", "sports", "live_recents", "upcoming", "catalogs"),
            ids(hidden.sections)
        )
        assertEquals(false, hidden.sections.first { it.id == "sports" }.enabled)
        assertEquals(false, hidden.sections.first { it.id == "upcoming" }.enabled)
    }

    @Test
    fun moveSwapsWithNeighbourAndCustomises() {
        val moved = HomeSectionOrderPolicy.move(null, "live_recents", -1)!!
        assertTrue("a first change customises the list", moved.customized)
        assertEquals(
            listOf("hero", "live_recents", "continue_watching", "upcoming", "catalogs"),
            ids(moved.sections)
        )
    }

    @Test
    fun moveAtTheEdgeChangesNothing() {
        val start = HomeSectionOrderPolicy.reset()
        assertSame("top row cannot go up", start, HomeSectionOrderPolicy.move(start, "continue_watching", -1))
        assertSame("bottom row cannot go down", start, HomeSectionOrderPolicy.move(start, "catalogs", +1))
        assertFalse(HomeSectionOrderPolicy.canMove(start, "continue_watching", -1))
        assertTrue(HomeSectionOrderPolicy.canMove(start, "continue_watching", +1))
        assertFalse(HomeSectionOrderPolicy.canMove(start, "catalogs", +1))
    }

    @Test
    fun heroIsPinnedFirstAndCannotMove() {
        val stored = cfg(true, "catalogs" to true, "hero" to true, "continue_watching" to true)
        val list = HomeSectionOrderPolicy.effective(stored)
        assertEquals("hero is first however it was stored", "hero", list.first().id)
        assertSame(
            "hero is not a movable row",
            stored,
            HomeSectionOrderPolicy.move(stored, "hero", +1)
        )
        assertFalse(HomeSectionOrderPolicy.canMove(stored, "hero", +1))
        assertFalse("hero is not in the reorder list", "hero" in ids(HomeSectionOrderPolicy.movableRows(stored)))
    }

    @Test
    fun heroOnOffIsAllowed() {
        val off = HomeSectionOrderPolicy.setEnabled(null, "hero", false)!!
        assertEquals(false, off.sections.first { it.id == "hero" }.enabled)
        assertEquals("hero", off.sections.first().id)
    }

    @Test
    fun missingRowIsSlottedAfterItsDefaultPredecessor() {
        // A phone wrote a list without live_recents.
        val stored = cfg(true, "hero" to true, "catalogs" to true, "continue_watching" to true, "upcoming" to true)
        assertEquals(
            listOf("catalogs", "continue_watching", "live_recents", "upcoming"),
            HomeSectionOrderPolicy.renderOrder(stored)
        )
    }

    @Test
    fun duplicateAndBlankIdsAreDropped() {
        val stored = cfg(true, "hero" to true, "" to true, "catalogs" to true, "catalogs" to false)
        val list = HomeSectionOrderPolicy.effective(stored)
        assertEquals(1, list.count { it.id == "catalogs" })
        assertTrue(list.first { it.id == "catalogs" }.enabled)
        assertTrue(list.none { it.id.isBlank() })
    }

    @Test
    fun resetIsNotCustomisedAndDefaultOrder() {
        val reset = HomeSectionOrderPolicy.reset()
        assertFalse(reset.customized)
        assertEquals(
            listOf("hero", "continue_watching", "live_recents", "upcoming", "catalogs"),
            ids(reset.sections)
        )
        assertTrue(reset.sections.all { it.enabled })
        // Whatever was customised before, Reset renders the default.
        assertEquals(
            listOf("continue_watching", "live_recents", "upcoming", "catalogs"),
            HomeSectionOrderPolicy.renderOrder(reset)
        )
    }

    @Test
    fun legacySwitchesDriveHeroAndContinueWatching() {
        val stored = cfg(
            true,
            "hero" to true, "continue_watching" to true, "live_recents" to true, "upcoming" to true, "catalogs" to true
        )
        assertEquals(
            "Continue Watching switched off in Settings stays off here",
            listOf("live_recents", "upcoming", "catalogs"),
            HomeSectionOrderPolicy.renderOrder(stored, continueWatchingEnabled = false)
        )
        assertEquals(
            false,
            HomeSectionOrderPolicy.effective(stored, heroEnabled = false).first { it.id == "hero" }.enabled
        )
    }

    @Test
    fun legacyToggleUpdatesOnlyACustomisedList() {
        val customised = cfg(true, "hero" to true, "continue_watching" to true, "catalogs" to true)
        val updated = HomeSectionOrderPolicy.withLegacyToggle(customised, "continue_watching", false)!!
        assertEquals(false, updated.sections.first { it.id == "continue_watching" }.enabled)
        assertTrue("stays customised", updated.customized)

        val plain = cfg(false, "hero" to true)
        assertSame(plain, HomeSectionOrderPolicy.withLegacyToggle(plain, "hero", false))
        assertNull(HomeSectionOrderPolicy.withLegacyToggle(null, "hero", false))
        assertSame(
            "other ids are not legacy toggles",
            customised,
            HomeSectionOrderPolicy.withLegacyToggle(customised, "catalogs", false)
        )
    }

    @Test
    fun storedEnabledReadsOnlyACustomisedList() {
        assertNull(HomeSectionOrderPolicy.storedEnabled(cfg(false, "hero" to false), "hero"))
        assertEquals(false, HomeSectionOrderPolicy.storedEnabled(cfg(true, "hero" to false), "hero"))
        assertNull(HomeSectionOrderPolicy.storedEnabled(cfg(true, "hero" to false), "catalogs"))
    }

    @Test
    fun jsonRoundTripsAndBadJsonIsNoConfig() {
        val config = cfg(true, "hero" to true, "future" to false, "catalogs" to true)
        assertEquals(config, HomeSectionsJson.decode(HomeSectionsJson.encode(config)))
        assertNull(HomeSectionsJson.decode("not json"))
        assertNull(HomeSectionsJson.decode(null))
        assertNull(HomeSectionsJson.decode(""))
    }
}
