package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.iptv.CategorySelections
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.m3uAccountFromUrl
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
 * to the legacy `xtream_accounts` table when `iptv_playlists` has no xtream rows, applies the
 * legacy rows locally (defaults for the new option fields) and pushes them up to the NEW table —
 * a one-way upgrade per spec §12. After a successful migration push the legacy rows are cleared
 * (one write of `[]` to the old RPC) so the fallback is one-shot; the legacy RPC is never
 * written otherwise.
 */
@Singleton
class XtreamAccountSyncService @Inject constructor(
    private val supabaseProvider: SyncBackendSupabaseProvider,
    private val authManager: AuthManager,
    private val accountStore: XtreamAccountStore,
    private val profileManager: ProfileManager,
    private val purge: com.nuvio.tv.core.iptv.IptvAccountPurge,
    private val resolver: com.nuvio.tv.core.iptv.match.XtreamTmdbResolver,
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

    /** [onlyIfEmpty] is the legacy-migration guard: the loser of a two-device first-login
     *  race becomes a no-op instead of a full-replace with stale legacy rows. */
    suspend fun pushToRemote(onlyIfEmpty: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val accounts = accountStore.accounts.first()
            val params = playlistPushParams(accounts, profileId, onlyIfEmpty)
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
     * No usable (xtream) rows in `iptv_playlists` => fall back to the legacy `xtream_accounts`
     * select and, when legacy rows exist, migrate them up to the new table (then clear them).
     * Both empty + non-empty local => push local up (source-type-scoped, so it can't delete
     * foreign rows).
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
            // Emptiness is decided AFTER filtering to rows this client understands: a table
            // holding only foreign source types (a future client's) must behave exactly like an
            // empty remote — applying an empty list would wipe local state.
            val remoteAccounts = rows.sortedBy { it.sortOrder }.mapNotNull { it.toXtreamAccountOrNull() }
            if (remoteAccounts.isNotEmpty()) {
                applyRemote(reconcileLocalIds(remoteAccounts, accountStore.accounts.first()))
                Log.d(TAG, "Pulled ${remoteAccounts.size} iptv playlists for profile $profileId")
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
                // One-way migration: copy the legacy rows into the new table (only-if-empty so
                // the loser of a two-device first-login race is a no-op), then clear the legacy
                // rows — a stale legacy copy would resurrect deleted playlists on every login.
                if (pushToRemote(onlyIfEmpty = true).isSuccess) clearLegacyRemote(profileId)
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
        val before = runCatching { accountStore.accounts.first() }.getOrDefault(emptyList())
        accountStore.replaceAll(accounts)
        isSyncingFromRemote = false
        // Playlists removed on another device: drop their local caches (indexes, ingested
        // catalog, session caches). Saved user data is untouched — see IptvAccountPurge.
        val remaining = accounts.map { it.id }.toSet()
        before.filter { it.id !in remaining }.forEach { runCatching { purge.purgeCaches(it.id) } }
        // Playlists added on another device: index them now, not on first play.
        resolver.warmUp(accounts)
    }

    /** One-shot legacy cleanup after a successful migration push: empty the old
     *  `xtream_accounts` rows so they can't resurrect deleted playlists on a later login.
     *  Best-effort — a failure just leaves the (idempotent) migration to run again. */
    private suspend fun clearLegacyRemote(profileId: Int) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_accounts", JsonArray(emptyList()))
            }
            withJwtRefreshRetry { postgrest.rpc("sync_push_xtream_accounts", params) }
            Log.d(TAG, "Cleared legacy xtream_accounts rows for profile $profileId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear legacy xtream_accounts rows", e)
        }
    }
}

// Wire (backend column enum) <-> internal source-type mapping. The table stores
// xtream | m3u_url | m3u_file | stalker; this client's internal spellings for M3U are
// SOURCE_URL ("url") / SOURCE_FILE ("file"). Mobile already writes the canonical names.
private const val WIRE_M3U_URL = "m3u_url"
private const val WIRE_M3U_FILE = "m3u_file"

