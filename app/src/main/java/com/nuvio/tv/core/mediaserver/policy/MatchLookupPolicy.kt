package com.nuvio.tv.core.mediaserver.policy

import com.nuvio.tv.core.mediaserver.client.mediabrowser.ItemDto
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserDialect

/**
 * The matched lane's lookup (design 5.6): a TMDB/IMDB title page asks "does this server have it?".
 *  - Emby filters server-side: `/Users/{uid}/Items?AnyProviderIdEquals=tmdb.123&Fields=ProviderIds`;
 *  - Jellyfin has NO provider-id filter (jellyfin#1990, only `hasTmdbId`/`hasImdbId`), so it is a title
 *    search (`SearchTerm` + `Years` +-1) VERIFIED client-side against `ProviderIds.Tmdb/Imdb` - a retitled
 *    item is a known false negative, which the per-server match cache (TTL) mitigates.
 * Skeleton in J1a: the pure query + verification; the client call and the match cache arrive with P3.
 */
internal object MatchLookupPolicy {
    data class ExternalIds(val tmdb: String? = null, val imdb: String? = null, val tvdb: String? = null) {
        val hasAny: Boolean get() = !tmdb.isNullOrBlank() || !imdb.isNullOrBlank() || !tvdb.isNullOrBlank()
    }

    enum class ItemKind(val includeItemType: String) { MOVIE("Movie"), SERIES("Series") }

    sealed interface Query {
        /** `AnyProviderIdEquals=` values, most specific first (`tmdb.123`, `imdb.tt0111161`). */
        data class ByProviderId(val anyProviderIdEquals: List<String>, val includeItemType: String) : Query

        /** A title search whose candidates must be verified with [verify]. */
        data class ByTitle(val searchTerm: String, val years: List<Int>, val includeItemType: String) : Query
    }

    fun query(dialect: MediaBrowserDialect, kind: ItemKind, ids: ExternalIds, title: String?, year: Int?): Query? {
        if (!ids.hasAny) return null
        if (dialect.supportsProviderIdFilter) {
            val values = buildList {
                ids.tmdb?.takeIf { it.isNotBlank() }?.let { add("tmdb.${it.trim()}") }
                ids.imdb?.takeIf { it.isNotBlank() }?.let { add("imdb.${it.trim()}") }
                ids.tvdb?.takeIf { it.isNotBlank() }?.let { add("tvdb.${it.trim()}") }
            }
            return Query.ByProviderId(values, kind.includeItemType)
        }
        val term = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Query.ByTitle(term, if (year != null) listOf(year - 1, year, year + 1) else emptyList(), kind.includeItemType)
    }

    /** The candidates whose `ProviderIds` agree with [ids] on at least one id (keys are case-insensitive: `Tmdb`/`tmdb`). */
    fun verify(candidates: List<ItemDto>, ids: ExternalIds): List<ItemDto> = candidates.filter { c ->
        val provider = c.providerIds.entries.associate { (k, v) -> k.lowercase() to v?.trim().orEmpty() }
        fun same(ours: String?, theirs: String?) = !ours.isNullOrBlank() && !theirs.isNullOrBlank() && ours.trim().equals(theirs, ignoreCase = true)
        same(ids.tmdb, provider["tmdb"]) || same(ids.imdb, provider["imdb"]) || same(ids.tvdb, provider["tvdb"])
    }
}
