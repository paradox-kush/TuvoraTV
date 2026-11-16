package com.nuvio.tv.ui.screens.iptv

/**
 * What a group-column row is called. Sim pass 2026-11-16 (UX): the top row read a bare "Search", which says nothing
 * about what it searches (the hub header has its own Search for Movies / Series). It reads "Search channels", and
 * "Search: “bbc”" once a query is in. Pure.
 */
internal object GuideCategoryLabelPolicy {
    sealed interface Label {
        data class Name(val name: String) : Label
        data object SearchChannels : Label
        data class SearchQuery(val query: String) : Label
    }

    fun of(special: GuideSpecial?, name: String, query: String): Label = when {
        special == GuideSpecial.SEARCH && query.isNotBlank() -> Label.SearchQuery(query)
        special == GuideSpecial.SEARCH -> Label.SearchChannels
        else -> Label.Name(name)
    }
}
