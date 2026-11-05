package com.nuvio.tv.ui.screens.search

/** F85: the IPTV content types the search result rows can be narrowed to; [catalogId] is the catalog id of the Xtream row. */
internal enum class IptvSearchScope(val catalogId: String) {
    LIVE("xtream_channels"),
    MOVIES("xtream_movies"),
    SERIES("xtream_series"),
}

/**
 * F85: scope chips (Live / Movies / Series, multi-select, default all) over the IPTV rows of a search. Pure
 * filter over rows already fetched — no new request. Only IPTV rows are narrowed; add-on rows are never touched.
 * The selection is never empty: un-picking the last chip goes back to all, so a viewer can't filter themselves
 * into a blank screen with no obvious way out. Mirrors NuvioMobile's `IptvSearchScopePolicy`.
 */
internal object IptvSearchScopePolicy {
    val ALL: Set<IptvSearchScope> = IptvSearchScope.entries.toSet()

    private val byCatalogId = IptvSearchScope.entries.associateBy { it.catalogId }

    fun scopeOf(catalogId: String): IptvSearchScope? = byCatalogId[catalogId]

    /** True when [rows] carry at least one IPTV row — the only time the chips are worth showing. */
    fun <T> hasIptvRows(rows: List<T>, keyOf: (T) -> String): Boolean = rows.any { scopeOf(keyOf(it)) != null }

    fun toggle(selected: Set<IptvSearchScope>, scope: IptvSearchScope): Set<IptvSearchScope> {
        val next = if (scope in selected) selected - scope else selected + scope
        return if (next.isEmpty()) ALL else next
    }

    /** Keeps every non-IPTV row, and an IPTV row only when its type is selected. Order is preserved. */
    fun <T> filter(rows: List<T>, selected: Set<IptvSearchScope>, keyOf: (T) -> String): List<T> {
        if (selected.isEmpty() || selected.size == ALL.size) return rows
        return rows.filter { row ->
            val scope = scopeOf(keyOf(row))
            scope == null || scope in selected
        }
    }
}
