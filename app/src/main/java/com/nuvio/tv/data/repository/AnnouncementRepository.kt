package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.announcements.AnnouncementViewer
import com.nuvio.tv.core.announcements.AnnouncementVisibilityPolicy
import com.nuvio.tv.core.auth.AuthManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
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
    private val authManager: AuthManager,
    @ApplicationContext private val context: Context,
) {
    private val refreshMutex = Mutex()

    /**
     * When this install first ran. Android records it per package and keeps it across updates, so
     * an existing install keeps seeing a policy notice while a fresh one does not (UX84). 0 when
     * unavailable, which the policy treats as an existing install.
     */
    private val installFirstSeenAtMs: Long by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        }.getOrDefault(0L)
    }

    private val viewer: Flow<AnnouncementViewer> = authManager.authState.map { state ->
        AnnouncementViewer(
            signIn = AnnouncementVisibilityPolicy.signInFrom(state, authManager.currentAnnouncementAccountRecord()),
            installFirstSeenAtMs = installFirstSeenAtMs,
        )
    }

    /** The single announcement to show right now, or null. */
    val current: Flow<Announcement?> =
        combine(store.cached, store.dismissedIds, viewer) { items, dismissed, viewer ->
            AnnouncementVisibilityPolicy.pick(items, dismissed, viewer)
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
