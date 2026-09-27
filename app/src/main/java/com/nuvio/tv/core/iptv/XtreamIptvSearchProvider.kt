package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.contracts.IptvSearchHit
import com.nuvio.tv.core.contracts.IptvSearchProvider
import com.nuvio.tv.core.contracts.IptvSearchRow
import com.nuvio.tv.data.local.XtreamAccountStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Fork implementation of [IptvSearchProvider]: the active profile's IPTV playlists via [XtreamSearchIndex]. */
class XtreamIptvSearchProvider @Inject constructor(
    private val accountStore: XtreamAccountStore,
    private val searchIndex: XtreamSearchIndex,
) : IptvSearchProvider {

    override suspend fun hasSearchableSources(): Boolean = accountStore.accounts.first().any { it.enabled }

    override suspend fun search(query: String): List<IptvSearchRow> = rowsOf(searchIndex.search(query))

    companion object {
        /** Channels, movies, series, in that order, as on Mobile and the pre-merge TV screen. Empty rows are dropped. */
        fun rowsOf(results: XtreamSearchIndex.Results): List<IptvSearchRow> = listOfNotNull(
            row("xtream_channels", "IPTV Channels", "tv", results.channels),
            row("xtream_movies", "IPTV Movies", "movie", results.movies),
            row("xtream_series", "IPTV Series", "series", results.series),
        )

        private fun row(catalogId: String, name: String, rawType: String, hits: List<XtreamSearchIndex.Hit>): IptvSearchRow? =
            hits.takeIf { it.isNotEmpty() }?.let {
                IptvSearchRow(
                    catalogId = catalogId,
                    name = name,
                    rawType = rawType,
                    hits = it.map { hit -> IptvSearchHit(hit.contentId, hit.name, hit.poster, hit.isLive) },
                )
            }
    }
}
