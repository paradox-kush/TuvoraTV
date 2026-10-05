package com.nuvio.tv.core.iptv.match

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B122 (2026-10-04): Stalker playlists were never offered as a source for add-on titles, while
 * Xtream was. Xtream matches through the index, which keys every catalog name with
 * [TitleNormalizer.keysOf] (provider tags, years, language suffixes stripped). The Stalker lane
 * compared the portal's raw name with plain normKey EQUALITY, so the everyday portal naming —
 * "EN - The Matrix (1999)", "|EN| The Matrix", "The Matrix 4K" — never matched anything.
 */
class StalkerTitleMatchPolicyTest {

    private val matrix = StalkerTitleMatchPolicy.wantKeys(listOf("The Matrix", null))
    private val bb = StalkerTitleMatchPolicy.wantKeys(listOf("Breaking Bad", "Breaking Bad"))

    @Test
    fun `everyday portal movie names match the TMDB title`() {
        listOf(
            "The Matrix (1999)",
            "EN - The Matrix (1999)",
            "|EN| The Matrix",
            "EN: The Matrix 1999",
            "The Matrix 4K",
            "The Matrix (1999) [MULTI-SUB]",
            "4K-NF - The Matrix",
        ).forEach { assertTrue("must match: $it", StalkerTitleMatchPolicy.movieMatches(it, matrix, year = 1999)) }
    }

    @Test
    fun `a different film or a wrong year does not match`() {
        assertFalse("sequel", StalkerTitleMatchPolicy.movieMatches("EN - The Matrix Reloaded (2003)", matrix, 1999))
        assertFalse("year off by 22", StalkerTitleMatchPolicy.movieMatches("The Matrix (2021)", matrix, 1999))
    }

    @Test
    fun `everyday portal series names match`() {
        listOf("Breaking Bad", "EN - Breaking Bad", "Breaking Bad (Hindi)", "|UK| Breaking Bad EN").forEach {
            assertTrue("must match: $it", StalkerTitleMatchPolicy.seriesMatches(it, bb, season = 2))
        }
    }

    @Test
    fun `a split-season entry is offered only for its own season`() {
        assertTrue("S5 entry, S5 asked", StalkerTitleMatchPolicy.seriesMatches("Breaking Bad S5", bb, season = 5))
        assertFalse("S5 entry, S1 asked", StalkerTitleMatchPolicy.seriesMatches("Breaking Bad S5", bb, season = 1))
        assertFalse("Season 3 entry, S1 asked", StalkerTitleMatchPolicy.seriesMatches("EN - Breaking Bad Season 3", bb, season = 1))
    }

    @Test
    fun `another show does not match`() {
        assertFalse(StalkerTitleMatchPolicy.seriesMatches("Better Call Saul", bb, season = 1))
    }
}
