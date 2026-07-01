package com.nuvio.tv.core.iptv

import android.util.Log
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.domain.model.ContentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets Xtream content show up in the platform search. Xtream panels have NO search
 * endpoint, so we fetch each enabled account's full live/vod/series lists once (lazily,
 * on the first IPTV search) and filter them in memory on each query.
 *
 * ponytail: caps each list at [MAX_INDEX] items to bound memory on a TV — a panel with
 * 174k VOD won't all fit, so titles past the cap aren't searchable (logged). The upgrade
 * path is a real on-device index (Room/FTS) if that ceiling ever bites.
 */
@Singleton
class XtreamSearchIndex @Inject constructor(
    private val store: XtreamAccountStore,
    private val client: XtreamClient,
    private val registry: XtreamItemRegistry
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

    // Cached per accountId so a slow VOD fetch doesn't block channel/series search.
    private val liveCache = ConcurrentHashMap<String, List<XtreamChannel>>()
    private val vodCache = ConcurrentHashMap<String, List<XtreamMovie>>()
    private val seriesCache = ConcurrentHashMap<String, List<XtreamSeriesItem>>()

    /** Awaits live + series + VOD per account so all three are searchable on the first query. */
    private suspend fun ensureLoaded() = coroutineScope {
        val accounts = store.accounts.first().filter { it.enabled }
        accounts.map { acc ->
            async {
                if (!liveCache.containsKey(acc.id)) {
                    val live = client.liveChannels(acc).getOrDefault(emptyList())
                    liveCache[acc.id] = live.take(MAX_INDEX)
                    Log.d(TAG, "indexed live=${live.size} for ${acc.name}")
                }
                if (!seriesCache.containsKey(acc.id)) {
                    val s = client.series(acc).getOrDefault(emptyList())
                    seriesCache[acc.id] = s.take(MAX_INDEX)
                    Log.d(TAG, "indexed series=${s.size} for ${acc.name}")
                }
                // ponytail: VOD is fetched inline (awaited) so movies are present on the FIRST
                // search. Tradeoff: a large panel makes the first search a bit slower, but the
                // previous detached launch never joined awaitAll, so movies came back empty.
                if (!vodCache.containsKey(acc.id)) {
                    val vod = client.vodMovies(acc).getOrDefault(emptyList())
                    vodCache[acc.id] = vod.take(MAX_INDEX)
                    Log.d(TAG, "indexed vod=${vod.size} for ${acc.name}")
                }
            }
        }.awaitAll()
    }

    suspend fun search(query: String): Results {
        val q = query.trim().lowercase()
        if (q.length < 2) return Results(emptyList(), emptyList(), emptyList())
        ensureLoaded()
        val accounts = store.accounts.first().filter { it.enabled }
        val channels = ArrayList<Hit>()
        val movies = ArrayList<Hit>()
        val series = ArrayList<Hit>()
        for (acc in accounts) {
            liveCache[acc.id].orEmpty().asSequence().filter { it.name.lowercase().contains(q) }.take(PER_ACCOUNT).forEach { ch ->
                val id = XtreamItemRegistry.liveId(acc.id, ch.streamId)
                registry.register(
                    XtreamResolvedItem(
                        id = id, type = ContentType.TV, name = ch.name, poster = ch.logo,
                        streamUrl = ch.streamUrl, kind = XtreamKind.LIVE, accountId = acc.id, streamId = ch.streamId
                    )
                )
                channels += Hit(id, ch.name, ch.logo, isLive = true, streamUrl = ch.streamUrl, detailType = "tv")
            }
            vodCache[acc.id].orEmpty().asSequence().filter { it.name.lowercase().contains(q) }.take(PER_ACCOUNT).forEach { m ->
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
            seriesCache[acc.id].orEmpty().asSequence().filter { it.name.lowercase().contains(q) }.take(PER_ACCOUNT).forEach { s ->
                val id = XtreamItemRegistry.seriesId(acc.id, s.seriesId)
                registry.register(
                    XtreamResolvedItem(
                        id = id, type = ContentType.SERIES, name = s.name, poster = s.poster,
                        description = s.plot, imdbRating = s.rating?.toFloatOrNull(),
                        streamUrl = "", kind = XtreamKind.SERIES, accountId = acc.id, streamId = s.seriesId
                    )
                )
                series += Hit(id, s.name, s.poster, isLive = false, streamUrl = null, detailType = "series")
            }
        }
        return Results(channels.take(DISPLAY), movies.take(DISPLAY), series.take(DISPLAY))
    }

    companion object {
        private const val TAG = "XtreamSearchIndex"
        private const val MAX_INDEX = 40000
        private const val PER_ACCOUNT = 60
        private const val DISPLAY = 60
    }
}
