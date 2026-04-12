package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.local.KitsuAuthDataStore
import com.nuvio.tv.data.mapper.KitsuListToMetaMapper
import com.nuvio.tv.data.remote.api.KitsuApi
import com.nuvio.tv.data.remote.dto.kitsu.KitsuLibraryPageDto
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
 * Kitsu library fetcher. Uses the JSON:API `filter[status]=` param to fetch
 * one status at a time, paginating via `links.next`. Each status is cached
 * independently with a 5-minute TTL.
 */
@Singleton
class KitsuListService @Inject constructor(
    private val api: KitsuApi,
    private val authStore: KitsuAuthDataStore,
    private val mapper: KitsuListToMetaMapper
) {
    private data class Entry(
        val items: List<TrackerListItem>,
        val fetchedAtMs: Long
    )

    private val cache = MutableStateFlow<Map<TrackerListStatus, Entry>>(emptyMap())
    private val fetchLocks = mutableMapOf<TrackerListStatus, Mutex>()

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

    suspend fun refresh(status: TrackerListStatus): NetworkResult<List<TrackerListItem>> {
        val authed = authStore.isAuthenticated.first()
        if (!authed) return NetworkResult.Success(emptyList())
        val userId = authStore.state.first().userId
            ?: (fetchSelfId() ?: return NetworkResult.Error("unknown kitsu user"))
        val lock = synchronized(fetchLocks) { fetchLocks.getOrPut(status) { Mutex() } }
        return lock.withLock {
            val cached = cache.value[status]
            if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS) {
                return@withLock NetworkResult.Success(cached.items)
            }
            fetchFully(userId, status)
        }
    }

    suspend fun invalidate(status: TrackerListStatus) {
        cache.value = cache.value - status
    }

    suspend fun invalidateAll() {
        cache.value = emptyMap()
    }

    // --- internal --- //

    private suspend fun fetchSelfId(): String? {
        val result = safeApiCall { api.getSelf() }
        val user = (result as? NetworkResult.Success)?.data?.data?.firstOrNull() ?: return null
        authStore.saveUser(userId = user.id, username = user.attributes?.name)
        return user.id
    }

    private suspend fun fetchFully(userId: String, status: TrackerListStatus): NetworkResult<List<TrackerListItem>> {
        val accumulated = mutableListOf<TrackerListItem>()
        var nextUrl: String? = null
        var pagesFetched = 0
        while (true) {
            val result = if (nextUrl == null) {
                safeApiCall { api.getLibrary(userId = userId, status = status.toKitsu()) }
            } else {
                safeApiCall { api.getLibraryPage(nextUrl!!) }
            }
            val page: KitsuLibraryPageDto = when (result) {
                is NetworkResult.Success -> result.data
                is NetworkResult.Error -> {
                    Log.w(TAG, "list fetch failed status=$status code=${result.code} msg=${result.message}")
                    return result
                }
                NetworkResult.Loading -> return NetworkResult.Loading
            }
            accumulated += mapper.map(page)
            pagesFetched++
            nextUrl = page.links?.next
            if (nextUrl.isNullOrBlank() || pagesFetched >= MAX_PAGES) break
        }
        cache.value = cache.value + (status to Entry(accumulated, System.currentTimeMillis()))
        return NetworkResult.Success(accumulated)
    }

    companion object {
        private const val TAG = "KitsuListService"
        private const val MAX_PAGES = 20
        private const val CACHE_TTL_MS = 5L * 60 * 1000
    }
}
