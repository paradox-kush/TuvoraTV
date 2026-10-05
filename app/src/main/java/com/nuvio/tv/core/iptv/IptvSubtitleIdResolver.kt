package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.contracts.IptvSubtitleIds
import com.nuvio.tv.core.iptv.match.MatchKind
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.player.AddonSubtitleIdPolicy
import com.nuvio.tv.core.tmdb.TmdbService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F17: the public id OpenSubtitles (any Stremio subtitle add-on) knows an Xtream movie/series by.
 * The panel's bulk list carries a TMDB id per item (local match index); TMDB's external-ids endpoint
 * turns it into an IMDb id (cached in TmdbService). Only the TMDB id is sent to TMDB — never the
 * provider id. M3U/Stalker items, items without a TMDB id or a failed lookup -> null (no request).
 */
@Singleton
class IptvSubtitleIdResolver @Inject constructor(
    private val matchIndex: XtreamMatchIndex,
    private val tmdbService: TmdbService,
) : IptvSubtitleIds {
    override suspend fun publicSubtitleVideoId(contentId: String, season: Int?, episode: Int?): String? {
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return null
        val sid = parsed.streamId.toIntOrNull() ?: return null
        val (kind, isSeries) = when (parsed.kind) {
            "vod" -> MatchKind.MOVIE to false
            "series" -> MatchKind.SERIES to true
            else -> return null
        }
        val tmdbId = runCatching { matchIndex.item(parsed.accountId, kind, sid)?.tmdb }.getOrNull()
            ?.takeIf { it > 0 } ?: return null
        val imdbId = runCatching { tmdbService.tmdbToImdb(tmdbId, if (isSeries) "tv" else "movie") }.getOrNull()
        return AddonSubtitleIdPolicy.publicVideoId(imdbId, isSeries, season, episode)
    }
}
