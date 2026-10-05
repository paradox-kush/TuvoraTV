package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.contracts.IptvSubtitleIds
import com.nuvio.tv.core.iptv.match.MatchKind
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.player.AddonSubtitleIdPolicy
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.XtreamAccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F17: the public id OpenSubtitles (any Stremio subtitle add-on) knows an Xtream movie/series by.
 * The item's TMDB id comes from the panel's bulk list (local match index) or — T5 (W2 device pass):
 * when the index has no row for it yet (it is built in the background, so a playlist added this
 * session or a panel still indexing had none, and no subtitle was ever requested) — from the panel's
 * own get_vod_info / get_series_info. TMDB's external-ids endpoint turns it into an IMDb id (cached in
 * TmdbService). Only the TMDB id is sent to TMDB — never the provider id; the panel calls go to the
 * user's own provider. M3U/Stalker items, items without a TMDB id or a failed lookup -> null (no request).
 */
@Singleton
class IptvSubtitleIdResolver internal constructor(
    private val indexTmdb: suspend (accountId: String, isSeries: Boolean, streamId: Int) -> Int?,
    private val panelTmdb: suspend (accountId: String, isSeries: Boolean, streamId: Int) -> Int?,
    private val imdbOf: suspend (tmdbId: Int, isSeries: Boolean) -> String?,
) : IptvSubtitleIds {

    @Inject
    constructor(
        matchIndex: XtreamMatchIndex,
        tmdbService: TmdbService,
        accountStore: XtreamAccountStore,
        clientFactory: IptvClientFactory,
        xtreamClient: XtreamClient,
    ) : this(
        indexTmdb = { accountId, isSeries, sid ->
            matchIndex.item(accountId, if (isSeries) MatchKind.SERIES else MatchKind.MOVIE, sid)?.tmdb
        },
        panelTmdb = { accountId, isSeries, sid ->
            accountStore.accounts.first().firstOrNull { it.id == accountId }
                ?.takeIf { it.isXtream() }
                ?.let { acc ->
                    if (isSeries) clientFactory.clientFor(acc).seriesInfo(acc, sid).getOrNull()?.tmdbId
                    else xtreamClient.vodTmdbId(acc, sid).getOrNull()
                }
        },
        imdbOf = { tmdbId, isSeries -> tmdbService.tmdbToImdb(tmdbId, if (isSeries) "tv" else "movie") },
    )

    override suspend fun publicSubtitleVideoId(contentId: String, season: Int?, episode: Int?): String? {
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return null
        val sid = parsed.streamId.toIntOrNull() ?: return null
        val isSeries = when (parsed.kind) {
            "vod" -> false
            "series" -> true
            else -> return null
        }
        val tmdbId = safely { indexTmdb(parsed.accountId, isSeries, sid) }?.takeIf { it > 0 }
            ?: safely { panelTmdb(parsed.accountId, isSeries, sid) }?.takeIf { it > 0 }
            ?: return null
        val imdbId = safely { imdbOf(tmdbId, isSeries) }
        return AddonSubtitleIdPolicy.publicVideoId(imdbId, isSeries, season, episode)
    }

    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
