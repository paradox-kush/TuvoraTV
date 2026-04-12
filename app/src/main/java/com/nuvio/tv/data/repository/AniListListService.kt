package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.local.AniListAuthDataStore
import com.nuvio.tv.data.mapper.AniListListToMetaMapper
import com.nuvio.tv.data.remote.api.AniListApi
import com.nuvio.tv.data.remote.dto.anilist.AniListGraphQLRequest
import com.nuvio.tv.data.remote.dto.anilist.AniListMediaListEntryDto
import com.nuvio.tv.data.remote.dto.anilist.AniListQueries
import com.nuvio.tv.domain.model.TrackerListItem
import com.nuvio.tv.domain.model.TrackerListStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AniList list fetcher. Unlike MAL, AniList returns the full user library in a
 * single `MediaListCollection` call partitioned into status buckets — so we
 * fetch once and distribute to per-status cache entries.
 */
@Singleton
class AniListListService @Inject constructor(
    private val api: AniListApi,
    private val authStore: AniListAuthDataStore,
    private val mapper: AniListListToMetaMapper
) {
    private data class Entry(
        val items: List<TrackerListItem>,
        val fetchedAtMs: Long
    )

    private val cache = MutableStateFlow<Map<TrackerListStatus, Entry>>(emptyMap())
    private val fullFetchMutex = Mutex()

    fun observe(status: TrackerListStatus): Flow<NetworkResult<List<TrackerListItem>>> =
        combine(cache, authStore.isAuthenticated) { snap, isAuthed ->
            snap[status] to isAuthed
        }.let { flow ->
            flow {
                var lastEmitted: Entry? = null
                flow.collect { (entry, isAuthed) ->
                    if (!isAuthed) {
                        emit(NetworkResult.Success(emptyList()))
                        return@collect
                    }
                    val now = System.currentTimeMillis()
                    if (entry != null && now - entry.fetchedAtMs < CACHE_TTL_MS) {
                        if (entry != lastEmitted) {
                            emit(NetworkResult.Success(entry.items))
                            lastEmitted = entry
                        }
                    } else {
                        emit(NetworkResult.Loading)
                        val result = refreshAll()
                        emit(result[status]?.let { NetworkResult.Success(it) } ?: NetworkResult.Success(emptyList()))
                    }
                }
            }
        }

    /** Refresh every status in a single network call and update cache. */
    suspend fun refreshAll(): Map<TrackerListStatus, List<TrackerListItem>> = fullFetchMutex.withLock {
        val cachedAny = cache.value.values.firstOrNull()
        if (cachedAny != null && System.currentTimeMillis() - cachedAny.fetchedAtMs < CACHE_TTL_MS) {
            return@withLock cache.value.mapValues { it.value.items }
        }
        val authed = authStore.isAuthenticated.first()
        if (!authed) return@withLock emptyMap()
        val userId = authStore.state.first().userId?.toIntOrNull()
            ?: (fetchViewerId() ?: return@withLock emptyMap())
        fetchCollection(userId) ?: emptyMap()
    }

    suspend fun refresh(status: TrackerListStatus): NetworkResult<List<TrackerListItem>> {
        val all = refreshAll()
        return NetworkResult.Success(all[status].orEmpty())
    }

    suspend fun invalidate(status: TrackerListStatus) {
        cache.value = cache.value - status
    }

    suspend fun invalidateAll() {
        cache.value = emptyMap()
    }

    // --- internal --- //

    private suspend fun fetchViewerId(): Int? {
        val result = safeApiCall { api.viewer(AniListGraphQLRequest(AniListQueries.VIEWER)) }
        val viewer = (result as? NetworkResult.Success)?.data?.data?.viewer ?: return null
        authStore.saveUser(userId = viewer.id.toString(), username = viewer.name)
        return viewer.id
    }

    private suspend fun fetchCollection(userId: Int): Map<TrackerListStatus, List<TrackerListItem>>? {
        val req = AniListGraphQLRequest(
            query = AniListQueries.MEDIA_LIST_COLLECTION,
            variables = mapOf("userId" to userId, "type" to "ANIME")
        )
        val result = safeApiCall { api.mediaListCollection(req) }
        val collection = when (result) {
            is NetworkResult.Success -> result.data.data?.collection
            is NetworkResult.Error -> {
                Log.w(TAG, "list fetch failed code=${result.code} msg=${result.message}")
                return null
            }
            NetworkResult.Loading -> return null
        } ?: return emptyMap()

        // Flatten all entries, skip custom lists (those are user-defined and
        // not tied to the 6 canonical statuses). Distribute into buckets.
        val buckets = mutableMapOf<TrackerListStatus, MutableList<AniListMediaListEntryDto>>()
        for (group in collection.lists) {
            if (group.isCustomList) continue
            val status = TrackerListStatus.fromAniList(group.status) ?: continue
            buckets.getOrPut(status) { mutableListOf() } += group.entries
        }
        val mapped = buckets.mapValues { (_, entries) -> mapper.map(entries) }
        val now = System.currentTimeMillis()
        // Seed every known status so empty lists cache as "empty" rather than "unfetched".
        val newCache = TrackerListStatus.values().associateWith { status ->
            Entry(items = mapped[status].orEmpty(), fetchedAtMs = now)
        }
        cache.value = newCache
        return mapped
    }

    companion object {
        private const val TAG = "AniListListService"
        private const val CACHE_TTL_MS = 5L * 60 * 1000
    }
}
