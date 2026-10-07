package com.nuvio.tv.core.contracts

import kotlinx.coroutines.flow.Flow

/**
 * Neutral IPTV search port (search firewall). Upstream-aligned search code reads IPTV results through
 * this instead of naming the fork's XtreamSearchIndex, so an upstream merge of SearchViewModel cannot
 * silently drop the IPTV lane again (it did in 7d69acade). Mirrors NuvioMobile's
 * `core/contracts/IptvSearchProvider.kt`.
 */
interface IptvSearchProvider {
    /**
     * True when at least one enabled IPTV playlist exists. Search then runs even with zero searchable
     * addons, and the value is part of the search request key.
     */
    suspend fun hasSearchableSources(): Boolean

    /** IPTV result rows in display order (channels, movies, series). Rows without hits are omitted. */
    suspend fun search(query: String): List<IptvSearchRow>

    /**
     * UX15: an opaque fingerprint of everything that changes what [search] can return (which
     * playlists are enabled, their content types and category selections). Emits the current value
     * on collection and again on every change; null while no enabled playlist exists. Equal values
     * mean equal results, so a shown search is refreshed only when this changes.
     */
    fun sourceSignature(): Flow<String?>
}

/**
 * One IPTV result row. [rawType] is the Stremio type the detail screen routes on ("tv", "movie",
 * "series"); [catalogId] is stable per row so the row keeps its key across searches.
 */
data class IptvSearchRow(
    val catalogId: String,
    val name: String,
    val rawType: String,
    val hits: List<IptvSearchHit>,
    /** What the row says it is "from" under its title; null = the IPTV default (a media server names its product). */
    val sourceLabel: String? = null,
)

/** One IPTV search hit. [contentId] is opaque here; the detail pipeline resolves it. */
data class IptvSearchHit(
    val contentId: String,
    val name: String,
    val poster: String?,
    val isLive: Boolean,
)
