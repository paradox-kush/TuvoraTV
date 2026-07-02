package com.nuvio.tv.core.iptv

import android.util.Log
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
    private val client: XtreamClient,
    private val registry: XtreamItemRegistry,
    private val matchIndex: XtreamMatchIndex,
    private val resolver: XtreamTmdbResolver,
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

    private suspend fun ensureLoaded() = coroutineScope {
        val accounts = store.accounts.first().filter { it.enabled }
        accounts.map { acc ->
            async {
                // Disabled content types are skipped entirely (not fetched, not indexed).
                if (acc.typeEnabled(XtreamAccount.TYPE_LIVE) && !liveCache.containsKey(acc.id)) {
                    val live = client.liveChannels(acc).getOrDefault(emptyList())
                    liveCache[acc.id] = live
                    Log.d(TAG, "indexed live=${live.size} for ${acc.name}")
                }
                // a cold index build (huge catalogs) shouldn't stall a keystroke forever;
                // a built index responds instantly, a building one fills in on a later search
                if (acc.typeEnabled(XtreamAccount.TYPE_MOVIES)) {
                    withTimeoutOrNull(INDEX_WAIT_MS) { resolver.ensureIndexed(acc, MatchKind.MOVIE) }
                }
                if (acc.typeEnabled(XtreamAccount.TYPE_SERIES)) {
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
        val channels = ArrayList<Hit>()
        val movies = ArrayList<Hit>()
        val series = ArrayList<Hit>()
        for (acc in accounts) {
            // Live hits carry a categoryId -> category selections filter them; a disabled
            // content type contributes nothing. (Movie/series index rows carry no categoryId,
            // so those filter at the content-type level only.)
            if (acc.typeEnabled(XtreamAccount.TYPE_LIVE)) liveCache[acc.id].orEmpty().asSequence()
                .filter { acc.allowsCategory(XtreamAccount.TYPE_LIVE, it.categoryId) }
                .filter { it.name.contains(q, ignoreCase = true) }.take(PER_ACCOUNT).forEach { ch ->
                    val id = XtreamItemRegistry.liveId(acc.id, ch.streamId)
                    registry.register(
                        XtreamResolvedItem(
                            id = id, type = ContentType.TV, name = ch.name, poster = ch.logo,
                            streamUrl = ch.streamUrl, kind = XtreamKind.LIVE, accountId = acc.id, streamId = ch.streamId
                        )
                    )
                    channels += Hit(id, ch.name, ch.logo, isLive = true, streamUrl = ch.streamUrl, detailType = "tv")
                }
            if (acc.typeEnabled(XtreamAccount.TYPE_MOVIES)) matchIndex.searchByName(acc.id, MatchKind.MOVIE, q, PER_ACCOUNT).forEach { m ->
                val id = XtreamItemRegistry.vodId(acc.id, m.sid)
                val streamUrl = client.buildStreamUrl(acc, "movie", m.sid, m.ext ?: "mp4")
                registry.register(
                    XtreamResolvedItem(
                        id = id, type = ContentType.MOVIE, name = m.name, poster = m.poster,
                        streamUrl = streamUrl, accountId = acc.id, streamId = m.sid
                    )
                )
                movies += Hit(id, m.name, m.poster, isLive = false, streamUrl = null, detailType = "movie")
            }
            if (acc.typeEnabled(XtreamAccount.TYPE_SERIES)) matchIndex.searchByName(acc.id, MatchKind.SERIES, q, PER_ACCOUNT).forEach { s ->
                val id = XtreamItemRegistry.seriesId(acc.id, s.sid)
                registry.register(
                    XtreamResolvedItem(
                        id = id, type = ContentType.SERIES, name = s.name, poster = s.poster,
                        streamUrl = "", kind = XtreamKind.SERIES, accountId = acc.id, streamId = s.sid
                    )
                )
                series += Hit(id, s.name, s.poster, isLive = false, streamUrl = null, detailType = "series")
            }
        }
        return Results(channels.take(DISPLAY), movies.take(DISPLAY), series.take(DISPLAY))
    }

    companion object {
        private const val TAG = "XtreamSearchIndex"
        private const val PER_ACCOUNT = 60
        private const val DISPLAY = 60
        private const val INDEX_WAIT_MS = 12_000L
    }
}
