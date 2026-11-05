package com.nuvio.tv.ui.screens.iptv

/**
 * One fetched window: [items] as shown (divider rows already dropped), [hasMore] judged on the RAW page,
 * and [consumed] = how many RAW rows the window covered. Paging indexes the provider's rows, so the next
 * window must start at the raw count — NOT at the shown count, which drifts below it once rows are hidden.
 * Twin: NuvioMobile `XtreamHubRepository.kt` (RawWindow / PagedState / advancePaged).
 */
data class RawWindow<T>(val items: List<T>, val hasMore: Boolean, val consumed: Int)

/** A category's paging position: rows shown so far, whether more exist, and the raw offset the next window starts at. */
data class PagedState<T>(val items: List<T>, val hasMore: Boolean, val rawOffset: Int)

/**
 * Loads the next window(s). A window whose rows were ALL hidden adds nothing, and the row only asks for
 * more when its item count changes — so such a window must not end the load: keep going (bounded by
 * [maxEmptyWindows], and by the raw offset always advancing) until something new shows or the provider
 * runs out. Rows already loaded are dropped by id (a stale index or a tied ORDER BY overlapping its pages
 * must never append duplicates). [fetch] returns null when the fetch failed/timed out: stop with what we have.
 */
suspend fun <T> advancePaged(
    start: PagedState<T>,
    maxEmptyWindows: Int = 20,
    id: (T) -> String,
    fetch: suspend (rawOffset: Int) -> RawWindow<T>?,
): PagedState<T> {
    var state = start
    var empties = 0
    while (state.hasMore) {
        val window = fetch(state.rawOffset) ?: return state
        val seen = state.items.mapTo(HashSet(state.items.size), id)
        val fresh = window.items.filter { id(it) !in seen }
        // No raw progress means a stuck/overlapping source: stop rather than loop.
        val more = window.hasMore && window.consumed > 0
        state = PagedState(state.items + fresh, more, state.rawOffset + window.consumed)
        if (fresh.isNotEmpty() || !more) return state
        if (++empties >= maxEmptyWindows) return state
    }
    return state
}
