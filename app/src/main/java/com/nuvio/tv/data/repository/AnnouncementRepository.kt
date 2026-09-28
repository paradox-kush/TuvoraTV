package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.announcements.AnnouncementCodec
import com.nuvio.tv.core.announcements.AnnouncementPolicy
import com.nuvio.tv.data.local.AnnouncementDataStore
import com.nuvio.tv.domain.model.Announcement
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Announcements"

/**
 * In-app announcements from `get_app_announcements(p_platform = 'tv')` (anon-safe).
 *
 * No polling: [refreshIfDue] is called when Home becomes visible and only hits the network when
 * [AnnouncementPolicy.shouldFetch] allows it (once per 6 h). A failure keeps the cached list.
 */
@Singleton
class AnnouncementRepository @Inject constructor(
    private val postgrest: Postgrest,
    private val store: AnnouncementDataStore,
) {
    private val refreshMutex = Mutex()

    /** The single announcement to show right now, or null. */
    val current: Flow<Announcement?> = combine(store.cached, store.dismissedIds) { items, dismissed ->
        AnnouncementPolicy.pick(items, dismissed)
    }.distinctUntilChanged()

    suspend fun refreshIfDue(nowMs: Long = System.currentTimeMillis()) {
        // Single-flight: overlapping resumes never stack a second request.
        if (!refreshMutex.tryLock()) return
        try {
            if (!AnnouncementPolicy.shouldFetch(store.lastFetchedAtMs(), nowMs)) return
            val items = try {
                val response = postgrest.rpc(
                    "get_app_announcements",
                    buildJsonObject { put("p_platform", "tv") }
                )
                AnnouncementCodec.decode(response.data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Announcements fetch failed; keeping cache: ${e.message}")
                return
            }
            store.saveFetched(items, nowMs)
        } finally {
            refreshMutex.unlock()
        }
    }

    suspend fun dismiss(id: String) {
        store.addDismissed(id)
    }
}
