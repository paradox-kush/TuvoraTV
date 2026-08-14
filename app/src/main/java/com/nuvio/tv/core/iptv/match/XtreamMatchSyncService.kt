package com.nuvio.tv.core.iptv.match

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "XtreamMatchSync"

/**
 * Syncs verified TMDB->stream mappings with the `iptv_tmdb_map` table, mirroring
 * XtreamAccountSyncService's shape. Rows are per user+provider (profiles share them).
 * Pull once per provider per session (LWW merge into the local SQLite mirror); push is
 * a debounced upsert of locally-confirmed rows. Anonymous sessions stay device-local.
 */
@Singleton
class XtreamMatchSyncService @Inject constructor(
    private val supabaseProvider: SyncBackendSupabaseProvider,
    private val authManager: AuthManager,
    private val index: XtreamMatchIndex,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pullMutex = Mutex()
    private val pulledProviders = mutableSetOf<String>()
    private var pulledForUser: String? = null
    private var pushJob: Job? = null

    @Serializable
    private data class MapRow(
        @SerialName("provider_key") val providerKey: String,
        @SerialName("content_type") val contentType: String,
        @SerialName("tmdb_id") val tmdbId: Int,
        @SerialName("stream_id") val streamId: Int? = null,
        @SerialName("matched_name") val matchedName: String? = null,
        @SerialName("updated_at_ms") val updatedAtMs: Long,
    )

    /** Merge this provider's remote mappings into the local mirror. No-op after the first call per session/user. */
    suspend fun pullOnce(provider: String) {
        if (!authManager.canSync) return
        val userId = runCatching { authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = true) }.getOrNull() ?: return
        pullMutex.withLock {
            if (pulledForUser != userId) { pulledProviders.clear(); pulledForUser = userId }
            if (!pulledProviders.add(provider)) return
        }
        withContext(Dispatchers.IO) {
            try {
                val rows = supabaseProvider.postgrest
                    .from("iptv_tmdb_map")
                    .select {
                        filter {
                            eq("user_id", userId)
                            eq("provider_key", provider)
                        }
                    }
                    .decodeList<MapRow>()
                var applied = 0
                for (row in rows) {
                    val kind = MatchKind.entries.firstOrNull { it.slug == row.contentType } ?: continue
                    val local = index.cachedMapping(provider, kind, row.tmdbId)
                    if (local == null || row.updatedAtMs > local.updatedAtMs) {
                        index.putMapping(provider, kind, row.tmdbId, row.streamId, row.matchedName, synced = true, updatedAtMs = row.updatedAtMs)
                        applied++
                    }
                }
                Log.d(TAG, "pullOnce($provider): ${rows.size} rows, $applied applied")
            } catch (e: Exception) {
                pullMutex.withLock { pulledProviders.remove(provider) } // retry next resolve
                Log.w(TAG, "pullOnce($provider) failed", e)
            }
        }
    }

    /** Debounced push of not-yet-synced local mappings for this provider. */
    fun triggerPush(provider: String) {
        if (!authManager.canSync) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(2_000)
            try {
                val pending = index.unsyncedMappings(provider)
                if (pending.isEmpty()) return@launch
                val rows = pending.map {
                    MapRow(
                        providerKey = provider,
                        contentType = it.kind,
                        tmdbId = it.tmdb,
                        streamId = it.sid,
                        matchedName = it.matchedName,
                        updatedAtMs = it.updatedAtMs,
                    )
                }
                supabaseProvider.postgrest.from("iptv_tmdb_map").upsert(rows)
                for (row in pending) index.markSynced(provider, row.kind, row.tmdb)
                Log.d(TAG, "pushed ${rows.size} mappings for $provider")
            } catch (e: Exception) {
                Log.w(TAG, "push($provider) failed", e)
            }
        }
    }
}
