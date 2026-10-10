package com.nuvio.tv.core.addons

import kotlinx.coroutines.delay

/**
 * What is fetched again after an add-on load fails — a manifest, or one of its catalog rows on Home — and when.
 *
 * Home's first load fetched every add-on manifest and every catalog row exactly once. One bad moment
 * (a cold network, a slow add-on timing out) left the failed add-on a catalog-less placeholder and the
 * failed rows absent, with no error while Continue Watching had items, until the app was restarted.
 *
 * Retries are delta-shaped and bounded: only what failed is fetched again, a fixed short ladder right
 * after the failure, then one pass each time Home is resumed (never a timer). Same policy as the
 * Mobile/Desktop `AddonLoadRetryPolicy`.
 */
internal object AddonLoadRetryPolicy {
    /** Waits before each automatic retry that follows a failure; after the last one Home waits for a visit. */
    val BACKOFF_MS: List<Long> = listOf(2_000L, 8_000L)

    fun delayBeforeRetry(retriesDone: Int): Long? = BACKOFF_MS.getOrNull(retriesDone)

    /** Rows to fetch again: still on Home and failed last time. Rows that loaded are never re-fetched. */
    fun catalogsToRetry(requestedKeys: List<String>, failedKeys: Set<String>): List<String> =
        requestedKeys.filter { it in failedKeys }.distinct()

    /** Add-ons to fetch again: installed and enabled, no manifest, last fetch failed, none already in flight. */
    fun manifestsToRetry(
        enabledUrls: List<String>,
        loadedUrls: Set<String>,
        failedUrls: Set<String>,
        inFlightUrls: Set<String>,
    ): List<String> =
        enabledUrls
            .filter { it in failedUrls && it !in loadedUrls && it !in inFlightUrls }
            .distinct()

    /** Whether Home says some rows are missing (with Retry) instead of silently showing fewer. */
    fun showsPartialFailure(failedRowCount: Int, failedManifestCount: Int, isLoading: Boolean): Boolean =
        !isLoading && (failedRowCount > 0 || failedManifestCount > 0)

    /**
     * Runs the automatic ladder that follows a failure: waits, retries once, and stops as soon as
     * [retryOnce] reports nothing is failing any more, or when the ladder is used up.
     *
     * @param retryOnce fetches what is still failing (a no-op when nothing is) and returns true when
     *   something is still failing afterwards.
     * @return how many retries ran.
     */
    suspend fun runLadder(
        sleep: suspend (Long) -> Unit = { delay(it) },
        retryOnce: suspend () -> Boolean,
    ): Int {
        var retries = 0
        while (true) {
            val wait = delayBeforeRetry(retries) ?: return retries
            sleep(wait)
            retries++
            if (!retryOnce()) return retries
        }
    }
}
