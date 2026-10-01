package com.nuvio.tv.ui.screens.search

/**
 * UX15: the Search screen kept showing the old IPTV rows after the viewer changed a playlist's
 * content settings (content types, categories, enabling a playlist), because nothing re-ran the
 * shown query. Pure decision for what to do when the IPTV source signature changes.
 *
 * Delta-shaped: a settings change only changes what the IPTV lane returns, so normally only the
 * IPTV rows are re-fetched — the addon catalogs (network) are left alone. The whole search re-runs
 * only when the IPTV lane appears or disappears, which flips the request key's `iptv=` and can
 * change the "no catalogs" state.
 */
internal object IptvSearchRefreshPolicy {
    enum class Action { NONE, REFRESH_IPTV_ROWS, RERUN_SEARCH }

    /** The search the screen ran: its query and the IPTV source signature it ran with (null = no IPTV). */
    data class ShownSearch(val query: String, val iptvSignature: String?)

    /**
     * [displayedQuery] is the query the screen shows results for right now; a change that lands
     * after the viewer moved on is ignored (the next search reads the new sources anyway).
     */
    fun onSourcesChanged(shown: ShownSearch?, displayedQuery: String, current: String?): Action {
        if (shown == null) return Action.NONE
        if (shown.query.length < MIN_SEARCH_QUERY_LENGTH || shown.query != displayedQuery.trim()) return Action.NONE
        if (shown.iptvSignature == current) return Action.NONE
        if ((shown.iptvSignature == null) != (current == null)) return Action.RERUN_SEARCH
        return Action.REFRESH_IPTV_ROWS
    }
}
