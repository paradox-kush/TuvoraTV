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

    // --- Sports Centre must not be collateral damage of a GUIDE setting -----------------------
    //
    // Found 2026-08-18: the picker was scoped to one job (shrink the guide index) and silently
    // acquired a second (choose which feeds Sports Centre can match against). epgshare-us-sports1
    // is published with countries "United States", so a viewer who picked "United Kingdom" for
    // their guide was also deleting the feed the sports matcher runs on.
    //
    // NOTE assertion order: this is JUnit, so it is assertTrue(message, condition) — the twin in
    // NuvioMobile/NuvioDesktop commonTest is kotlin.test and puts the message LAST.

    private val sportsSources = sources + listOf(
        EpgSourceInfo("epgshare-us-sports1", "epgshare US sports", "United States", 120),
        EpgSourceInfo("epgenius-14", "EPGenius someone/B1G", "United States", 60),
    )

    @Test
    fun `a guide region selection keeps the sports feeds`() {
        val kept = EpgRegionCatalog.slugsFor(setOf("United Kingdom"), sportsSources)
        assertTrue(
            "a sports feed backs Sports Centre, not the guide — a guide region must never delete it",
            "epgshare-us-sports1" in kept,
        )
    }

    @Test
    fun `a guide region selection keeps the curated EPGenius feeds`() {
        // The backend allowlists 7 EPGenius ids by hand for COVERAGE, not by region; they are the
        // sports backbone. Region-filtering a curated allowlist throws away the curation.
        val kept = EpgRegionCatalog.slugsFor(setOf("United Kingdom"), sportsSources)
        assertTrue("curated EPGenius feeds survive any region selection", "epgenius-14" in kept)
    }

    @Test
    fun `keeping sports does not readmit the general feeds the viewer filtered out`() {
        val kept = EpgRegionCatalog.slugsFor(setOf("United Kingdom"), sportsSources)
        assertTrue("a general US guide feed is still filtered out", "epgshare-us2" !in kept)
    }

    @Test
    fun `regions implied by followed leagues survive the selection`() {
        // A viewer following Liga MX needs Mexico's feed even though their guide is UK-only.
        val withMx = sportsSources + EpgSourceInfo("epgshare-mx1", "epgshare MX1", "Mexico", 200)
        val kept = EpgRegionCatalog.slugsFor(setOf("United Kingdom"), withMx, setOf("Mexico"))
        assertTrue("a followed league's country is not optional coverage", "epgshare-mx1" in kept)
        assertTrue("and nothing else leaks back in", "epgshare-us2" !in kept)
    }
}
