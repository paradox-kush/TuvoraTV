package com.nuvio.tv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The region picker exists because the mirror index is mostly dead weight per household: on a
 * real 11k-channel panel only 2,035 of 15,397 indexed EPG channels ever matched. These pin the
 * rules that decide what a selection keeps — getting them wrong silently drops a viewer's EPG.
 *
 * Twin of the KMP EpgRegionCatalogTest. NOTE the assertion argument order differs between the
 * two: JUnit is (message, expected, actual); kotlin.test is (expected, actual, message).
 */
class EpgRegionCatalogTest {

    private val sources = listOf(
        EpgSourceInfo("epgshare-uk1", "epgshare UK1", "United Kingdom", 481),
        EpgSourceInfo("epgshare-us2", "epgshare US2", "United States", 763),
        EpgSourceInfo("epgshare-in1", "epgshare IN1", "India", 1166),
        EpgSourceInfo("epgenius-31", "EPGenius GR/CY", "Cyprus,Greece", 400),
        EpgSourceInfo("mystery", "Unlabelled feed", null, 50),
    )

    @Test
    fun `flags come from country names`() {
        assertEquals("🇬🇧", EpgRegionCatalog.flagFor("United Kingdom"))
        assertEquals("🇮🇳", EpgRegionCatalog.flagFor("india"))
        // Unknown name degrades to no flag rather than a wrong one.
        assertEquals("", EpgRegionCatalog.flagFor("Atlantis"))
    }

    @Test
    fun `a multi-country source appears under each country`() {
        val catalog = EpgRegionCatalog.catalogFrom(sources)
        assertTrue("epgenius-31" in catalog.first { it.name == "Greece" }.slugs)
        assertTrue("epgenius-31" in catalog.first { it.name == "Cyprus" }.slugs)
    }

    @Test
    fun `catalog is ordered by coverage so the useful regions lead the list`() {
        assertEquals("India", EpgRegionCatalog.catalogFrom(sources).first().name)
    }

    @Test
    fun `an unlabelled source becomes the other region`() {
        val catalog = EpgRegionCatalog.catalogFrom(sources)
        assertEquals(setOf("mystery"), catalog.first { it.name == EpgRegionCatalog.UNCLASSIFIED }.slugs)
    }

    /** The picker is opt-in: an untouched install must behave exactly as it did before. */
    @Test
    fun `an empty selection keeps everything`() {
        assertEquals(
            sources.map { it.slug }.toSet(),
            EpgRegionCatalog.slugsFor(emptySet(), sources),
        )
    }

    @Test
    fun `a selection keeps only its regions plus unclassified`() {
        val kept = EpgRegionCatalog.slugsFor(setOf("United Kingdom", "India"), sources)
        assertTrue("epgshare-uk1" in kept)
        assertTrue("epgshare-in1" in kept)
        assertTrue("US was not selected", "epgshare-us2" !in kept)
        // Never hide a source the backend didn't label — that would drop coverage the viewer
        // never chose to drop.
        assertTrue("unclassified sources always survive a filter", "mystery" in kept)
    }

    @Test
    fun `selection matching is case insensitive`() {
        assertTrue("epgshare-uk1" in EpgRegionCatalog.slugsFor(setOf("united kingdom"), sources))
    }

    @Test
    fun `selecting one country of a multi-country source keeps it`() {
        // Dropping the shared feed would take Greece's EPG away with Cyprus's.
        assertTrue("epgenius-31" in EpgRegionCatalog.slugsFor(setOf("Greece"), sources))
    }
}
