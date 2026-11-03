package com.nuvio.tv.core.iptv.match

import com.nuvio.tv.core.iptv.match.CatalogResiliencePolicy.CategoryStep
import com.nuvio.tv.core.iptv.match.CatalogResiliencePolicy.Remembered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JUnit (TV): assertEquals(message, expected, actual). Twin of Mobile's CatalogResiliencePolicyTest. */
class CatalogResiliencePolicyTest {
    private val ttl = 7 * 24 * 60 * 60 * 1000L

    @Test
    fun `a title lookup does not wait for a missing catalog`() {
        assertFalse(CatalogResiliencePolicy.shouldAwaitBuild(existingBuiltAt = null, force = false, awaitRefresh = false, waitIfMissing = false))
    }

    @Test
    fun `refreshes and explicit waiters still wait`() {
        assertTrue(CatalogResiliencePolicy.shouldAwaitBuild(null, force = true, awaitRefresh = false, waitIfMissing = false))
        assertTrue(CatalogResiliencePolicy.shouldAwaitBuild(5L, force = false, awaitRefresh = true, waitIfMissing = false))
        assertTrue(CatalogResiliencePolicy.shouldAwaitBuild(null, force = false, awaitRefresh = false, waitIfMissing = true))
        assertFalse(CatalogResiliencePolicy.shouldAwaitBuild(5L, force = false, awaitRefresh = false, waitIfMissing = true))
    }

    @Test
    fun `a remembered sid plays when the catalog never landed`() {
        assertEquals("sid trusted without an index", Remembered.USE_SID,
            CatalogResiliencePolicy.remembered(1234, rowPresent = false, catalogComplete = false, updatedAtMs = 1, nowMs = 2, lastAddedAtMs = 0, negativeTtlMs = ttl))
    }

    @Test
    fun `a remembered sid missing from a complete catalog is rematched`() {
        assertEquals("vanished from a complete catalog", Remembered.UNKNOWN,
            CatalogResiliencePolicy.remembered(1234, rowPresent = false, catalogComplete = true, updatedAtMs = 1, nowMs = 2, lastAddedAtMs = 0, negativeTtlMs = ttl))
    }

    @Test
    fun `a remembered row in the index is used`() {
        assertEquals("row present", Remembered.USE_ROW,
            CatalogResiliencePolicy.remembered(1234, rowPresent = true, catalogComplete = true, updatedAtMs = 1, nowMs = 2, lastAddedAtMs = 0, negativeTtlMs = ttl))
    }

    @Test
    fun `a fresh negative verdict holds until the catalog gains titles`() {
        assertEquals("fresh miss", Remembered.NOT_ON_PROVIDER,
            CatalogResiliencePolicy.remembered(null, false, true, updatedAtMs = 100, nowMs = 200, lastAddedAtMs = 50, negativeTtlMs = ttl))
        assertEquals("catalog gained titles since", Remembered.UNKNOWN,
            CatalogResiliencePolicy.remembered(null, false, true, updatedAtMs = 100, nowMs = 200, lastAddedAtMs = 150, negativeTtlMs = ttl))
        assertEquals("expired", Remembered.UNKNOWN,
            CatalogResiliencePolicy.remembered(null, false, true, updatedAtMs = 100, nowMs = 100 + ttl, lastAddedAtMs = 0, negativeTtlMs = ttl))
    }

    @Test
    fun `the full list is avoided for six hours after it failed`() {
        assertTrue(CatalogResiliencePolicy.useFullList(avoidFullUntilMs = null, nowMs = 0))
        assertFalse(CatalogResiliencePolicy.useFullList(avoidFullUntilMs = 1_000, nowMs = 999))
        assertTrue(CatalogResiliencePolicy.useFullList(avoidFullUntilMs = 1_000, nowMs = 1_000))
        assertEquals("six hours", 6 * 60 * 60 * 1000L, CatalogResiliencePolicy.AVOID_FULL_MS)
    }

    @Test
    fun `an overloaded provider stops the category walk`() {
        assertEquals("429", CategoryStep.STOP_AND_BACK_OFF, CatalogResiliencePolicy.afterCategoryFailure(429, hostGuardRefused = false))
        assertEquals("520", CategoryStep.STOP_AND_BACK_OFF, CatalogResiliencePolicy.afterCategoryFailure(520, hostGuardRefused = false))
        assertEquals("host guard", CategoryStep.STOP_AND_BACK_OFF, CatalogResiliencePolicy.afterCategoryFailure(null, hostGuardRefused = true))
    }

    @Test
    fun `a cut off category is skipped not fatal`() {
        assertEquals("no status", CategoryStep.SKIP_CATEGORY, CatalogResiliencePolicy.afterCategoryFailure(null, hostGuardRefused = false))
        assertEquals("404", CategoryStep.SKIP_CATEGORY, CatalogResiliencePolicy.afterCategoryFailure(404, hostGuardRefused = false))
    }

    @Test
    fun `only a whole single pass walk finalises the index`() {
        assertTrue(CatalogResiliencePolicy.walkCompletes(categoriesTotal = 301, categoriesDone = 301, startedAtCategory = 0))
        assertFalse(CatalogResiliencePolicy.walkCompletes(301, 300, 0))
        assertFalse(CatalogResiliencePolicy.walkCompletes(301, 301, 12))
        assertFalse(CatalogResiliencePolicy.walkCompletes(0, 0, 0))
    }

    @Test
    fun `low memory devices keep one build at a time`() {
        assertEquals("low", 1, CatalogResiliencePolicy.globalBuildPermits(lowMemoryDevice = true))
        assertEquals("normal", 2, CatalogResiliencePolicy.globalBuildPermits(lowMemoryDevice = false))
    }
}
