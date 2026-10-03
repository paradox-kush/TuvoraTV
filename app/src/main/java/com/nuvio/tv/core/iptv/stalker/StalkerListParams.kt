package com.nuvio.tv.core.iptv.stalker

/**
 * The query for one `get_ordered_list` page — pure, so the dialect rules are pinned by tests.
 *
 * B02: the category travels in a DIFFERENT parameter per section, and getting it wrong is silent:
 *  - `itv` filters by `genre` (stock Ministra `itv.class.php`, Xtream-Codes `portal.php` itv branch).
 *  - `vod` / `series` filter by `category`. On stock Ministra (`server/lib/vod.class.php` getData)
 *    a `genre` that is neither empty nor `*` ADDS a filter on the movie's genre ids
 *    (`cat_genre_id_1..4 = intval(genre)`), so sending the category id as `genre` too kept only
 *    films whose GENRE id happened to equal the CATEGORY id — every Movies row empty ("Movies keep
 *    loading"). Xtream-Codes panels read only `category` here, which is why this never showed on
 *    them or on our mock. So for vod/series `genre` is always `*` — the stock "no genre filter"
 *    value (iptvnator sends `0`, equally inert).
 */
internal object StalkerListParams {

    fun forPage(type: String, categoryId: String?, search: String?, page: Int): Map<String, String> = buildMap {
        val category = categoryId ?: "*"
        put("type", type)
        put("action", "get_ordered_list")
        if (type == "itv") {
            put("genre", category)
        } else {
            put("genre", "*")
            put("category", category)
        }
        search?.let { put("search", it) }
        put("p", page.toString())
        put("sortby", "number")
    }
}