internal fun wireSourceType(internalType: String): String = when (internalType) {
    XtreamAccount.SOURCE_URL -> WIRE_M3U_URL
    XtreamAccount.SOURCE_FILE -> WIRE_M3U_FILE
    else -> internalType
}

/**
 * Maps a sync row to a local account for every source type this client understands; null for
 * malformed rows and unknown (future) source types — those stay remote-only, and the push scope
 * (p_source_types) guarantees we never delete them. Ids are re-derived with the same builders
 * the settings form uses, so a pulled playlist matches a hand-added one. The internal "url"/"file"
 * spellings are accepted as aliases beside the canonical wire names (defensive).
 */
internal fun SupabaseIptvPlaylist.toXtreamAccountOrNull(): XtreamAccount? {
    val base: XtreamAccount = when (sourceType) {
        XtreamAccount.SOURCE_XTREAM -> {
            val baseUrl = baseUrl ?: return null
            val user = username ?: return null
            val pass = password ?: return null
            XtreamAccount(
                id = "$baseUrl|$user",
                name = name ?: baseUrl,
                baseUrl = baseUrl,
                username = user,
                password = pass,
                enabled = enabled,
                sourceType = XtreamAccount.SOURCE_XTREAM
            )
        }
        WIRE_M3U_URL, XtreamAccount.SOURCE_URL -> {
            val playlistUrl = (url ?: baseUrl)?.takeIf { it.isNotBlank() } ?: return null
            // UA rides `user_agent` (canonical) with `username` as the legacy stash this client
            // itself used to write.
            m3uAccountFromUrl(
                playlistUrl,
                userAgent = (userAgent ?: username)?.takeIf { it.isNotBlank() },
                name = name
            )?.copy(enabled = enabled) ?: return null
        }
        WIRE_M3U_FILE, XtreamAccount.SOURCE_FILE -> {
            // File BYTES are never synced — this lands as a re-import ghost. Deterministic id so
            // repeated pulls are stable; reconcileLocalIds keeps the local id (and with it the
            // local file copy) when this device already has the playlist.
            val fn = (fileName ?: name)?.takeIf { it.isNotBlank() } ?: "Playlist"
            XtreamAccount(
                id = "file:synced-$fn",
                name = name ?: fn.substringBeforeLast('.'),
                baseUrl = "",
                username = "",
                password = "",
                enabled = enabled,
                sourceType = XtreamAccount.SOURCE_FILE,
                fileName = fn
            )
        }
        XtreamAccount.SOURCE_STALKER -> {
            val portal = (portalUrl ?: baseUrl)?.takeIf { it.isNotBlank() } ?: return null
            val mac = macAddress?.takeIf { it.isNotBlank() } ?: return null
            XtreamAccount(
                id = "stalker|$portal|$mac",
                name = name ?: portal,
                baseUrl = portal,
                username = "",
                password = "",
                enabled = enabled,
                sourceType = XtreamAccount.SOURCE_STALKER,
                portalUrl = portal,
                macAddress = mac,
                stalkerUsername = stalkerUsername.orEmpty(),
                stalkerPassword = stalkerPassword.orEmpty(),
                serialNumber = serialNumber.orEmpty(),
                deviceId = deviceId.orEmpty(),
                sendDeviceId = sendDeviceId
            )
        }
        else -> return null
    }
    return base.copy(
        epgUrl = epgUrl,
        dnsProvider = dnsProvider,
        autoRefreshHours = autoRefreshHours,
        contentTypes = contentTypes.toSet(),
        categorySelections = decodeCategorySelections(categorySelections)
    )
}

