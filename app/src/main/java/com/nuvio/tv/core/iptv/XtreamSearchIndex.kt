package com.nuvio.tv.core.iptv

import android.util.Log
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.identity.IptvIdentity
import com.nuvio.tv.core.iptv.overlay.CategoryOverlay
import com.nuvio.tv.core.iptv.overlay.IptvHiddenItemsPolicy
import com.nuvio.tv.core.iptv.overlay.IptvOverlayRepository
import com.nuvio.tv.core.iptv.overlay.OverlaySnapshot
import com.nuvio.tv.core.iptv.match.IptvSourceCategoryPolicy
import com.nuvio.tv.core.iptv.match.MatchKind
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.iptv.match.XtreamTmdbResolver
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.domain.model.ContentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets Xtream content show up in the platform search. Xtream panels have NO search
 * endpoint. Movies + series are served from the persistent SQLite match index (the same
 * one TMDB->stream matching builds: FULL catalog — no 40k RAM cap — 24h TTL, survives
 * restarts). Live channels aren't in that index, so they keep the fetch-once RAM path.
 */
@Singleton
class XtreamSearchIndex @Inject constructor(
    private val store: XtreamAccountStore,
    private val clientFactory: IptvClientFactory,
    private val xtreamClient: XtreamClient,   // for the Xtream-only match-index stream URLs
    private val registry: XtreamItemRegistry,
    private val matchIndex: XtreamMatchIndex,
    private val resolver: XtreamTmdbResolver,
    private val contentDb: IptvContentDb,
    private val overlayRepository: IptvOverlayRepository,
) {
    /** A search match, ready to become a MetaPreview + click target. */
    data class Hit(
        val contentId: String,
        val name: String,
        val poster: String?,
        val isLive: Boolean,
        val streamUrl: String?,
        val detailType: String
    )

    data class Results(val channels: List<Hit>, val movies: List<Hit>, val series: List<Hit>)

    private val liveCache = ConcurrentHashMap<String, List<XtreamChannel>>()
    /** "accountId|type" -> category names, read only when the overlay hides some category (F01). */
    private val categoryNameCache = ConcurrentHashMap<String, List<IptvHiddenItemsPolicy.NamedCategory>>()

    /** Drop one account's session cache (account removed or its credentials replaced). */
    fun evict(accountId: String) {
        liveCache.remove(accountId)
        categoryNameCache.keys.removeAll { it.startsWith("$accountId|") }
    }

    /** Category ids of [type] the overlay hides for [acc]; names are fetched once per session. */
    private suspend fun hiddenCategoryIds(acc: XtreamAccount, type: String, overlay: Map<String, CategoryOverlay>): Set<String> {
        if (overlay.values.none { it.hidden }) return emptySet()
        val key = "${acc.id}|$type"
        val names = categoryNameCache[key] ?: run {
            val client = clientFactory.clientFor(acc)
            when (type) {
                XtreamAccount.TYPE_LIVE -> client.liveCategories(acc)
                XtreamAccount.TYPE_MOVIES -> client.vodCategories(acc)
                else -> client.seriesCategories(acc)
            }.getOrDefault(emptyList())
                .map { IptvHiddenItemsPolicy.NamedCategory(it.id, it.name) }
                .also { if (it.isNotEmpty()) categoryNameCache[key] = it }
        }
        return IptvHiddenItemsPolicy.hiddenCategoryIds(acc.id, type, names, overlay)
    }

    /** F01: hits whose group the viewer hid are left out of search. */
    private suspend fun <T> withoutHiddenGroups(
        acc: XtreamAccount, type: String, overlay: OverlaySnapshot, hits: List<T>, categoryOf: (T) -> String?,
    ): List<T> {
        if (hits.isEmpty()) return hits
        val hidden = hiddenCategoryIds(acc, type, overlay.categories)
        if (hidden.isEmpty()) return hits
        return hits.filter { val c = categoryOf(it); c == null || c !in hidden }
    }

    /** F01: live hits ranked by [IptvChannelSearchPolicy], without hidden channels or channels of hidden groups. */
    private suspend fun visibleLiveHits(
        acc: XtreamAccount, q: String, overlay: OverlaySnapshot, candidates: Sequence<XtreamChannel>,
    ): List<XtreamChannel> = IptvHiddenItemsPolicy.visibleHits(
        IptvChannelSearchPolicy.search(candidates.filter { IptvChannelSearchPolicy.matches(q, it.name) }.toList(), q) { it.name },
        hiddenCategoryIds(acc, XtreamAccount.TYPE_LIVE, overlay.categories),
        overlay.channels,
        categoryOf = { it.categoryId },
        entityOf = { IptvIdentity.entityId(acc.id, it.name, it.epgChannelId) },
    )

    private suspend fun ensureLoaded() = coroutineScope {
        val accounts = store.accounts.first().filter { it.enabled }
        accounts.map { acc ->
            async {
                // M3U (url or file): the whole catalog (live/vod/series) is in IptvContentDb once
                // ingested — no per-type RAM cache or match index. Just ensure the ingest exists
                // (bounded so a cold 192MB parse doesn't stall a keystroke; it fills in on a later
                // search). A file playlist with no local copy skips cleanly inside ensureIngested.
                if (acc.isM3UBacked()) {
                    withTimeoutOrNull(INDEX_WAIT_MS) { clientFactory.m3u().ensureIngested(acc) }
                    return@async
                }
                // Disabled content types and explicit-empty selections are skipped entirely
                // (not fetched, not indexed).
                if (acc.searchIncludesType(XtreamAccount.TYPE_LIVE) && !liveCache.containsKey(acc.id)) {
                    val live = clientFactory.clientFor(acc).liveChannels(acc).getOrDefault(emptyList())
                    liveCache[acc.id] = live
                    Log.d(TAG, "indexed live=${live.size} for ${acc.name}")
                }
                // a cold index build (huge catalogs) shouldn't stall a keystroke forever;
                // a built index responds instantly, a building one fills in on a later search.
                // Match-index builds need player_api, so only real Xtream panels — a Stalker
                // account searching movies/series would just fire a doomed build into backoff.
                if (acc.isXtream() && acc.searchIncludesType(XtreamAccount.TYPE_MOVIES)) {
                    withTimeoutOrNull(INDEX_WAIT_MS) { resolver.ensureIndexed(acc, MatchKind.MOVIE) }
                }
                if (acc.isXtream() && acc.searchIncludesType(XtreamAccount.TYPE_SERIES)) {
                    withTimeoutOrNull(INDEX_WAIT_MS) { resolver.ensureIndexed(acc, MatchKind.SERIES) }
                }
            }
        }.awaitAll()
    }

    suspend fun search(query: String): Results {
        val q = query.trim()
        if (q.length < 2) return Results(emptyList(), emptyList(), emptyList())
        ensureLoaded()
        val accounts = store.accounts.first().filter { it.enabled }
        // F01: what the viewer hid (a channel, or a whole group, on any device or the website) stays out.
        val overlay = runCatching { overlayRepository.freshSnapshot() }.getOrDefault(OverlaySnapshot())
        val channels = ArrayList<Hit>()
        val movies = ArrayList<Hit>()
        val series = ArrayList<Hit>()
        for (acc in accounts) {
            if (acc.isM3UBacked()) { searchM3U(acc, q, overlay, channels, movies, series); continue }
            // Live hits carry a categoryId -> category selections filter them per item; a
            // disabled content type (or an explicit "Deselect All") contributes nothing.
            if (acc.searchIncludesType(XtreamAccount.TYPE_LIVE)) visibleLiveHits(
                acc, q, overlay,
                liveCache[acc.id].orEmpty().asSequence().filter { acc.allowsCategory(XtreamAccount.TYPE_LIVE, it.categoryId) },
            ).take(PER_ACCOUNT).forEach { ch ->
                    val id = XtreamItemRegistry.liveId(acc.id, ch.streamId)
                    registry.register(
                        XtreamResolvedItem(
                            id = id, type = ContentType.TV, name = ch.name, poster = ch.logo,
                            streamUrl = ch.streamUrl, kind = XtreamKind.LIVE, accountId = acc.id, streamId = ch.streamId
                        )
                    )
                    channels += Hit(id, ch.name, ch.logo, isLive = true, streamUrl = ch.streamUrl, detailType = "tv")
                }
            // Movies/series (B19): the playlist's in-app content-type toggles and category selections
            // filter every hit BEFORE the per-account cap; F01: so do hidden groups.
            if (acc.searchIncludesType(XtreamAccount.TYPE_MOVIES)) when {
                acc.isXtream() -> keepCapped(
                    acc, XtreamAccount.TYPE_MOVIES,
                    withoutHiddenGroups(acc, XtreamAccount.TYPE_MOVIES, overlay,
                        matchIndex.searchByName(acc.id, MatchKind.MOVIE, q, scanLimit(acc, XtreamAccount.TYPE_MOVIES))) { it.categoryId },
                ) { it.categoryId }.forEach { m ->
                    val id = XtreamItemRegistry.vodId(acc.id, m.sid)
                    val streamUrl = xtreamClient.buildStreamUrl(acc, "movie", m.sid, m.ext ?: "mp4")
                    registry.register(
                        XtreamResolvedItem(
                            id = id, type = ContentType.MOVIE, name = m.name, poster = m.poster,
                            streamUrl = streamUrl, accountId = acc.id, streamId = m.sid
                        )
                    )
                    movies += Hit(id, m.name, m.poster, isLive = false, streamUrl = null, detailType = "movie")
                }
                // Stalker never enters the match index (its player_api builds fail into backoff), so
                // ask the portal itself (get_ordered_list&search=), as the source lane and Mobile do.
                // The play link is minted at play time (streamUrl "" = create_link on play).
                acc.sourceType == XtreamAccount.SOURCE_STALKER -> keepCapped(
                    acc, XtreamAccount.TYPE_MOVIES,
                    withoutHiddenGroups(acc, XtreamAccount.TYPE_MOVIES, overlay, clientFactory.stalker().searchMovies(acc, q)) { it.categoryId },
                ) { it.categoryId }.forEach { m ->
                    val id = XtreamItemRegistry.vodId(acc.id, m.streamId)
                    registry.register(
                        XtreamResolvedItem(
                            id = id, type = ContentType.MOVIE, name = m.name, poster = m.poster,
                            imdbRating = m.rating?.toFloatOrNull(), streamUrl = m.streamUrl,
                            accountId = acc.id, streamId = m.streamId
                        )
                    )
                    movies += Hit(id, m.name, m.poster, isLive = false, streamUrl = null, detailType = "movie")
                }
            }
            if (acc.searchIncludesType(XtreamAccount.TYPE_SERIES)) when {
                acc.isXtream() -> keepCapped(
                    acc, XtreamAccount.TYPE_SERIES,
                    withoutHiddenGroups(acc, XtreamAccount.TYPE_SERIES, overlay,
                        matchIndex.searchByName(acc.id, MatchKind.SERIES, q, scanLimit(acc, XtreamAccount.TYPE_SERIES))) { it.categoryId },
                ) { it.categoryId }.forEach { s ->
                    registerSeries(acc, s.sid, s.name, s.poster, series)
                }
                acc.sourceType == XtreamAccount.SOURCE_STALKER -> keepCapped(
                    acc, XtreamAccount.TYPE_SERIES,
                    withoutHiddenGroups(acc, XtreamAccount.TYPE_SERIES, overlay, clientFactory.stalker().searchSeries(acc, q)) { it.categoryId },
                ) { it.categoryId }.forEach { s ->
                    registerSeries(acc, s.seriesId, s.name, s.poster, series, description = s.plot, rating = s.rating)
                }
            }
        }
        return Results(channels.take(DISPLAY), movies.take(DISPLAY), series.take(DISPLAY))
    }

    private fun registerSeries(
        acc: XtreamAccount, sid: Int, name: String, poster: String?, into: MutableList<Hit>,
        description: String? = null, rating: String? = null,
    ) {
        val id = XtreamItemRegistry.seriesId(acc.id, sid)
        registry.register(
            XtreamResolvedItem(
                id = id, type = ContentType.SERIES, name = name, poster = poster,
                description = description, imdbRating = rating?.toFloatOrNull(),
                streamUrl = "", kind = XtreamKind.SERIES, accountId = acc.id, streamId = sid
            )
        )
        into += Hit(id, name, poster, isLive = false, streamUrl = null, detailType = "series")
    }

    private fun scanLimit(acc: XtreamAccount, type: String) = IptvSourceCategoryPolicy.scanLimit(acc, type, PER_ACCOUNT)

    private fun <T> keepCapped(acc: XtreamAccount, type: String, items: List<T>, categoryOf: (T) -> String?): List<T> =
        IptvSourceCategoryPolicy.keepCapped(acc, type, items, PER_ACCOUNT, categoryOf)

    /**
     * M3U search reads the ingested catalog from [IptvContentDb] (substring name match per type),
     * registering each hit like the Xtream path so it plays via the same short-circuit. Respects the
     * content-type toggles and per-category selections for every type (all rows carry a categoryId),
     * filtered before the per-account cap.
     */
    private suspend fun searchM3U(acc: XtreamAccount, q: String, overlay: OverlaySnapshot, channels: MutableList<Hit>, movies: MutableList<Hit>, series: MutableList<Hit>) {
        if (acc.searchIncludesType(XtreamAccount.TYPE_LIVE)) {
            // F01: the DB narrows on the query's longest word; every word must then match (any order),
            // ranked, without hidden channels or groups.
            val probe = IptvChannelSearchPolicy.normalize(q).split(' ').maxByOrNull { it.length } ?: q
            val rows = contentDb.searchChannels(acc.id, probe, maxOf(scanLimit(acc, XtreamAccount.TYPE_LIVE), IptvSourceCategoryPolicy.FILTERED_SCAN_LIMIT))
            val shown = IptvHiddenItemsPolicy.visibleHits(
                IptvChannelSearchPolicy.search(rows, q) { it.name },
                hiddenCategoryIds(acc, XtreamAccount.TYPE_LIVE, overlay.categories),
                overlay.channels,
                categoryOf = { it.categoryId },
                entityOf = { IptvIdentity.entityId(acc.id, it.name, it.tvgId) },
            )
            keepCapped(acc, XtreamAccount.TYPE_LIVE, shown) { it.categoryId }
                .forEach { ch ->
                    val id = XtreamItemRegistry.liveId(acc.id, ch.sid)
                    registry.register(
                        XtreamResolvedItem(
                            id = id, type = ContentType.TV, name = ch.name, poster = ch.logo,
                            streamUrl = ch.url, kind = XtreamKind.LIVE, accountId = acc.id, streamId = ch.sid
                        )
                    )
                    channels += Hit(id, ch.name, ch.logo, isLive = true, streamUrl = ch.url, detailType = "tv")
                }
        }
        if (acc.searchIncludesType(XtreamAccount.TYPE_MOVIES)) keepCapped(
            acc, XtreamAccount.TYPE_MOVIES,
            withoutHiddenGroups(acc, XtreamAccount.TYPE_MOVIES, overlay, contentDb.searchVod(acc.id, q, scanLimit(acc, XtreamAccount.TYPE_MOVIES))) { it.categoryId },
        ) { it.categoryId }.forEach { m ->
            val id = XtreamItemRegistry.vodId(acc.id, m.sid)
            registry.register(
                XtreamResolvedItem(
                    id = id, type = ContentType.MOVIE, name = m.name, poster = m.logo,
                    streamUrl = m.url, accountId = acc.id, streamId = m.sid
                )
            )
            movies += Hit(id, m.name, m.logo, isLive = false, streamUrl = null, detailType = "movie")
        }
        if (acc.searchIncludesType(XtreamAccount.TYPE_SERIES)) keepCapped(
            acc, XtreamAccount.TYPE_SERIES,
            withoutHiddenGroups(acc, XtreamAccount.TYPE_SERIES, overlay, contentDb.searchSeries(acc.id, q, scanLimit(acc, XtreamAccount.TYPE_SERIES))) { it.categoryId },
        ) { it.categoryId }.forEach { s ->
            val id = XtreamItemRegistry.seriesId(acc.id, s.sid)
            registry.register(
                XtreamResolvedItem(
                    id = id, type = ContentType.SERIES, name = s.name, poster = s.logo,
                    streamUrl = "", kind = XtreamKind.SERIES, accountId = acc.id, streamId = s.sid
                )
            )
            series += Hit(id, s.name, s.logo, isLive = false, streamUrl = null, detailType = "series")
        }
    }

    companion object {
        private const val TAG = "XtreamSearchIndex"
        private const val PER_ACCOUNT = 60
        private const val DISPLAY = 60
        private const val INDEX_WAIT_MS = 12_000L
    }
}

/**
 * Whether search should index/return [type] for this account at all: the type is switched on and its
 * category selection is not the explicit empty "none" ([IptvSourceCategoryPolicy.offers]). Per-item
 * category filtering happens at search time — every row (live, match-index movie/series, Stalker,
 * M3U) carries its categoryId.
 */
internal fun XtreamAccount.searchIncludesType(type: String): Boolean = IptvSourceCategoryPolicy.offers(this, type)
