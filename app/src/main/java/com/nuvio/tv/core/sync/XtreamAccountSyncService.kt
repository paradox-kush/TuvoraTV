package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.iptv.CategorySelections
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.remote.supabase.SupabaseIptvPlaylist
import com.nuvio.tv.data.remote.supabase.SupabaseXtreamAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "XtreamAccountSyncService"

/**
 * Syncs IPTV playlists (Xtream accounts + playlist-manager options) per profile to Supabase,
 * mirroring AddonSyncService. Push = full-replace RPC on change (debounced from the settings VM);
 * pull = direct RLS-scoped select on login. Empty remote + non-empty local => migrate local up.
 *
 * v2 (playlist manager P1): push/pull target the `iptv_playlists` table + RPC. Pull falls back
 * to the legacy `xtream_accounts` table when `iptv_playlists` is empty, applies the legacy rows
 * locally (defaults for the new option fields) and pushes them up to the NEW table — a one-way
 * upgrade per spec §12; the legacy RPC is never written again by this client.
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
                put("p_playlists", buildJsonArray {
                    accounts.forEachIndexed { index, acc -> add(playlistPushJson(acc, index)) }
                })
                put("p_profile_id", profileId)
            }
            withJwtRefreshRetry { postgrest.rpc("sync_push_iptv_playlists", params) }
            Log.d(TAG, "Pushed ${accounts.size} iptv playlists for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push iptv playlists", e)
            Result.failure(e)
        }
    }

    /**
     * Pull this profile's playlists and apply them locally.
     * `iptv_playlists` empty => fall back to the legacy `xtream_accounts` select and, when legacy
     * rows exist, migrate them up to the new table. Both empty + non-empty local => push local up.
     */
    suspend fun pullAndApply(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                ?: return@withContext Result.failure(
                    IllegalStateException("Unable to resolve sync owner for xtream sync")
                )
            val profileId = profileManager.activeProfileId.value
            val rows = withJwtRefreshRetry {
                postgrest.from("iptv_playlists")
                    .select { filter {
                        eq("user_id", effectiveUserId)
                        eq("profile_id", profileId)
                    } }
                    .decodeList<SupabaseIptvPlaylist>()
            }
            if (rows.isNotEmpty()) {
                val accounts = rows.sortedBy { it.sortOrder }.mapNotNull { it.toAccount() }
                applyRemote(accounts)
                Log.d(TAG, "Pulled ${accounts.size} iptv playlists for profile $profileId")
                return@withContext Result.success(Unit)
            }

            // Legacy fallback: rows written by pre-playlist-manager clients.
            val legacy = withJwtRefreshRetry {
                postgrest.from("xtream_accounts")
                    .select { filter {
                        eq("user_id", effectiveUserId)
                        eq("profile_id", profileId)
                    } }
                    .decodeList<SupabaseXtreamAccount>()
            }
            if (legacy.isNotEmpty()) {
                val accounts = legacy.sortedBy { it.sortOrder }.map {
                    XtreamAccount(
                        id = "${it.baseUrl}|${it.username}",
                        name = it.name ?: it.baseUrl,
                        baseUrl = it.baseUrl,
                        username = it.username,
                        password = it.password,
                        enabled = it.enabled,
                        // new playlist options take their defaults
                    )
                }
                applyRemote(accounts)
                // One-way migration: copy the legacy rows into the new table.
                pushToRemote()
                Log.d(TAG, "Migrated ${accounts.size} legacy xtream accounts to iptv_playlists for profile $profileId")
                return@withContext Result.success(Unit)
            }

            if (accountStore.accounts.first().isNotEmpty()) pushToRemote()
            Result.success(Unit)
        } catch (e: Exception) {
            isSyncingFromRemote = false
            Log.e(TAG, "Failed to pull iptv playlists", e)
            Result.failure(e)
        }
    }

    private suspend fun applyRemote(accounts: List<XtreamAccount>) {
        isSyncingFromRemote = true
        accountStore.replaceAll(accounts)
        isSyncingFromRemote = false
    }

    /** P1: this client only understands xtream sources; other source types stay remote-only. */
    private fun SupabaseIptvPlaylist.toAccount(): XtreamAccount? {
        if (sourceType != XtreamAccount.SOURCE_XTREAM) return null
        val base = baseUrl ?: return null
        val user = username ?: return null
        val pass = password ?: return null
        return XtreamAccount(
            id = "$base|$user",
            name = name ?: base,
            baseUrl = base,
            username = user,
            password = pass,
            enabled = enabled,
            sourceType = sourceType,
            epgUrl = epgUrl,
            dnsProvider = dnsProvider,
            autoRefreshHours = autoRefreshHours,
            contentTypes = contentTypes.toSet(),
            categorySelections = decodeCategorySelections(categorySelections)
        )
    }
}

/**
 * One playlist row of the `sync_push_iptv_playlists` payload. Contract (must match the
 * migration RPC in nuvio-backend `20260702000000_iptv_playlists.sql`): source_type, name
 * (omitted when blank), enabled, sort_order, base_url, username, password, epg_url (omitted
 * when null), dns_provider, auto_refresh_hours, content_types (array of strings),
 * category_selections (object with live/movies/series arrays, each omitted when null;
 * whole object omitted when all three are null).
 */
internal fun playlistPushJson(acc: XtreamAccount, sortOrder: Int): JsonObject = buildJsonObject {
    put("source_type", acc.sourceType)
    if (acc.name.isNotBlank()) put("name", acc.name)
    put("enabled", acc.enabled)
    put("sort_order", sortOrder)
    put("base_url", acc.baseUrl)
    put("username", acc.username)
    put("password", acc.password)
    acc.epgUrl?.let { put("epg_url", it) }
    put("dns_provider", acc.dnsProvider)
    put("auto_refresh_hours", acc.autoRefreshHours)
    put("content_types", buildJsonArray { acc.contentTypes.forEach { add(it) } })
    val sel = acc.categorySelections
    if (!sel.allNull) {
        put("category_selections", buildJsonObject {
            sel.live?.let { put("live", JsonArray(it.map(::JsonPrimitive))) }
            sel.movies?.let { put("movies", JsonArray(it.map(::JsonPrimitive))) }
            sel.series?.let { put("series", JsonArray(it.map(::JsonPrimitive))) }
        })
    }
}

/** Lenient jsonb -> CategorySelections: anything malformed degrades to "all categories". */
internal fun decodeCategorySelections(element: JsonElement?): CategorySelections {
    val obj = element as? JsonObject ?: return CategorySelections()
    fun list(key: String): List<String>? = (obj[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
    return CategorySelections(live = list("live"), movies = list("movies"), series = list("series"))
}
