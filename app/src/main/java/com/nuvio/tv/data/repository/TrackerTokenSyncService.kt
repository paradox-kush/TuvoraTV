package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AniListAuthDataStore
import com.nuvio.tv.data.local.KitsuAuthDataStore
import com.nuvio.tv.data.local.MalAuthDataStore
import com.nuvio.tv.data.remote.supabase.UserTrackerTokensRow
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes and pulls anime-tracker access / refresh tokens to the Supabase
 * `user_tracker_tokens` table so a user who signed in on one device has the
 * tokens available on every other device sharing the same sync owner.
 *
 * This service is additive — old app versions never call these RPCs, so
 * there's no risk of an older client overwriting data written here.
 *
 * Local storage (per-tracker `*AuthDataStore`) remains the source of truth
 * at runtime; this service just mirrors writes to the cloud and backfills
 * empty DataStores on startup.
 */
@Singleton
class TrackerTokenSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileManager: ProfileManager,
    private val malAuthStore: MalAuthDataStore,
    private val aniListAuthStore: AniListAuthDataStore,
    private val kitsuAuthStore: KitsuAuthDataStore
) {
    companion object {
        private const val TAG = "TrackerTokenSync"
        const val TRACKER_MAL = "mal"
        const val TRACKER_ANILIST = "anilist"
        const val TRACKER_KITSU = "kitsu"
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }

    /**
     * Pull every tracker's tokens for the active profile and hydrate the
     * matching [*AuthDataStore]. Called from
     * [com.nuvio.tv.core.sync.StartupSyncService] on startup.
     *
     * Local tokens are overwritten only when the remote row has a strictly
     * newer `updated_at`. That way a device that just refreshed its token
     * (and hasn't pushed yet) doesn't get its newer value stomped by a
     * stale remote copy.
     */
    suspend fun pullTokensForActiveProfile(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!authManager.isAuthenticated) return@withContext Result.success(Unit)
        try {
            val profileId = profileManager.activeProfileId.value
            val params = buildJsonObject { put("p_profile_id", profileId) }
            val response = withJwtRefreshRetry {
                postgrest.rpc("get_tracker_tokens", params)
            }
            val rows = response.decodeList<UserTrackerTokensRow>()
            Log.i(TAG, "pulled ${rows.size} tracker token row(s) for profile $profileId")
            rows.forEach { applyRemoteRow(it) }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "pullTokensForActiveProfile failed", e)
            Result.failure(e)
        }
    }

    /**
     * Push the given tracker's current local tokens to Supabase. Called by
     * the per-tracker `*AuthService` after a successful phone-pair login,
     * token refresh, or debug local sign-in.
     */
    suspend fun pushTokens(
        tracker: String,
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long?,
        trackerUserId: String?,
        trackerUsername: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!authManager.isAuthenticated) return@withContext Result.success(Unit)
        try {
            val profileId = profileManager.activeProfileId.value
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_tracker", tracker)
                put("p_access_token", accessToken)
                put("p_refresh_token", refreshToken ?: "")
                put("p_expires_in_seconds", expiresInSeconds ?: 0L)
                put("p_tracker_user_id", trackerUserId ?: "")
                put("p_username", trackerUsername ?: "")
            }
            withJwtRefreshRetry { postgrest.rpc("upsert_tracker_tokens", params) }
            Log.d(TAG, "pushed $tracker tokens for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "pushTokens($tracker) failed", e)
            Result.failure(e)
        }
    }

    /**
     * Remote-delete the tracker row for the active profile. Called from
     * `*AuthService.revokeAndLogout()` alongside the existing local clear.
     */
    suspend fun clearTokens(tracker: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!authManager.isAuthenticated) return@withContext Result.success(Unit)
        try {
            val profileId = profileManager.activeProfileId.value
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_tracker", tracker)
            }
            withJwtRefreshRetry { postgrest.rpc("clear_tracker_tokens", params) }
            Log.d(TAG, "cleared $tracker tokens for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "clearTokens($tracker) failed", e)
            Result.failure(e)
        }
    }

    private suspend fun applyRemoteRow(row: UserTrackerTokensRow) {
        val expiresInSeconds = computeExpiresInSeconds(row.expiresAt)
        when (row.tracker) {
            TRACKER_MAL -> {
                if (row.refreshToken.isNullOrBlank()) return
                val local = malAuthStore.state.first()
                if (!remoteIsNewerThanLocal(row, local.expiresAtEpochMs)) return
                malAuthStore.saveTokens(row.accessToken, row.refreshToken, expiresInSeconds ?: 3600L)
                malAuthStore.saveUser(row.trackerUserId, row.trackerUsername)
            }
            TRACKER_ANILIST -> {
                val local = aniListAuthStore.state.first()
                if (!remoteIsNewerThanLocal(row, local.expiresAtEpochMs)) return
                aniListAuthStore.saveToken(row.accessToken, expiresInSeconds ?: DEFAULT_ANILIST_LIFETIME_SECONDS)
                aniListAuthStore.saveUser(row.trackerUserId, row.trackerUsername)
            }
            TRACKER_KITSU -> {
                if (row.refreshToken.isNullOrBlank()) return
                val local = kitsuAuthStore.state.first()
                if (!remoteIsNewerThanLocal(row, local.expiresAtEpochMs)) return
                kitsuAuthStore.saveTokens(row.accessToken, row.refreshToken, expiresInSeconds ?: DEFAULT_KITSU_LIFETIME_SECONDS)
                kitsuAuthStore.saveUser(row.trackerUserId, row.trackerUsername)
            }
            else -> Log.w(TAG, "ignoring unknown tracker ${row.tracker}")
        }
    }

    /**
     * Apply-if-newer: remote row replaces local only when the remote's
     * derived expiry is newer than the local one. Same token generation is
     * a no-op; a device that already has a refreshed newer token won't be
     * downgraded to an older remote copy.
     */
    private fun remoteIsNewerThanLocal(row: UserTrackerTokensRow, localExpiresAtMs: Long?): Boolean {
        val remoteMs = parseIsoToEpochMs(row.expiresAt) ?: return localExpiresAtMs == null
        val localMs = localExpiresAtMs ?: return true
        return remoteMs > localMs
    }

    private fun computeExpiresInSeconds(isoExpiresAt: String?): Long? {
        val ms = parseIsoToEpochMs(isoExpiresAt) ?: return null
        val delta = ms - System.currentTimeMillis()
        return if (delta > 0) delta / 1000L else null
    }

    private fun parseIsoToEpochMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try { Instant.parse(iso).toEpochMilli() } catch (_: DateTimeParseException) { null }
    }
}

private const val DEFAULT_ANILIST_LIFETIME_SECONDS = 365L * 24 * 60 * 60
private const val DEFAULT_KITSU_LIFETIME_SECONDS = 30L * 24 * 60 * 60
