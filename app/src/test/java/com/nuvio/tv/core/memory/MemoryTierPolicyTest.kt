package com.nuvio.tv.core.memory

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the tier rules and the pressure-escalation semantics (WP1) — JUnit twin of the
 * mobile/desktop commonTest. The iOS physical-memory test has no TV counterpart: this
 * codebase only ships to Android TV, so the policy here carries no iOS rule.
 *
 * Escalation contract, pinned here: 2+ CONSECUTIVE pressure samples escalate one tier below
 * base; the escalated tier holds for MemoryPressureGovernor.DEFAULT_HOLD_MS past the last
 * pressure sample (a relax sample inside the hold only resets the consecutive counter);
 * after the hold the tier relaxes back to base.
 */
class MemoryTierPolicyTest {

    @Test
    fun `android probe maps low-ram flag and memory class to tiers`() {
        // The flag alone forces LOW even with a roomy memoryClass.
        assertEquals(MemoryTier.LOW, MemoryTierPolicy.androidTier(isLowRamDevice = true, memoryClassMb = 512))
        // memoryClass thresholds: <=192 LOW, <=320 MID, else HIGH (StreamVault-proven cuts).
        assertEquals(MemoryTier.LOW, MemoryTierPolicy.androidTier(isLowRamDevice = false, memoryClassMb = 128))
        assertEquals(MemoryTier.LOW, MemoryTierPolicy.androidTier(isLowRamDevice = false, memoryClassMb = 192))
        assertEquals(MemoryTier.MID, MemoryTierPolicy.androidTier(isLowRamDevice = false, memoryClassMb = 256))
        assertEquals(MemoryTier.MID, MemoryTierPolicy.androidTier(isLowRamDevice = false, memoryClassMb = 320))
        assertEquals(MemoryTier.HIGH, MemoryTierPolicy.androidTier(isLowRamDevice = false, memoryClassMb = 384))
        assertEquals(MemoryTier.HIGH, MemoryTierPolicy.androidTier(isLowRamDevice = false, memoryClassMb = 512))
    }

    @Test
    fun `image cache budget follows the tier`() {
        assertEquals(32L * 1024 * 1024, MemoryTierPolicy.imageMemoryCacheBytes(MemoryTier.LOW))
        assertEquals(64L * 1024 * 1024, MemoryTierPolicy.imageMemoryCacheBytes(MemoryTier.MID))
        assertEquals(96L * 1024 * 1024, MemoryTierPolicy.imageMemoryCacheBytes(MemoryTier.HIGH))
    }

    /**
     * How many catalog rows one index transaction may hold.
     *
     * Measured on the user's 2 GB Onn 4K box (2026-08-16, v1.4.30): the catalog index build held a
     * worker at 97% CPU for minutes and the hub's own reads — which come from the SAME index DB —
     * queued behind its write transaction, so movies "didn't load" and rows never filled. The batch
     * was 5,000 rows, each costing an UPDATE-or-INSERT plus one INSERT per normalized key: roughly
     * 25,000 statements under the writer lock before it was released.
     *
     * The numbers are StreamVault's (CatalogSyncRuntimeProfile: LOW 100 / MID 300 / HIGH 500),
     * whose tier cuts we already share. Smaller batches lower BOTH the lock hold and the heap peak
     * — the old 5,000 was chosen against "the whole catalog at once", never against 500.
     */
    @Test
    fun `index batch size follows the tier`() {
        assertEquals("a 1GB box must not hold 5k rows of writes", 100, MemoryTierPolicy.indexBatchSize(MemoryTier.LOW))
        assertEquals(300, MemoryTierPolicy.indexBatchSize(MemoryTier.MID))
        assertEquals(500, MemoryTierPolicy.indexBatchSize(MemoryTier.HIGH))
    }

    @Test
    fun `pressure escalates one tier transiently and relaxes back`() {
        val hold = MemoryPressureGovernor.DEFAULT_HOLD_MS
        val governor = MemoryPressureGovernor(baseTier = MemoryTier.HIGH)

        // Two consecutive pressure samples escalate one tier below base.
        governor.onPressure(nowMs = 1_000L)
        governor.onPressure(nowMs = 2_000L)
        assertEquals(MemoryTier.MID, governor.effectiveTier(nowMs = 2_000L))

        // Still held just before the hold expires...
        assertEquals(MemoryTier.MID, governor.effectiveTier(nowMs = 2_000L + hold - 1))
        // ...and relaxed back to base after it.
        assertEquals(MemoryTier.HIGH, governor.effectiveTier(nowMs = 2_000L + hold + 1))

        // LOW base has no lower rung: escalation stays LOW.
        val floor = MemoryPressureGovernor(baseTier = MemoryTier.LOW)
        floor.onPressure(nowMs = 1_000L)
        floor.onPressure(nowMs = 2_000L)
        assertEquals(MemoryTier.LOW, floor.effectiveTier(nowMs = 2_000L))
    }

    @Test
    fun `tier never flaps on a single sample`() {
        val hold = MemoryPressureGovernor.DEFAULT_HOLD_MS
        val governor = MemoryPressureGovernor(baseTier = MemoryTier.HIGH)

        // A lone pressure sample never changes the tier.
        governor.onPressure(nowMs = 1_000L)
        assertEquals(MemoryTier.HIGH, governor.effectiveTier(nowMs = 1_001L))

        // A relax in between breaks the streak: the next lone pressure still changes nothing.
        governor.onRelax(nowMs = 2_000L)
        governor.onPressure(nowMs = 3_000L)
        assertEquals(MemoryTier.HIGH, governor.effectiveTier(nowMs = 3_001L))

        // Once genuinely escalated, a single relax sample inside the hold does not flap it back.
        governor.onPressure(nowMs = 4_000L)
        assertEquals(MemoryTier.MID, governor.effectiveTier(nowMs = 4_000L))
        governor.onRelax(nowMs = 4_500L)
        assertEquals(MemoryTier.MID, governor.effectiveTier(nowMs = 4_500L))

        // The hold still expires from the LAST pressure sample.
        assertEquals(MemoryTier.HIGH, governor.effectiveTier(nowMs = 4_000L + hold + 1))
    }

    @Test
    fun `repeated pressure extends the hold and never drops more than one tier`() {
        val hold = MemoryPressureGovernor.DEFAULT_HOLD_MS
        val governor = MemoryPressureGovernor(baseTier = MemoryTier.HIGH)
        governor.onPressure(nowMs = 0L)
        governor.onPressure(nowMs = 1_000L)
        governor.onPressure(nowMs = 2_000L)
        governor.onPressure(nowMs = 3_000L)
        // Never below one tier under base, no matter how many samples.
        assertEquals(MemoryTier.MID, governor.effectiveTier(nowMs = 3_000L))
        // The hold runs from the last sample, not the first.
        assertEquals(MemoryTier.MID, governor.effectiveTier(nowMs = 1_000L + hold + 1))
        assertEquals(MemoryTier.HIGH, governor.effectiveTier(nowMs = 3_000L + hold + 1))
    }
}
