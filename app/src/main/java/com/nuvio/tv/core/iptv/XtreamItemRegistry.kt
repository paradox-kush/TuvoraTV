package com.nuvio.tv.core.iptv

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Stream
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

    companion object {
        const val PREFIX = "xtream:"
        /** Stable, collision-free per-playlist content ids. accountId may contain ':'/'|' — fine, it's only a map key + prefix check. */
        fun vodId(accountId: String, streamId: Int): String = "$PREFIX$accountId:vod:$streamId"
        fun seriesId(accountId: String, seriesId: Int): String = "$PREFIX$accountId:series:$seriesId"
        fun episodeId(accountId: String, episodeId: String): String = "$PREFIX$accountId:episode:$episodeId"
        fun liveId(accountId: String, streamId: Int): String = "$PREFIX$accountId:live:$streamId"
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
