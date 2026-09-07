package com.nuvio.tv.core.iptv

/**
 * Pure decision for the Live guide's synthetic "Favorites" row.
 *
 * A live favourite is a synced Library ★ item stored under `xtream:<baseUrl|username>:live:<streamId>`
 * (type "tv"), identical on every device. It DOES sync into TV's library — the ★ lights up — but the
 * device-local [com.nuvio.tv.data.local.XtreamLiveStore] ref (name/logo/URL baked in at favourite time)
 * is NEVER synced. A favourite made on another device therefore has no local ref on TV, so the old
 * `mapNotNull { refFor(id)?… }` silently dropped it and the Favorites row came up empty.
 *
 * This policy builds a row for EVERY live favourite under the account, using the library entry's own
 * name/logo, and treats the local ref as an optional fast-path (its name/logo/streamUrl win when it is
 * present). Playback resolves the URL from the content id, so an empty [Row.streamUrl] is fine.
 *
 * No Android / DataStore deps — the caller passes plain values through the [localRef] and [streamIdOf]
 * lambdas — so it unit-tests in isolation (house pattern: RadarLiveRefreshPolicy, LivePlaybackFreezePolicy).
 */
object LiveFavoritesRowPolicy {

    /** A synced Library ★ entry projected to the fields the favourites row needs. */
    data class FavoriteEntry(val id: String, val name: String, val logo: String?)

    /** The optional device-local ref: name/logo/URL baked in when THIS device favourited the channel. */
    data class LocalRef(val name: String, val logo: String?, val streamUrl: String)

    /** One favourites-row output; the ViewModel maps it to a GuideChannel. */
    data class Row(
        val contentId: String,
        val name: String,
        val logo: String?,
        val streamUrl: String,
        val streamId: Int,
    )

    fun rows(
        entries: List<FavoriteEntry>,
        accountPrefix: String,
        localRef: (String) -> LocalRef?,
        streamIdOf: (String) -> Int,
    ): List<Row> =
        entries
            .filter { XtreamItemRegistry.isLiveContentId(it.id) && it.id.startsWith(accountPrefix) }
            .map { entry ->
                // A row for EVERY live favourite under the account. The device-local ref is only an
                // optional fast-path: when present its name/logo/URL win; when absent (a favourite made
                // on another device) the library entry's own name/logo carry the row and the URL is
                // resolved from the content id at play time.
                when (val ref = localRef(entry.id)) {
                    null -> Row(entry.id, entry.name, entry.logo, streamUrl = "", streamId = streamIdOf(entry.id))
                    else -> Row(entry.id, ref.name, ref.logo, ref.streamUrl, streamIdOf(entry.id))
                }
            }
}
