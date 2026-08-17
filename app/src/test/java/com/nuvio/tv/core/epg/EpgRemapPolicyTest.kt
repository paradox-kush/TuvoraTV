package com.nuvio.tv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the re-match schedule that ended the Onn-box "background spin": one Sports-tab visit
 * used to re-match 49k channels (115.7s CPU / 2.5min on a 2GB box — research/
 * tv-epg-mirror-spin.md) because (a) every mirror generation bump re-matched every account and
 * (b) an account whose channel fetch failed never wrote rows, stayed "missing" forever, and
 * re-ran the full episode on EVERY surface visit.
 *
 * The policy keys on the INDEX GENERATION because matching is a pure function of (names,
 * index): same inputs cannot yield a different answer, so re-running is provably pointless.
 */
class EpgRemapPolicyTest {

    private val now = 1_000_000_000_000L
    private val h = 60 * 60 * 1000L
    private val genA = "2026-08-17T00:00:00Z"
    private val genB = "2026-08-18T00:00:00Z"

    @Test
    fun `a never-mapped account maps immediately`() {
        assertEquals(
            EpgRemapPolicy.Decision.REMATCH,
            EpgRemapPolicy.decide(now, false, mappedGeneration = "", currentGeneration = genA, attemptedAtMs = 0L, agedBudgetLeft = true),
        )
    }

    @Test
    fun `a failed attempt cools down instead of re-running on every visit`() {
        assertEquals(
            EpgRemapPolicy.Decision.SKIP,
            EpgRemapPolicy.decide(now, false, "", genA, attemptedAtMs = now - 1 * h, agedBudgetLeft = true),
        )
    }

    @Test
    fun `a cooled-down failure retries`() {
        assertEquals(
            EpgRemapPolicy.Decision.REMATCH,
            EpgRemapPolicy.decide(now, false, "", genA, attemptedAtMs = now - EpgRemapPolicy.ATTEMPT_COOLDOWN_MS, agedBudgetLeft = true),
        )
    }

    /** The whole point: identical inputs, so no amount of asking can change the answer. */
    @Test
    fun `same generation never re-matches`() {
        assertEquals(
            EpgRemapPolicy.Decision.SKIP,
            EpgRemapPolicy.decide(now, false, genA, genA, attemptedAtMs = now - 90L * 24 * h, agedBudgetLeft = true),
        )
    }

    @Test
    fun `a new generation re-matches - that is the only thing that can change the result`() {
        assertEquals(
            EpgRemapPolicy.Decision.REMATCH,
            EpgRemapPolicy.decide(now, false, genA, genB, attemptedAtMs = now - 1 * h, agedBudgetLeft = true),
        )
    }

    @Test
    fun `only one account re-matches per sync on a generation bump`() {
        assertEquals(
            EpgRemapPolicy.Decision.SKIP,
            EpgRemapPolicy.decide(now, false, genA, genB, attemptedAtMs = now - 1 * h, agedBudgetLeft = false),
        )
    }

    @Test
    fun `no published generation keeps the existing mapping`() {
        assertEquals(
            EpgRemapPolicy.Decision.SKIP,
            EpgRemapPolicy.decide(now, false, genA, currentGeneration = "", attemptedAtMs = 0L, agedBudgetLeft = true),
        )
    }

    @Test
    fun `an empty-but-successful match counts as mapped`() {
        // The generation is stamped even when zero channels matched (all-24-7 accounts) — the
        // old code keyed "mapped" on row presence, so an all-miss account re-ran forever.
        assertEquals(
            EpgRemapPolicy.Decision.SKIP,
            EpgRemapPolicy.decide(now, false, genA, genA, attemptedAtMs = now - 1 * h, agedBudgetLeft = true),
        )
    }

    @Test
    fun `force wins over everything`() {
        assertEquals(
            EpgRemapPolicy.Decision.REMATCH,
            EpgRemapPolicy.decide(now, force = true, mappedGeneration = genA, currentGeneration = genA, attemptedAtMs = now, agedBudgetLeft = false),
        )
    }
}
