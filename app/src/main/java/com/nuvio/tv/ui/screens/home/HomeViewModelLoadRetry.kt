package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.addons.AddonLoadRetryPolicy
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/*
 * Failed Home loads are fetched again instead of staying missing until the app restarts.
 * The decisions live in [AddonLoadRetryPolicy]; this file is only the Home bookkeeping:
 *  - a row whose fetch failed (with nothing on screen) is retried on the 2 s / 8 s ladder,
 *  - then once each time Home is resumed ([retryFailedHomeLoadsPipeline]) - never on a timer,
 *  - only the failed rows are fetched, and nothing at all when none failed.
 */

/** A row failed. A refresh of a row that is already on screen is not a missing row, so it is ignored. */
internal fun HomeViewModel.recordCatalogLoadFailure(
    key: String,
    addon: Addon,
    catalog: CatalogDescriptor,
    generation: Long
) {
    if (generation != catalogLoadGeneration) return
    if (readCatalogRow(key)?.items?.isNotEmpty() == true) return
    synchronized(catalogStateLock) { failedCatalogLoads[key] = addon to catalog }
    publishCatalogLoadFailures()
    scheduleCatalogRetryLadder(generation)
}

internal fun HomeViewModel.clearCatalogLoadFailure(key: String) {
    val removed = synchronized(catalogStateLock) { failedCatalogLoads.remove(key) != null }
    if (removed) publishCatalogLoadFailures()
}

/** A full load re-requests every row; earlier failures and their retry no longer apply. */
internal fun HomeViewModel.resetCatalogLoadFailures() {
    catalogRetryJob?.cancel()
    catalogRetryJob = null
    synchronized(catalogStateLock) { failedCatalogLoads.clear() }
    _uiState.update { state ->
        if (state.failedRowCount == 0 && !state.homeLoadRetryPending) state
        else state.copy(failedRowCount = 0, homeLoadRetryPending = false)
    }
}

/** Home was resumed (or Retry was pressed): one pass over what failed, and no request when nothing did. */
internal fun HomeViewModel.retryFailedHomeLoadsPipeline() {
    addonRepository.retryFailedManifests()
    if (catalogRetryJob?.isActive == true || catalogsLoadInProgress) return
    val hasFailures = synchronized(catalogStateLock) { failedCatalogLoads.isNotEmpty() }
    if (!hasFailures) return
    val generation = catalogLoadGeneration
    runCatalogRetry(generation) { retryFailedCatalogsOnce(generation) }
}

private fun HomeViewModel.scheduleCatalogRetryLadder(generation: Long) {
    if (catalogRetryJob?.isActive == true) return
    runCatalogRetry(generation) {
        val retries = AddonLoadRetryPolicy.runLadder { retryFailedCatalogsOnce(generation) }
        Log.d(HomeViewModel.TAG, "Home catalog retry ladder finished after $retries retr(y/ies)")
    }
}

private fun HomeViewModel.runCatalogRetry(generation: Long, block: suspend () -> Unit) {
    _uiState.update { it.copy(homeLoadRetryPending = true) }
    catalogRetryJob = viewModelScope.launch {
        try {
            block()
        } finally {
            // A newer full load already reset the flag (and may own a newer retry).
            if (generation == catalogLoadGeneration) {
                _uiState.update { it.copy(homeLoadRetryPending = false) }
            }
        }
    }
}

/** Fetches the failed rows that are still on Home once; true when some still failed. */
private suspend fun HomeViewModel.retryFailedCatalogsOnce(generation: Long): Boolean {
    if (generation != catalogLoadGeneration) return false
    val (requestedKeys, failed) = synchronized(catalogStateLock) {
        (catalogOrder.toList() + currentHeroCatalogKeys) to failedCatalogLoads.toMap()
    }
    val keys = AddonLoadRetryPolicy.catalogsToRetry(requestedKeys, failed.keys)
    if (keys.isEmpty()) return false
    Log.d(HomeViewModel.TAG, "Retrying ${keys.size} failed home catalog(s)")
    pendingCatalogLoads += keys.size
    keys.mapNotNull { failed[it] }
        .map { (addon, catalog) -> loadCatalogPipeline(addon, catalog, generation) }
        .joinAll()
    return synchronized(catalogStateLock) { keys.any { it in failedCatalogLoads } }
}

private fun HomeViewModel.publishCatalogLoadFailures() {
    val count = synchronized(catalogStateLock) { failedCatalogLoads.size }
    _uiState.update { state -> if (state.failedRowCount == count) state else state.copy(failedRowCount = count) }
}
