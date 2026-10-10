package com.nuvio.tv.core.addons

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JUnit order: assertEquals(message, expected, actual). */
class AddonLoadRetryPolicyTest {

    @Test
    fun `the automatic ladder is short and then stops`() {
        assertEquals("first retry", 2_000L, AddonLoadRetryPolicy.delayBeforeRetry(0))
        assertEquals("second retry", 8_000L, AddonLoadRetryPolicy.delayBeforeRetry(1))
        assertNull("no third automatic retry", AddonLoadRetryPolicy.delayBeforeRetry(2))
    }

    @Test
    fun `only rows that failed and are still on Home are fetched again`() {
        val retry = AddonLoadRetryPolicy.catalogsToRetry(
            requestedKeys = listOf("a", "b", "c", "b"),
            failedKeys = setOf("b", "gone"),
        )
        assertEquals("failed and still requested, once", listOf("b"), retry)
    }

    @Test
    fun `nothing failed means no request at all`() {
        assertTrue(AddonLoadRetryPolicy.catalogsToRetry(listOf("a", "b"), emptySet()).isEmpty())
        assertTrue(
            AddonLoadRetryPolicy.manifestsToRetry(
                enabledUrls = listOf("x"),
                loadedUrls = setOf("x"),
                failedUrls = emptySet(),
                inFlightUrls = emptySet(),
            ).isEmpty()
        )
    }

    @Test
    fun `only enabled add-ons whose manifest failed and is not in flight are fetched again`() {
        val retry = AddonLoadRetryPolicy.manifestsToRetry(
            enabledUrls = listOf("failed", "loaded", "in-flight", "pending", "failed"),
            loadedUrls = setOf("loaded"),
            // "disabled" failed too, but is not enabled; "loaded" failed a background refresh yet has a manifest.
            failedUrls = setOf("failed", "loaded", "in-flight", "disabled"),
            inFlightUrls = setOf("in-flight"),
        )
        assertEquals("only the failed, enabled, idle add-on", listOf("failed"), retry)
    }

    @Test
    fun `missing rows are said out loud once loading settles`() {
        assertTrue(AddonLoadRetryPolicy.showsPartialFailure(failedRowCount = 1, failedManifestCount = 0, isLoading = false))
        assertTrue(AddonLoadRetryPolicy.showsPartialFailure(failedRowCount = 0, failedManifestCount = 1, isLoading = false))
        assertFalse(AddonLoadRetryPolicy.showsPartialFailure(failedRowCount = 1, failedManifestCount = 0, isLoading = true))
        assertFalse(AddonLoadRetryPolicy.showsPartialFailure(failedRowCount = 0, failedManifestCount = 0, isLoading = false))
    }

    @Test
    fun `the ladder retries twice on the backoff and then stops while still failing`() = runBlocking {
        val waits = mutableListOf<Long>()
        var calls = 0
        val retries = AddonLoadRetryPolicy.runLadder(sleep = { waits += it }) { calls++; true }

        assertEquals("waits follow the backoff", listOf(2_000L, 8_000L), waits)
        assertEquals("two retries", 2, retries)
        assertEquals("two fetch passes", 2, calls)
    }

    @Test
    fun `the ladder stops as soon as nothing is failing`() = runBlocking {
        val waits = mutableListOf<Long>()
        var calls = 0
        val retries = AddonLoadRetryPolicy.runLadder(sleep = { waits += it }) { calls++; false }

        assertEquals("one wait", listOf(2_000L), waits)
        assertEquals("one retry", 1, retries)
        assertEquals("one fetch pass", 1, calls)
    }
}
