package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.contracts.IptvSearchHit
import com.nuvio.tv.core.contracts.IptvSearchProvider
import com.nuvio.tv.core.contracts.IptvSearchRow
import com.nuvio.tv.core.iptv.overlay.IptvOverlayRepository
import com.nuvio.tv.core.iptv.overlay.OverlaySnapshot
import com.nuvio.tv.data.local.XtreamAccountStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Fork implementation of [IptvSearchProvider]: the active profile's IPTV playlists via [XtreamSearchIndex]. */
class XtreamIptvSearchProvider @Inject constructor(
    private val accountStore: XtreamAccountStore,
    private val searchIndex: XtreamSearchIndex,
    private val overlayRepository: IptvOverlayRepository,
) : IptvSearchProvider {

    override suspend fun hasSearchableSources(): Boolean = accountStore.accounts.first().any { it.enabled }

    override suspend fun search(query: String): List<IptvSearchRow> = rowsOf(searchIndex.search(query))

    override fun sourceSignature(): Flow<String?> =
        combine(accountStore.accounts, overlayRepository.uiState) { accounts, overlay ->
            signatureOf(accounts, hiddenKeysOf(overlay))
        }.distinctUntilChanged()

    companion object {
        /**
         * UX15: fingerprint of what search reads from the enabled playlists — which playlists, what
         * they point at, their content types and category selections. Null with no enabled
         * playlist. Order-insensitive; display-only settings (name, EPG, catch-up, user agent) and
         * the password are left out, so editing those never re-runs a search. [hiddenKeys] (from the overlay)
         * is included because search drops hidden groups — a hide refreshes shown results.
         */
        fun signatureOf(accounts: List<XtreamAccount>, hiddenKeys: Set<String> = emptySet()): String? {
            val enabled = accounts.filter { it.enabled }
            if (enabled.isEmpty()) return null
            fun sel(list: List<String>?) = list?.sorted()?.joinToString(",", "[", "]") ?: "all"
            return enabled.map { acc ->
                listOf(
                    acc.id, acc.sourceType, acc.baseUrl, acc.username, acc.portalUrl, acc.macAddress,
                    acc.contentTypes.sorted().joinToString(","),
                    sel(acc.categorySelections.live), sel(acc.categorySelections.movies), sel(acc.categorySelections.series),
                ).joinToString("\u0001")
            }.sorted().joinToString("\u0002") +
                if (hiddenKeys.isEmpty()) "" else "\u0003" + hiddenKeys.sorted().joinToString(",")
        }

        /** What search filters out of the overlay: hidden groups and hidden channels (pins/renames don't change results). */
        fun hiddenKeysOf(overlay: OverlaySnapshot): Set<String> =
            overlay.categories.filterValues { it.hidden }.keys.mapTo(HashSet()) { "cat:$it" } +
                overlay.channels.filterValues { it.hidden }.keys.map { "ch:$it" }

        /** Channels, movies, series, in that order, as on Mobile and the pre-merge TV screen. Empty rows are dropped. */
        fun rowsOf(results: XtreamSearchIndex.Results): List<IptvSearchRow> = listOfNotNull(
            row("xtream_channels", "IPTV Channels", "tv", results.channels),
            row("xtream_movies", "IPTV Movies", "movie", results.movies),
            row("xtream_series", "IPTV Series", "series", results.series),
        )

        private fun row(catalogId: String, name: String, rawType: String, hits: List<XtreamSearchIndex.Hit>): IptvSearchRow? =
            // UX44: heading rows never surface as hits, in any row.
            IptvSearchRowFilter.withoutDividers(hits) { it.name }.takeIf { it.isNotEmpty() }?.let {
                IptvSearchRow(
                    catalogId = catalogId,
                    name = name,
                    rawType = rawType,
                    hits = it.map { hit -> IptvSearchHit(hit.contentId, hit.name, hit.poster, hit.isLive) },
                )
            }
    }
}
