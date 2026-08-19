package com.nuvio.tv.core.iptv

import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class XtreamKind { VOD, SERIES, LIVE }

/** A resolved Xtream catalog item, keyed by its `xtream:` content id. */
data class XtreamResolvedItem(
    val id: String,
    val type: ContentType,
    val name: String,
    val poster: String?,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val runtime: String? = null,
    val streamUrl: String,
    val kind: XtreamKind = XtreamKind.VOD,
    // For on-detail TMDB enrichment + series episode fetch (get_vod_info / get_series_info).
    val accountId: String = "",
    val streamId: Int = 0
)

/**
 * In-memory map of `xtream:` content id -> resolved item, populated as Xtream catalogs load.
 * The meta + stream repositories read from it to short-circuit resolution for Xtream ids
 * (HYBRID LANE: Xtream content resolves its own meta + single direct stream, bypassing
 * addon/debrid). No id parsing needed — the id is just a unique key checked by [PREFIX].
 *
 * ponytail: a process-lifetime cache. Deep-linking to an xtream id never browsed this session
 * misses; the upgrade path is a get_vod_info fallback fetch (Phase D).
 */
@Singleton
class XtreamItemRegistry @Inject constructor() {
    private val items = ConcurrentHashMap<String, XtreamResolvedItem>()

    fun register(item: XtreamResolvedItem) { items[item.id] = item }
    fun get(id: String): XtreamResolvedItem? = items[id]
    fun isXtreamId(id: String): Boolean = id.startsWith(PREFIX)

    /** Drop everything (playlist edited: cached stream URLs embed the old server/creds). */
    fun clear() { items.clear() }

    /** Parsed pieces of an `xtream:` content id, used to rebuild an item on a cache miss. */
    data class ParsedId(val accountId: String, val kind: String, val streamId: String)

    companion object {
        const val PREFIX = "xtream:"
        /** Prefix shared by every content id of one account — used for playlist-edit id migration. */
        fun accountPrefix(accountId: String): String = "$PREFIX$accountId:"
        /** Stable, collision-free per-playlist content ids. accountId may contain ':'/'|' — fine, it's only a map key + prefix check. */
        fun vodId(accountId: String, streamId: Int): String = "$PREFIX$accountId:vod:$streamId"
        fun seriesId(accountId: String, seriesId: Int): String = "$PREFIX$accountId:series:$seriesId"
        fun episodeId(accountId: String, episodeId: String): String = "$PREFIX$accountId:episode:$episodeId"
        fun liveId(accountId: String, streamId: Int): String = "$PREFIX$accountId:live:$streamId"

        private val KIND_MARKERS = listOf("vod", "series", "episode", "live")

        /**
         * Inverse of the id builders. Both the accountId ("http://host:port|user") and a Stalker
         * episode streamId ("seriesId:season:epNum") may contain ':', so the split anchors on the
         * RIGHTMOST known ":kind:" marker — everything before it is the accountId, everything
         * after it is the streamId. (Rightmost, because the kind marker always follows the
         * accountId, and a streamId — digits/colon-digits — can never contain a kind marker.)
         * Returns null if the id isn't a well-formed xtream id.
         */
        fun parseId(id: String): ParsedId? {
            if (!id.startsWith(PREFIX)) return null
            val body = id.removePrefix(PREFIX)
            var markerIdx = -1
            var kind: String? = null
            for (candidate in KIND_MARKERS) {
                val idx = body.lastIndexOf(":$candidate:")
                if (idx > markerIdx) {
                    markerIdx = idx
                    kind = candidate
                }
            }
            if (markerIdx <= 0 || kind == null) return null
            val accountId = body.substring(0, markerIdx)
            val streamId = body.substring(markerIdx + kind.length + 2)
            if (accountId.isEmpty() || streamId.isEmpty()) return null
            return ParsedId(accountId, kind, streamId)
        }
        /** True for a live-channel content id. accountId can't be parsed (may contain ':'),
         *  but the kind segment is unambiguous: vod/series/episode/live are the only suffixes. */
        fun isLiveContentId(id: String): Boolean = id.startsWith(PREFIX) && id.contains(":live:")
    }
}

