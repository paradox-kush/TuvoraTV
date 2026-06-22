package com.nuvio.tv.data.locallibrary

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monotonic counter bumped once whenever a local library scan + TMDB match pass
 * completes. The synthetic Local Library addon is structurally identical before
 * and after a scan (its catalogs are derived from source configs, not matches),
 * so the reactive Home pipeline — gated on addon equality / a catalog signature —
 * never re-fetches the freshly-matched catalog content on its own. UI that caches
 * catalog rows observes this revision and force-reloads so newly-matched items
 * appear without an app restart.
 */
@Singleton
class LocalLibraryRevision @Inject constructor() {
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun bump() {
        _revision.value += 1
    }
}
