package com.nuvio.tv.core.iptv.match

import com.nuvio.tv.core.iptv.XtreamAccount

/**
 * Which IPTV items a playlist may OFFER outside its own browse screen: as a source in a title's
 * source list, and as a search hit (B19).
 *
 * Only the playlist's in-app settings count: its content-type toggles ([XtreamAccount.typeEnabled])
 * and its per-type category include list ([XtreamAccount.allowsCategory]: null = all, [] = none,
 * a list = only those ids). Category hides made on the website or a device (the per-profile overlay) do
 * NOT filter a title's sources; search leaves hidden items out separately in XtreamSearchIndex (F01,
 * product decision 2026-09-27).
 *
 * Callers filter with [keep] BEFORE any display/fan-out cap (`take(n)`), so hidden items can never
 * crowd allowed ones out of the cap. Filtering happens after TMDB resolution, never inside it: the
 * resolver's tmdb→sid mappings sync to every device on the provider and must stay selection-neutral.
 */
object IptvSourceCategoryPolicy {

    /** The playlist content type ([XtreamAccount.TYPE_MOVIES] …) an index kind belongs to. */
    fun typeOf(kind: MatchKind): String = when (kind) {
        MatchKind.MOVIE -> XtreamAccount.TYPE_MOVIES
        MatchKind.SERIES -> XtreamAccount.TYPE_SERIES
        MatchKind.LIVE -> XtreamAccount.TYPE_LIVE
    }

    /**
     * True when the playlist can offer anything of [type]: the type is switched on and its selection
     * is not the explicit empty "none". A false here lets callers skip all work (network included).
     */
    fun offers(acc: XtreamAccount, type: String): Boolean =
        acc.typeEnabled(type) && acc.categorySelections.forType(type)?.isEmpty() != true

    /**
     * [items] the playlist may offer for [type], in their original order. An item whose category is
     * unknown (null) is kept only while the type has no partial selection, the same rule browse and
     * live search already apply.
     */
    fun <T> keep(acc: XtreamAccount, type: String, items: List<T>, categoryOf: (T) -> String?): List<T> {
        if (!offers(acc, type)) return emptyList()
        if (acc.categorySelections.forType(type) == null) return items
        return items.filter { acc.allowsCategory(type, categoryOf(it)) }
    }

    /** [keep], then the cap: the cap counts allowed items only. */
    fun <T> keepCapped(acc: XtreamAccount, type: String, items: List<T>, cap: Int, categoryOf: (T) -> String?): List<T> =
        keep(acc, type, items, categoryOf).take(cap)

    /**
     * How many rows a capped local query should read so that filtering happens before the cap.
     * With no partial selection nothing is filtered out, so [cap] rows are enough; with one, the
     * query reads a wider window and [keepCapped] trims to [cap] after filtering.
     */
    fun scanLimit(acc: XtreamAccount, type: String, cap: Int): Int =
        if (acc.categorySelections.forType(type) == null) cap else maxOf(cap, FILTERED_SCAN_LIMIT)

    /** Rows scanned per account and type when a partial category selection must filter a search. */
    const val FILTERED_SCAN_LIMIT = 1_000
}