/** Builds a full [Meta] for Nuvio's native detail screen from a resolved Xtream item. */
fun XtreamResolvedItem.toMeta(): Meta = Meta(
    id = id,
    type = type,
    name = name,
    poster = poster,
    posterShape = PosterShape.POSTER,
    background = background,
    logo = null,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    runtime = runtime,
    director = emptyList(),
    cast = emptyList(),
    videos = emptyList(),
    country = null,
    awards = null,
    language = null,
    links = emptyList()
)

/** The single direct stream for an Xtream item, presented as one addon group. */
fun XtreamResolvedItem.toAddonStreams(): List<AddonStreams> = listOf(
    AddonStreams(
        addonName = "Xtream IPTV",
        addonLogo = null,
        streams = listOf(
            Stream(
                name = "Direct",
                title = name,
                description = null,
                url = streamUrl,
                ytId = null,
                infoHash = null,
                fileIdx = null,
                externalUrl = null,
                behaviorHints = null,
                addonName = "Xtream IPTV",
                addonLogo = null
            )
        )
    )
)

/**
 * Rebuilds and re-registers a resolved item for a saved/deep-linked `xtream:` id that was
 * never browsed this session (so it missed the in-memory registry). Only the account and the
 * id are needed: VOD/live get a directly-built stream URL; series carries an empty stream url
 * and its episodes are resolved later by get_series_info. Returns null only if the id is
 * malformed or the account is gone — the caller uses that to decide "no longer available".
 */
suspend fun XtreamItemRegistry.rebuildFromId(
    id: String,
    store: XtreamAccountStore,
    clientFactory: IptvClientFactory
): XtreamResolvedItem? {
    val parsed = XtreamItemRegistry.parseId(id) ?: return null
    val account = runCatching { store.accounts.first() }.getOrNull()
        ?.firstOrNull { it.id == parsed.accountId } ?: return null
    // Episodes carry a String episode id and need get_series_info to resolve their URL;
    // they're only reached via a series meta build (which registers them), so a direct
    // episode miss isn't rebuildable here.
    if (parsed.kind == "episode") return null
    val streamId = parsed.streamId.toIntOrNull() ?: return null
    // Rebuild DISPLAY metadata only; the stream URL stays blank and is resolved FRESH at play time
    // (StreamRepositoryImpl.resolveXtreamPlayStreams -> refreshIptvStreamUrl). This is the app's
    // standard "browse-time items carry a blank stream URL" contract — and eager resolution here
    // was pathological for Stalker: resolveStreamUrl on a cold registry miss re-pages the whole VOD
    // catalog (get_ordered_list until the id is found, ~23 requests/item) just to fill a field the
    // meta path discards and the play path re-mints anyway (Stalker create_link URLs are single-use).
    // That scan fired for every Stalker Continue-Watching item on EVERY cold start. See anti-jank F1.
    val item = when (parsed.kind) {
        "series" -> XtreamResolvedItem(
            id = id, type = ContentType.SERIES, name = "", poster = null,
            streamUrl = "", kind = XtreamKind.SERIES,
            accountId = account.id, streamId = streamId
        )
        "live" -> XtreamResolvedItem(
            id = id, type = ContentType.TV, name = "", poster = null,
            streamUrl = "", kind = XtreamKind.LIVE,
            accountId = account.id, streamId = streamId
        )
        else -> XtreamResolvedItem( // "vod"
            id = id, type = ContentType.MOVIE, name = "", poster = null,
            streamUrl = "", kind = XtreamKind.VOD,
            accountId = account.id, streamId = streamId
        )
    }
    register(item)
    return item
}
