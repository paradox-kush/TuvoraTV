package com.nuvio.tv.core.iptv.match

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import io.github.jan.supabase.postgrest.query.Order
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
 * Pull is delta-shaped per MatchMapPullPolicy (LWW merge into the local SQLite mirror); push is
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

    /** The remote table, scoped to the sync owner explicitly (as the pre-B78 full select was). */
    private fun remoteFor(userId: String) = MatchMapRemote { provider, sinceMs, offset, limit ->
        supabaseProvider.postgrest
            .from("iptv_tmdb_map")
            .select {
                filter {
                    eq("user_id", userId)
                    eq("provider_key", provider)
                    if (sinceMs != null) gte("updated_at_ms", sinceMs)
                }
                // Ascending is load-bearing: a pull interrupted between pages leaves the mark at
                // the newest APPLIED row, and the next delta resumes from there.
                order("updated_at_ms", Order.ASCENDING)
                order("content_type", Order.ASCENDING)
                order("tmdb_id", Order.ASCENDING)
                range(offset.toLong(), (offset + limit - 1).toLong())
            }
            .decodeList<MapRow>()
            .mapNotNull { row ->
                val kind = MatchKind.entries.firstOrNull { it.slug == row.contentType } ?: return@mapNotNull null
                RemoteMapping(kind, row.tmdbId, row.streamId, row.matchedName, row.updatedAtMs)
            }
    }

    private val store = object : MatchMapStore {
        override suspend fun readCursor(owner: String, provider: String) = index.readPullCursor(owner, provider)
        override suspend fun applyPage(owner: String, provider: String, rows: List<RemoteMapping>, cursor: MatchMapCursor) =
            index.applyPulledPage(owner, provider, rows, cursor)
    }

    /**
     * Merge this provider's remote mappings into the local mirror. At most once per provider per
     * session/user, and even then delta-shaped (B78): [MatchMapPullPolicy] fetches only rows newer
     * than the persisted cursor, or nothing at all when the last pull was recent.
     */
    suspend fun pullOnce(provider: String) {
        if (!authManager.canSync) return
        val userId = runCatching { authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = true) }.getOrNull() ?: return
        pullMutex.withLock {
            if (pulledForUser != userId) { pulledProviders.clear(); pulledForUser = userId }
            if (!pulledProviders.add(provider)) return
        }
        withContext(Dispatchers.IO) {
            try {
                val r = MatchMapPuller.pull(userId, provider, System.currentTimeMillis(), store, remoteFor(userId))
                Log.d(TAG, "pullOnce($provider): full=${r.full} requests=${r.requests} rows=${r.fetched} applied=${r.applied}")
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
