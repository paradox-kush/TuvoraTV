package com.nuvio.tv.core.anime

import android.util.Log
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.ArmApi
import com.nuvio.tv.data.remote.api.ArmEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [ArmApi] (arm.haglund.dev) that maps between external
 * IDs — TMDB / IMDb / TVDB → MAL / AniList / Kitsu / AniDB.
 *
 * `arm` returns arrays for TMDB/IMDb/TVDB lookups because a single TMDB show
 * frequently bundles multiple anime seasons/cours that each have their own
 * tracker entry. Ordering in those arrays is unspecified, so per-season
 * disambiguation is the caller's responsibility (typically handled by
 * [EpisodeOffsetMapper] which composes PlexAniBridge data on top).
 *
 * Results are memoised in-process (per TMDB/tracker id) for the life of the
 * singleton — the app restarts seldom enough that a cold cache on launch is
 * acceptable and avoids dealing with disk persistence here.
 */
@Singleton
class AnimeIdMapper @Inject constructor(
    private val armApi: ArmApi
) {
    private val tmdbCache = mutableMapOf<Int, List<ArmEntry>>()
    private val tmdbMutex = Mutex()

    private val imdbCache = mutableMapOf<String, List<ArmEntry>>()
    private val imdbMutex = Mutex()

    private val trackerCache = mutableMapOf<TrackerKey, ArmEntry>()
    private val trackerMutex = Mutex()

    /** Every anime entry mapped to this IMDb id (one per anime season/cour). */
    suspend fun getEntriesForImdb(imdbId: String): List<ArmEntry> {
        val key = imdbId.trim()
        if (key.isBlank()) return emptyList()
        imdbMutex.withLock {
            imdbCache[key]?.let { return it }
        }
        val result = safeApiCall { armApi.resolveImdbToAll(imdbId = key) }
        val list = when (result) {
            is NetworkResult.Success -> result.data
            is NetworkResult.Error -> {
                Log.w(TAG, "arm lookup failed imdb=$key code=${result.code} msg=${result.message}")
                emptyList()
            }
            NetworkResult.Loading -> emptyList()
        }
        imdbMutex.withLock { imdbCache[key] = list }
        return list
    }

    /** Every anime entry mapped to this TMDB show (one per anime season/cour). */
    suspend fun getEntriesForTmdb(tmdbId: Int): List<ArmEntry> {
        tmdbMutex.withLock {
            tmdbCache[tmdbId]?.let { return it }
        }
        val result = safeApiCall { armApi.resolveTmdbToAll(tmdbId) }
        val list = when (result) {
            is NetworkResult.Success -> result.data
            is NetworkResult.Error -> {
                Log.w(TAG, "arm lookup failed tmdb=$tmdbId code=${result.code} msg=${result.message}")
                emptyList()
            }
            NetworkResult.Loading -> emptyList()
        }
        tmdbMutex.withLock { tmdbCache[tmdbId] = list }
        return list
    }

    /** Resolve every ID for a tracker-native id (MAL/AniList/Kitsu). */
    suspend fun resolveFromTracker(source: TrackerSource, id: Int): ArmEntry? {
        val key = TrackerKey(source, id)
        trackerMutex.withLock {
            trackerCache[key]?.let { return it }
        }
        val result = safeApiCall {
            when (source) {
                TrackerSource.MAL -> armApi.resolveMalToAll(malId = id.toString())
                TrackerSource.ANILIST -> armApi.resolveAnilistToAll(anilistId = id.toString())
                TrackerSource.KITSU -> armApi.resolveKitsuToAll(kitsuId = id.toString())
            }
        }
        val entry = (result as? NetworkResult.Success)?.data
        if (entry != null) {
            trackerMutex.withLock { trackerCache[key] = entry }
        } else if (result is NetworkResult.Error) {
            Log.w(TAG, "arm lookup failed ${source.name}=$id code=${result.code} msg=${result.message}")
        }
        return entry
    }

    enum class TrackerSource { MAL, ANILIST, KITSU }

    private data class TrackerKey(val source: TrackerSource, val id: Int)

    companion object { private const val TAG = "AnimeIdMapper" }
}
