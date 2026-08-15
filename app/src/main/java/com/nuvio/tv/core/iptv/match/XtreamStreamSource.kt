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
    private val stalkerClient: com.nuvio.tv.core.iptv.stalker.StalkerClient,
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
        // Stalker has no match index — the resolver builds one from player_api bulk lists a portal
        // doesn't have, and paging its 63k-movie catalog is what got a portal to ban us. Instead ask
        // the PORTAL to find the title (get_ordered_list&search=, 1-2 requests).
        if (acc.sourceType == XtreamAccount.SOURCE_STALKER) return stalkerStreams(acc, kind, titles, season, episode)

        val match = resolver.resolve(acc, kind, tmdbId, titles) ?: return emptyList()

        return when (kind) {
            MatchKind.LIVE -> emptyList()   // live never TMDB-resolves; the guide plays directly
            MatchKind.MOVIE -> {
                // catalogs carry several editions (4K/HD/language) of the same film —
                // surface them all: by shared tmdb id where the panel provides ids, else
                // by shared normalized name (year-guarded; the verified match stays first)
                val editions = index.byTmdb(acc.id, kind, tmdbId)
                    .ifEmpty { sameNameEditions(acc.id, kind, match.item, titles.year) }
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
                val editions = index.byTmdb(acc.id, kind, tmdbId)
                    .ifEmpty { sameNameEditions(acc.id, kind, match.item, titles.year) }
                    .take(MAX_SERIES_EDITIONS) // one get_series_info per edition — bound it
                editions.flatMap { ed ->
                    val detail = client.seriesInfo(acc, ed.sid).getOrNull() ?: return@flatMap emptyList<Stream>()
                    detail.episodes.filter { it.season == s && it.episodeNum == e }.map { ep ->
                        // edition catalog name as title so language variants are tellable apart
                        xtreamStream(acc = acc, label = "S${s}E${e} · ${ep.title}", url = ep.streamUrl, title = ed.name)
                    }
                }
            }
        }
    }

    /**
     * Stalker VOD for a TMDB title, via the portal's own search. Panels ship no tmdb ids, so the match
     * is name-key equality + a year guard — the same rule [sameNameEditions] uses for id-less panels.
     *
     * Series resolve the real season/episode: a portal models a series as a two-level tree and
     * StalkerClient.seriesInfo walks it, so a TMDB S/E maps exactly.
     */
    private suspend fun stalkerStreams(
        acc: XtreamAccount,
        kind: MatchKind,
        titles: com.nuvio.tv.core.tmdb.TmdbTitleBundle,
        season: Int?,
        episode: Int?,
    ): List<Stream> {
        val query = titles.primary?.takeIf { it.isNotBlank() } ?: return emptyList()
        val wantKeys = listOfNotNull(titles.primary, titles.original)
            .map { TitleNormalizer.normKey(it) }.filter { it.isNotEmpty() }.toSet()
        if (wantKeys.isEmpty()) return emptyList()

        return when (kind) {
            MatchKind.LIVE -> emptyList()   // live never TMDB-resolves; the guide plays directly
            MatchKind.MOVIE -> stalkerClient.searchMovies(acc, query)
                .filter { TitleNormalizer.normKey(it.name) in wantKeys }
                .filter { yearCompatible(TitleNormalizer.yearOf(it.name), titles.year) }
                .take(MAX_STALKER_EDITIONS)   // a catalog carries 4K/HD/language cuts of one film
                .map { movie ->
                    // DEFERRED — see [deferredMovie]: create_link runs when the viewer picks this
                    // edition, not while the list is merely being built.
                    xtreamStream(acc = acc, label = movie.name, url = deferredMovie(acc, movie.streamId, movie.name))
                }

            MatchKind.SERIES -> {
                val s = season ?: return emptyList()
                val e = episode ?: return emptyList()
                // Year is NOT guarded here: a panel names a series "Breaking Bad", rarely with a year,
                // and TMDB's year is the FIRST-air year — guarding would drop later-season matches.
                stalkerClient.searchSeries(acc, query)
                    .filter { TitleNormalizer.normKey(it.name) in wantKeys }
                    .take(MAX_STALKER_EDITIONS)   // language cuts ("Breaking Bad (Hindi)") are separate
                    .map { series ->
                        val url = deferredEpisode(acc, series.seriesId, s, e)
                        // The label IS what's shown (name wins; title is only a fallback), so the
                        // portal's own name has to live there, exactly like the movie branch. Stalker
                        // episode titles are generic ("Episode 7"), so the series name is what
                        // distinguishes editions ("Breaking Bad (Hindi)").
                        xtreamStream(acc = acc, label = "${series.name} · S${s}E${e}", url = url, title = series.name)
                    }
            }
        }
    }

    /**
     * Scheme for a Stalker source whose play link has NOT been minted yet.
     *
     * A MAG box calls create_link once, when the viewer presses play. We used to call it while
     * merely BUILDING the source list — up to [MAX_STALKER_EDITIONS] per account, so one movie with
     * three portals fired a dozen. Panels register a session/connection per created link and lines
     * are commonly sold with max_connections=1, so the eager calls could occupy the very slot the
     * real playback then needed and the stream came back 401. Listing is now free;
     * [resolveDeferredUrl] mints exactly one link, for the edition actually chosen.
     */
    private fun deferredMovie(acc: XtreamAccount, streamId: Int, name: String): String =
        "$DEFERRED_PREFIX${acc.id}|movie|$streamId|${name.replace('|', ' ')}"

    private fun deferredEpisode(acc: XtreamAccount, seriesId: Int, season: Int, episode: Int): String =
        "$DEFERRED_PREFIX${acc.id}|episode|$seriesId|$season|$episode"

    /** Mints the real play link for a [isDeferred] URL, or null when it can't be issued.
     *  [forceFresh] — the 401-refresh path only: bypass a static-cmd verdict (the static URL just
     *  died); the normal pick-time resolve keeps the policy's static shortcut. */
    suspend fun resolveDeferredUrl(url: String, accounts: List<XtreamAccount>, forceFresh: Boolean = false): String? {
        if (!isDeferred(url)) return url
        val parts = url.removePrefix(DEFERRED_PREFIX).split("|")
        // accountId itself contains '|' ("stalker|http://host|MAC"), so anchor on the kind marker.
        val kindIdx = parts.indexOfFirst { it == "movie" || it == "episode" }
        if (kindIdx <= 0) return null
        val accountId = parts.subList(0, kindIdx).joinToString("|")
        val acc = accounts.firstOrNull { it.id == accountId } ?: return null
        return when (parts[kindIdx]) {
            "movie" -> parts.getOrNull(kindIdx + 1)?.toIntOrNull()
                ?.let { stalkerClient.resolveStreamUrl(acc, "movie", it, forceFresh) }
            "episode" -> {
                val seriesId = parts.getOrNull(kindIdx + 1)?.toIntOrNull() ?: return null
                val season = parts.getOrNull(kindIdx + 2)?.toIntOrNull() ?: return null
                val ep = parts.getOrNull(kindIdx + 3)?.toIntOrNull() ?: return null
                stalkerClient.resolveEpisodeUrl(acc, seriesId, season, ep)
            }
            else -> null
        }
    }

    private fun yearCompatible(a: Int?, b: Int?): Boolean =
        a == null || b == null || (if (a > b) a - b else b - a) <= 1

    /**
     * Editions of the same title on panels that ship no tmdb ids: items sharing the matched
     * item's normalized name key, year-compatible with the target. The verified match leads.
     */
    private suspend fun sameNameEditions(provider: String, kind: MatchKind, matched: IndexedItem, targetYear: Int?): List<IndexedItem> {
        val key = TitleNormalizer.normKey(matched.name)
        if (key.isEmpty()) return listOf(matched)
        val siblings = index.probe(provider, kind, key).filter {
            it.year == null || targetYear == null || (if (it.year > targetYear) it.year - targetYear else targetYear - it.year) <= 1
        }
        return (listOf(matched) + siblings).distinctBy { it.sid }
    }

    private fun xtreamStream(acc: XtreamAccount, label: String, url: String, title: String? = null) = Stream(
        name = label,
        title = title,
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

    companion object {
        private const val MAX_SERIES_EDITIONS = 5
        private const val MAX_STALKER_EDITIONS = 5
        private const val DEFERRED_PREFIX = "stalker-deferred:"

        /** True for a matched Stalker source that still needs its create_link minted. */
        fun isDeferred(url: String?): Boolean = url != null && url.startsWith(DEFERRED_PREFIX)
    }
}
