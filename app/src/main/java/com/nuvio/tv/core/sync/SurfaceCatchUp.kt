package com.nuvio.tv.core.sync

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.domain.model.AuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B03 (D1/D2) — the "pull as truth" half of Realtime on TV. Reads the surface version vector
 * (`sync_get_surface_versions`, one < 1 KB call), pulls only the surfaces that advanced past what this
 * TV last pulled ([SurfaceCatchUpPolicy]) through the per-surface dispatcher a Realtime event uses
 * ([StartupSyncService.pullSurface]), and records the new versions only for pulls that succeeded.
 *
 * Runs on every Realtime (re)subscribe and on foreground (MainActivity.onResume → requestForegroundSync,
 * inside its existing 2-minute freshness window) — the TV used to refresh only watch state + library on
 * resume (upstream c68b0e4b1), so a web edit made while it slept never arrived until a cold start. An
 * idle slate costs the single vector read. A server without the RPC (not yet deployed) is a no-op.
 */
@Singleton
class SurfaceCatchUp @Inject constructor(
    @ApplicationContext context: Context,
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val startupSyncService: dagger.Lazy<StartupSyncService>,
) {
    private val preferences = context.getSharedPreferences("nuvio_sync_surface_versions", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val seenCodec = MapSerializer(String.serializer(), Long.serializer())

    @Serializable
    private data class Row(
        @SerialName("profile_id") val profileId: Int? = null,
        val surface: String,
        val version: Long,
    )

    private fun loadSeen(userId: String): Map<String, Long> =
        preferences.getString(userId, null)
            ?.let { runCatching { json.decodeFromString(seenCodec, it) }.getOrNull() }
            .orEmpty()

    private fun saveSeen(userId: String, seen: Map<String, Long>) {
        preferences.edit().putString(userId, json.encodeToString(seenCodec, seen)).apply()
    }

    suspend fun fetch(profileId: Int): List<SurfaceVersion>? {
        if (!authManager.canSync) return null
        return try {
            postgrest.rpc("sync_get_surface_versions", buildJsonObject { put("p_profile_id", profileId) })
                .decodeList<Row>()
                .map { SurfaceVersion(it.profileId, it.surface, it.version) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "surface versions unavailable: ${e.message}")
            null
        }
    }

    /** Pull whatever advanced since this TV last pulled it. Returns the surfaces pulled. */
    suspend fun run(profileId: Int, reason: String): List<String> = mutex.withLock {
        val userId = (authManager.authState.value as? AuthState.FullAccount)?.userId ?: return@withLock emptyList()
        val server = fetch(profileId) ?: return@withLock emptyList()
        val seen = loadSeen(userId)
        val sync = startupSyncService.get()
        val plan = SurfaceCatchUpPolicy.plan(seen, server, sync.pullableSurfaces())
        if (plan.isEmpty()) return@withLock emptyList()
        Log.i(TAG, "catch-up ($reason) profile=$profileId surfaces=${plan.map { it.surface }}")
        val pulled = plan.filter { sync.pullSurface(profileId, it.surface) }
        saveSeen(userId, SurfaceCatchUpPolicy.advance(seen, pulled))
        pulled.map { it.surface }
    }

    private companion object {
        const val TAG = "SurfaceCatchUp"
    }
}
