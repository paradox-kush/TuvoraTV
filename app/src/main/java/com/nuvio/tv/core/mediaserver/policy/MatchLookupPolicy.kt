package com.nuvio.tv.core.mediaserver.policy

import com.nuvio.tv.core.mediaserver.client.mediabrowser.ItemDto
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserDialect

/**
 * The matched lane's lookup (design 5.6): a TMDB/IMDB title page asks "does this server have it?".
 *  - Emby filters server-side: `/Users/{uid}/Items?AnyProviderIdEquals=tmdb.123&Fields=ProviderIds`;
 *  - Jellyfin has NO provider-id filter (jellyfin#1990, only `hasTmdbId`/`hasImdbId`), so it is a title
 *    search (`SearchTerm` + `Years` +-1) VERIFIED client-side against `ProviderIds.Tmdb/Imdb` - a retitled
 *    item is a known false negative, which the per-server match cache (TTL) mitigates.
 * The pure query + verification (J1a); P3 (J2) adds [queries] (the ordered requests for one title) and
 * [MatchCache] (per-server external id -> item ids with a TTL), driven by `MediaServerMatchLane`.
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

    /** What TMDB/the title page knows about the title being looked up. [tmdb]/[imdb] are the ids a server item can carry. */
    data class TitleFacts(
        val ids: ExternalIds,
        val primary: String? = null,
        val original: String? = null,
        val alternatives: List<String> = emptyList(),
        val year: Int? = null,
    )

    /** The most title searches one lookup may issue (Jellyfin has no id filter): primary, original, then one alternative/translation. */
    const val MAX_TITLE_QUERIES = 3

    /**
     * The ordered requests that can find [facts] on a [dialect] server: Emby = ONE exact id filter; Jellyfin = a title
     * search per distinct candidate title (primary, original, a TMDB alternative/translation - a library often holds the
     * localised or original name), each followed by [verify]. Empty when nothing can be asked (no ids, or no title for Jellyfin).
     */
    fun queries(dialect: MediaBrowserDialect, kind: ItemKind, facts: TitleFacts): List<Query> {
        if (!facts.ids.hasAny) return emptyList()
        if (dialect.supportsProviderIdFilter) return listOfNotNull(query(dialect, kind, facts.ids, facts.primary, facts.year))
        val seen = mutableSetOf<String>()
        return (listOf(facts.primary, facts.original) + facts.alternatives.take(1))
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .filter { seen.add(it.lowercase()) }
            .take(MAX_TITLE_QUERIES)
            .mapNotNull { query(dialect, kind, facts.ids, it, facts.year) }
    }
}
