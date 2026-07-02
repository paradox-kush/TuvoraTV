package com.nuvio.tv.core.iptv.match

import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.Stream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a TMDB movie/episode into playable Xtream [Stream]s for one account — the bridge
 * that lets IPTV VOD show up next to addon/debrid streams on TMDB-driven detail screens.
 * Returns empty (never throws) when the account doesn't carry the title.
 */
@Singleton
class XtreamStreamSource @Inject constructor(
    private val client: XtreamClient,
    private val resolver: XtreamTmdbResolver,
    private val index: XtreamMatchIndex,
    private val tmdbService: TmdbService,
) {
    suspend fun streamsFor(acc: XtreamAccount, type: String, videoId: String, season: Int?, episode: Int?): List<Stream> {
        val kind = when (type) {
            "movie" -> MatchKind.MOVIE
            "series", "tv" -> MatchKind.SERIES
            else -> return emptyList()
        }
        val tmdbId = tmdbService.ensureTmdbId(videoId, type)?.toIntOrNull() ?: run {
            android.util.Log.w("XtreamStreamSource", "skip $videoId: no TMDB id (missing API key or unknown id)")
            return emptyList()
        }
        val titles = tmdbService.titleBundle(tmdbId, type) ?: run {
            android.util.Log.w("XtreamStreamSource", "skip tmdb=$tmdbId: title bundle unavailable (API key/network)")
            return emptyList()
        }
        val match = resolver.resolve(acc, kind, tmdbId, titles) ?: return emptyList()

        return when (kind) {
            MatchKind.MOVIE -> {
                // id-tagged catalogs often carry several editions (4K/HD/language) of the
                // same film — surface them all as separate streams
                val editions = index.byTmdb(acc.id, kind, tmdbId).ifEmpty { listOf(match.item) }
                editions.map { item ->
                    // label with the panel's own catalog name — carries 4K/NF/language tags
                    xtreamStream(
                        acc = acc,
                        label = item.name,
                        url = client.buildStreamUrl(acc, "movie", item.sid, item.ext ?: "mp4"),
                    )
                }
            }
            MatchKind.SERIES -> {
                val s = season ?: return emptyList()
                val e = episode ?: return emptyList()
                val detail = client.seriesInfo(acc, match.item.sid).getOrNull() ?: return emptyList()
                detail.episodes.filter { it.season == s && it.episodeNum == e }.map { ep ->
                    xtreamStream(acc = acc, label = "S${s}E${e} · ${ep.title}", url = ep.streamUrl)
                }
            }
        }
    }

    private fun xtreamStream(acc: XtreamAccount, label: String, url: String) = Stream(
        name = label,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = acc.name,
        addonLogo = null,
    )
}
