package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.remote.supabase.SupabaseXtreamAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "XtreamAccountSyncService"

/**
 * Syncs Xtream IPTV accounts (playlists) per profile to Supabase, mirroring AddonSyncService.
 * Push = full-replace RPC on change (debounced from the settings VM); pull = direct RLS-scoped
 * select on login. Empty remote + non-empty local => migrate local up.
 */
@Singleton
class XtreamAccountSyncService @Inject constructor(
    private val supabaseProvider: SyncBackendSupabaseProvider,
    private val authManager: AuthManager,
    private val accountStore: XtreamAccountStore,
    private val profileManager: ProfileManager,
) {
    private val postgrest get() = supabaseProvider.postgrest
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null

    var isSyncingFromRemote: Boolean = false

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    /** Debounced push after a local account change (called from XtreamSettingsViewModel). */
    fun triggerRemoteSync() {
        if (isSyncingFromRemote || !authManager.isAuthenticated) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(600)
            if (!isSyncingFromRemote) pushToRemote()
        }
    }

    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val accounts = accountStore.accounts.first()
            val params = buildJsonObject {
                put("p_accounts", buildJsonArray {
                    accounts.forEachIndexed { index, acc ->
                        addJsonObject {
                            put("base_url", acc.baseUrl)
                            put("username", acc.username)
                            put("password", acc.password)
                            if (acc.name.isNotBlank()) put("name", acc.name)
                            put("enabled", acc.enabled)
                            put("sort_order", index)
                        }
                    }
                })
                put("p_profile_id", profileId)
            }
            withJwtRefreshRetry { postgrest.rpc("sync_push_xtream_accounts", params) }
            Log.d(TAG, "Pushed ${accounts.size} xtream accounts for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push xtream accounts", e)
            Result.failure(e)
        }
    }

    /** Pull this profile's playlists and apply them locally. Empty remote + non-empty local => migrate up. */
    suspend fun pullAndApply(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                ?: return@withContext Result.failure(
                    IllegalStateException("Unable to resolve sync owner for xtream sync")
                )
            val profileId = profileManager.activeProfileId.value
            val rows = withJwtRefreshRetry {
                postgrest.from("xtream_accounts")
                    .select { filter {
                        eq("user_id", effectiveUserId)
                        eq("profile_id", profileId)
                    } }
                    .decodeList<SupabaseXtreamAccount>()
            }
            if (rows.isEmpty()) {
                if (accountStore.accounts.first().isNotEmpty()) pushToRemote()
                return@withContext Result.success(Unit)
            }
            val accounts = rows.sortedBy { it.sortOrder }.map {
                XtreamAccount(
                    id = "${it.baseUrl}|${it.username}",
                    name = it.name ?: it.baseUrl,
                    baseUrl = it.baseUrl,
                    username = it.username,
                    password = it.password,
                    enabled = it.enabled,
                )
            }
            isSyncingFromRemote = true
            accountStore.replaceAll(accounts)
            isSyncingFromRemote = false
            Log.d(TAG, "Pulled ${accounts.size} xtream accounts for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            isSyncingFromRemote = false
            Log.e(TAG, "Failed to pull xtream accounts", e)
            Result.failure(e)
        }
    }
}