/**
 * Keeps this device's account id when a pulled account is the same playlist under a different id.
 * Only file playlists need it: their locally-minted id is a random `file:{uuid}` (the local copy
 * lives at `{id}.m3u`), while a pulled ghost has the deterministic `file:synced-` id — matching by
 * fileName preserves the local copy + saved content keys. Every other source type derives ids
 * deterministically, so pulled == local already.
 */
internal fun reconcileLocalIds(pulled: List<XtreamAccount>, local: List<XtreamAccount>): List<XtreamAccount> =
    pulled.map { acc ->
        if (acc.sourceType != XtreamAccount.SOURCE_FILE) return@map acc
        val match = local.firstOrNull { it.sourceType == XtreamAccount.SOURCE_FILE && it.fileName == acc.fileName }
        if (match != null) acc.copy(id = match.id) else acc
    }

/** The wire source types this client fully understands — the push's full-replace scope. Unknown
 *  (future) types stay outside the scope, so they can never be deleted by this client. */
internal val SYNCED_SOURCE_TYPES = listOf(
    XtreamAccount.SOURCE_XTREAM, WIRE_M3U_URL, WIRE_M3U_FILE, XtreamAccount.SOURCE_STALKER
)

/**
 * Full parameter object for `sync_push_iptv_playlists`. Every push scopes the full-replace with
 * the source types this client understands, so it never deletes rows of a type it doesn't
 * (written by a newer client). `p_only_if_empty` is sent only on the legacy-migration push.
 */
internal fun playlistPushParams(accounts: List<XtreamAccount>, profileId: Int, onlyIfEmpty: Boolean): JsonObject =
    buildJsonObject {
        put("p_playlists", buildJsonArray {
            accounts.forEachIndexed { index, acc -> add(playlistPushJson(acc, index)) }
        })
        put("p_profile_id", profileId)
        put("p_source_types", buildJsonArray { SYNCED_SOURCE_TYPES.forEach { add(it) } })
        if (onlyIfEmpty) put("p_only_if_empty", true)
    }

/**
 * One playlist row of the `sync_push_iptv_playlists` payload. Contract (must match the
 * migration RPC in nuvio-backend `20260702210000_iptv_playlists.sql`): source_type is the WIRE
 * name (m3u_url/m3u_file for this client's url/file); per-type extras ride their dedicated
 * columns (url/user_agent, file_name, portal_url/mac_address/stalker_*); name is omitted when
 * blank, epg_url when null, category_selections when all-null — the RPC's coalesce defaults apply.
 */
internal fun playlistPushJson(acc: XtreamAccount, sortOrder: Int): JsonObject = buildJsonObject {
    put("source_type", wireSourceType(acc.sourceType))
    if (acc.name.isNotBlank()) put("name", acc.name)
    put("enabled", acc.enabled)
    put("sort_order", sortOrder)
    put("base_url", acc.baseUrl)
    put("username", acc.username)
    put("password", acc.password)
    when (acc.sourceType) {
        XtreamAccount.SOURCE_URL -> {
            put("url", acc.baseUrl)                                    // the playlist URL IS the base
            acc.username.takeIf { it.isNotBlank() }?.let { put("user_agent", it) }   // UA lives in username
        }
        XtreamAccount.SOURCE_FILE -> {
            acc.fileName?.takeIf { it.isNotBlank() }?.let { put("file_name", it) }   // metadata only
        }
        XtreamAccount.SOURCE_STALKER -> {
            put("portal_url", acc.portalUrl)
            put("mac_address", acc.macAddress)
            acc.stalkerUsername.takeIf { it.isNotBlank() }?.let { put("stalker_username", it) }
            acc.stalkerPassword.takeIf { it.isNotBlank() }?.let { put("stalker_password", it) }
            acc.serialNumber.takeIf { it.isNotBlank() }?.let { put("serial_number", it) }
            acc.deviceId.takeIf { it.isNotBlank() }?.let { put("device_id", it) }
            put("send_device_id", acc.sendDeviceId)
        }
    }
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
