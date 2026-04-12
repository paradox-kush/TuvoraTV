package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.local.MalAuthDataStore
import com.nuvio.tv.data.mapper.MalListToMetaMapper
import com.nuvio.tv.data.remote.api.MalApi
import com.nuvio.tv.data.remote.dto.mal.MalListEntryDto
import com.nuvio.tv.domain.model.TrackerListItem
import com.nuvio.tv.domain.model.TrackerListStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the user's MAL animelist and maps it to [TrackerListItem]. One list
 * per [TrackerListStatus] is cached with a 5-minute TTL; cache is keyed by
 * status so the "Watching" row refreshing doesn't invalidate "Completed".
 *
 * The service does not cross watch-progress updates into its own cache — when
 * the player marks an episode watched, callers should invoke [invalidate] for
 * the affected status(es) so the next home fetch reflects the new state.
 */
@Singleton
class MalListService @Inject constructor(
    private val api: MalApi,
    private val authStore: MalAuthDataStore,
    private val mapper: MalListToMetaMapper
) {
    private data class Entry(
        val items: List<TrackerListItem>,
        val fetchedAtMs: Long
    )

    private val cache = MutableStateFlow<Map<TrackerListStatus, Entry>>(emptyMap())
    private val invalidations = MutableSharedFlow<TrackerListStatus>(extraBufferCapacity = 16)
    private val fetchLocks = mutableMapOf<TrackerListStatus, Mutex>()

    /**
     * Observe items for a single status. Emits cached values immediately when
     * fresh, otherwise kicks off a fetch and re-emits. Invalidations trigger
     * re-fetches.
     */
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
                        val result = refresh(status)
                        emit(result)
                    }
                }
            }
        }

    /** Force a refresh of a single status. Suspends until the network call returns. */
    suspend fun refresh(status: TrackerListStatus): NetworkResult<List<TrackerListItem>> {
        val isAuthed = authStore.isAuthenticated.first()
        if (!isAuthed) return NetworkResult.Success(emptyList())
        val lock = synchronized(fetchLocks) {
            fetchLocks.getOrPut(status) { Mutex() }
        }
        return lock.withLock {
            // Another waiter may have populated the cache while we were queued.
            val cached = cache.value[status]
            if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS) {
                return@withLock NetworkResult.Success(cached.items)
            }
            fetchFully(status)
        }
    }

    suspend fun invalidate(status: TrackerListStatus) {
        cache.value = cache.value - status
        invalidations.emit(status)
    }

    suspend fun invalidateAll() {
        cache.value = emptyMap()
        TrackerListStatus.values().forEach { invalidations.emit(it) }
    }

    // --- internal --- //

    private suspend fun fetchFully(status: TrackerListStatus): NetworkResult<List<TrackerListItem>> {
        val accumulated = mutableListOf<MalListEntryDto>()
        var offset = 0
        var pagesFetched = 0
        while (true) {
            val result = safeApiCall {
                api.getUserAnimeList(status = status.toMal(), offset = offset, limit = PAGE_SIZE)
            }
            when (result) {
                is NetworkResult.Success -> {
                    accumulated += result.data.data
                    pagesFetched++
                    val next = result.data.paging?.next
                    if (next.isNullOrBlank() || pagesFetched >= MAX_PAGES) break
                    offset += PAGE_SIZE
                }
                is NetworkResult.Error -> {
                    Log.w(TAG, "list fetch failed status=$status code=${result.code} msg=${result.message}")
                    return result
                }
                NetworkResult.Loading -> return NetworkResult.Loading
            }
        }
        val mapped = mapper.map(accumulated)
        cache.value = cache.value + (status to Entry(mapped, System.currentTimeMillis()))
        return NetworkResult.Success(mapped)
    }

    companion object {
        private const val TAG = "MalListService"
        private const val PAGE_SIZE = 200
        private const val MAX_PAGES = 20  // 4000 entries — more than any real user
        private const val CACHE_TTL_MS = 5L * 60 * 1000
    }
}
