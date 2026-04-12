package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sanity checks for the three wire-format enum converters. These run on every
 * fanout write so even small regressions (e.g. AniList renaming CURRENT to
 * WATCHING in a future API rev) get flagged by CI immediately.
 */
class TrackerListStatusTest {

    @Test
    fun `MAL round-trip preserves every canonical status`() {
        TrackerListStatus.values()
            .filter { it != TrackerListStatus.REWATCHING } // MAL has no separate rewatching status
            .forEach { status ->
                val wire = status.toMal()
                val parsed = TrackerListStatus.fromMal(wire)
                // WATCHING and REWATCHING both map to MAL "watching"; fromMal returns WATCHING.
                val expected = if (status == TrackerListStatus.REWATCHING) TrackerListStatus.WATCHING else status
                assertEquals("round-trip for $status via $wire", expected, parsed)
            }
    }

    @Test
    fun `AniList round-trip preserves every status`() {
        TrackerListStatus.values().forEach { status ->
            val wire = status.toAniList()
            assertEquals("round-trip for $status via $wire", status, TrackerListStatus.fromAniList(wire))
        }
    }

    @Test
    fun `Kitsu round-trip handles rewatching fallback`() {
        TrackerListStatus.values()
            .filter { it != TrackerListStatus.REWATCHING }
            .forEach { status ->
                val wire = status.toKitsu()
                assertEquals("round-trip for $status via $wire", status, TrackerListStatus.fromKitsu(wire))
            }
        // REWATCHING is a synthetic value on Kitsu — writes to "current".
        assertEquals("current", TrackerListStatus.REWATCHING.toKitsu())
    }

    @Test
    fun `fromXxx ignores unknown values`() {
        assertNull(TrackerListStatus.fromMal("rewatching_arc"))
        assertNull(TrackerListStatus.fromAniList("NOT_A_STATUS"))
        assertNull(TrackerListStatus.fromKitsu(""))
        assertNull(TrackerListStatus.fromKitsu(null))
    }

    @Test
    fun `case folding is consistent with wire format`() {
        // MAL / Kitsu lowercase, AniList uppercase. Tolerate either way around.
        assertEquals(TrackerListStatus.COMPLETED, TrackerListStatus.fromMal("COMPLETED"))
        assertEquals(TrackerListStatus.COMPLETED, TrackerListStatus.fromKitsu("COMPLETED"))
        assertEquals(TrackerListStatus.COMPLETED, TrackerListStatus.fromAniList("completed"))
    }
}
