package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nuvio.tv.core.iptv.CategorySelections
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.recordAdd
import com.nuvio.tv.core.iptv.recordUpdate
import com.nuvio.tv.core.iptv.recordDelete
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the list of configured Xtream IPTV accounts, profile-scoped, as JSON.
 * Mirrors [AddonPreferences]' list-in-DataStore pattern.
 *
 * ponytail: credentials stored in plaintext, same as the existing Debrid API keys.
 * Encrypt-at-rest is the upgrade path if the app ever adds it for Debrid too.
 */
@Singleton
class XtreamAccountStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val gson = Gson()
    private val accountsKey = stringPreferencesKey("xtream_accounts")

    private fun store(pid: Int = profileManager.activeProfileId.value) = factory.get(pid, FEATURE)

    val accounts: Flow<List<XtreamAccount>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> parse(prefs[accountsKey]) }
    }

    /** One explicit-profile read for playback owners that must never follow the active-profile flow. */
    internal suspend fun findForProfile(profileId: Int, accountId: String): XtreamAccount? {
        require(accountId.isNotBlank()) { "Account id must not be blank" }
        return accountsForProfile(profileId)
            .firstOrNull { it.id == accountId }
    }

    /** Explicit-profile snapshot for playback ingress that must not observe an active-profile race. */
    internal suspend fun accountsForProfile(profileId: Int): List<XtreamAccount> {
        require(profileId > 0) { "Profile id must be positive" }
        return factory.get(profileId, FEATURE).data
            .map { prefs -> parse(prefs[accountsKey]) }
            .first()
    }

    /** Insert or replace by id (id = baseUrl|username). */
    suspend fun upsert(account: XtreamAccount) {
        store().edit { prefs ->
            val current = parse(prefs[accountsKey]).toMutableList()
            val i = current.indexOfFirst { it.id == account.id }
            if (i >= 0) current[i] = account else current.add(account)
            prefs[accountsKey] = mergeXtreamAccountsJson(gson, prefs[accountsKey], current)
        }
        recordPending { it.recordAdd(account) }   // B24 v2: durable add intent (add-playlist path)
    }

    /** Swap the account stored under oldId in place (URL/creds edit), keeping list position. */
    suspend fun replace(oldId: String, account: XtreamAccount) {
        store().edit { prefs ->
            val updated = parse(prefs[accountsKey])
                .filterNot { it.id == account.id && it.id != oldId } // drop a pre-existing duplicate of the new identity
                .map { if (it.id == oldId) account else it }
            prefs[accountsKey] = mergeXtreamAccountsJson(gson, prefs[accountsKey], updated)
        }
        if (oldId != account.id) recordPending { it.recordDelete(oldId) }  // identity changed: old id gone
        recordPending { it.recordUpdate(account) }   // B24 v2: durable edit intent
    }

    suspend fun remove(id: String) {
        store().edit { prefs ->
            val original = prefs[accountsKey]
            prefs[accountsKey] = mergeXtreamAccountsJson(gson, original, parse(original).filterNot { it.id == id })
        }
        recordPending { it.recordDelete(id) }   // B24 v2: durable delete intent
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        update(id) { it.copy(enabled = enabled) }
    }

    /**
     * Field-level read-modify-write INSIDE the DataStore edit: [transform] runs against the
     * latest persisted account, so rapid option edits (e.g. "Deselect All" then a toggle) compose
     * instead of a stale UI snapshot clobbering the earlier write.
     */
    suspend fun update(id: String, transform: (XtreamAccount) -> XtreamAccount) {
        store().edit { prefs ->
            prefs[accountsKey] = applyAccountUpdate(gson, prefs[accountsKey], id, transform)
        }
        // recordPending self-gates on the per-profile activation (records for active/paused-adopted,
        // skips for pure-legacy), so no outer v2Enabled gate is needed here.
        accountsForProfile(profileManager.activeProfileId.value).firstOrNull { it.id == id }
            ?.let { updated -> recordPending { it.recordUpdate(updated) } }   // B24 v2: durable field-edit intent
    }

    /** Replace all accounts for the active profile (used when applying a remote pull). */
    suspend fun replaceAll(accounts: List<XtreamAccount>) {
        store().edit { prefs -> prefs[accountsKey] = gson.toJson(accounts) }
    }

    /**
     * Whether the persisted blob for [profileId] may safely full-replace the server (B24). Only a
     * clean present array (Valid, including an explicit `[]` written by a deliberate delete-all)
     * qualifies. A null/blank blob — a fresh install or a DataStore corruption-reset, which writes
     * an EMPTY store rather than `[]` — is Absent and must NOT push an empty full-replace that could
     * wipe another device's server copy; a garbled blob is Corrupt and is withheld likewise.
     * Stateless: the persisted bytes are the source of truth (a delete-all always leaves `[]`).
     */
    suspend fun canPushFullReplace(profileId: Int = profileManager.activeProfileId.value): Boolean {
        val raw = factory.get(profileId, FEATURE).data.map { prefs -> prefs[accountsKey] }.first()
        return decodeXtreamAccountsOutcome(gson, raw).canFullReplace
    }

    /**
     * ONE atomic read of [profileId]'s persisted blob, yielding the push authority AND the outgoing
     * accounts from the SAME bytes (B24 §2). The prior push read authority via [canPushFullReplace]
     * and the payload via the ACTIVE-profile [accounts] flow separately: a profile switch between
     * the two could redirect another profile's accounts under this profile's id, and a concurrent
     * edit between the two reads could desync the authority decision from the payload. Reading the
     * blob once — for the explicitly-passed [profileId] — closes both. The returned snapshot is
     * immutable, so a later local edit cannot change a push already in flight.
     */
    suspend fun pushSnapshot(profileId: Int): PlaylistPushSnapshot {
        val raw = factory.get(profileId, FEATURE).data.map { prefs -> prefs[accountsKey] }.first()
        val outcome = decodeXtreamAccountsOutcome(gson, raw)
        return PlaylistPushSnapshot(profileId, outcome.canFullReplace, outcome.accounts)
    }

    private fun parse(json: String?): List<XtreamAccount> = decodeXtreamAccountsJson(gson, json)

    // --- B24 v2 sync state (revision + mutationId + pending ops) ---------------------------------
    // Stored in a SEPARATE DataStore feature file from the accounts blob, so a reset/corruption of
    // the accounts store (the B24 corruption model) does NOT drop the pending "add C" or the baseline
    // revision. NOTE: this survives an accounts-store reset, NOT a wipe of the WHOLE DataStore
    // directory (both files) — see the sync engine's guard: a missing sync-state + non-authoritative
    // local store adopts the server and never pushes, so even a whole-store loss cannot wipe A+B.
    private val syncStateKey = stringPreferencesKey("xtream_sync_state")

    internal suspend fun loadPlaylistSyncStateRaw(profileId: Int): String? =
        factory.get(profileId, SYNC_STATE_FEATURE).data.map { it[syncStateKey] }.first()

    internal suspend fun savePlaylistSyncStateRaw(profileId: Int, json: String) {
        factory.get(profileId, SYNC_STATE_FEATURE).edit { it[syncStateKey] = json }
    }

    /** Append a user mutation to the durable pending-op log (gated: only when the v2 path is active). */
    private suspend fun recordPending(transform: (List<com.nuvio.tv.core.iptv.PendingOpDto>) -> List<com.nuvio.tv.core.iptv.PendingOpDto>) {
        val pid = profileManager.activeProfileId.value
        val cur = com.nuvio.tv.core.iptv.decodePlaylistSyncState(gson, loadPlaylistSyncStateRaw(pid))
        // Adopted (revision advanced or pending held) profiles keep recording even while paused, so
        // intent is never dropped; a never-adopted profile on v1 records nothing (B24 activation).
        val adopted = cur.revision > 0 || cur.pending.isNotEmpty()
        if (!com.nuvio.tv.core.iptv.PlaylistSyncConfig.recordsPending(adopted)) return
        savePlaylistSyncStateRaw(pid, com.nuvio.tv.core.iptv.encodePlaylistSyncState(gson, cur.copy(pending = transform(cur.pending))))
    }

    companion object {
        private const val FEATURE = "xtream_accounts"
        private const val SYNC_STATE_FEATURE = "xtream_sync_state"
    }
}

/** [XtreamAccountStore.update]'s transform application against the LATEST persisted JSON.
 *  Extracted so the compose-against-latest-state behavior is unit-testable. */
internal fun applyAccountUpdate(
    gson: Gson,
    json: String?,
    id: String,
    transform: (XtreamAccount) -> XtreamAccount
): String = mergeXtreamAccountsJson(
    gson,
    json,
    decodeXtreamAccountsJson(gson, json).map { if (it.id == id) transform(it) else it }
)

/**
 * The outcome of decoding the persisted account blob, with explicit validity so the sync push
 * never mistakes an absent/reset store or an unreadable blob for a genuine empty collection (B24).
 * Only [Valid] (a clean present array, including an explicit `[]`) may full-replace the server.
 *
 *  - [Valid]     — a clean array; `[]` is a deliberate delete-all and MUST still push.
 *  - [Recovered] — the array parsed but some element(s) failed; usable rows recovered, subset must
 *    not full-replace.
 *  - [Corrupt]   — present but not a decodable array.
 *  - [Absent]    — null/blank: fresh install, cleared store, or a DataStore corruption-reset (which
 *    writes an EMPTY store, not `[]`). Empty but NOT user-authored — pushing it could wipe another
 *    device's server copy.
 */
internal sealed interface XtreamAccountLoadOutcome {
    val accounts: List<XtreamAccount>
    data class Valid(override val accounts: List<XtreamAccount>) : XtreamAccountLoadOutcome
    data class Recovered(override val accounts: List<XtreamAccount>, val droppedCount: Int) : XtreamAccountLoadOutcome
    data object Corrupt : XtreamAccountLoadOutcome {
        override val accounts: List<XtreamAccount> get() = emptyList()
    }
    data object Absent : XtreamAccountLoadOutcome {
        override val accounts: List<XtreamAccount> get() = emptyList()
    }
}

/** Only a clean, complete decode may full-replace the server (B24). */
internal val XtreamAccountLoadOutcome.canFullReplace: Boolean
    get() = this is XtreamAccountLoadOutcome.Valid

/**
 * An immutable, atomically-captured outgoing-push snapshot (B24 §2): the target [profileId], whether
 * the persisted blob is authoritative enough to full-replace, and the exact accounts to send — all
 * derived from ONE read of that profile's blob. Because it is a value snapshot, a profile switch or
 * a local edit occurring during the network push cannot redirect it or change its payload.
 */
data class PlaylistPushSnapshot(
    val profileId: Int,
    val canFullReplace: Boolean,
    val accounts: List<XtreamAccount>,
)

/**
 * Element-wise decode: a single incompatible row recovers the rest instead of collapsing the whole
 * list, a null/blank blob is [XtreamAccountLoadOutcome.Absent] (a fresh/reset store, not a genuine
 * empty), and a non-array blob is [XtreamAccountLoadOutcome.Corrupt]. Applies the same
 * missing-primitive defaults as before. Extracted so the classification is unit-testable.
 */
internal fun decodeXtreamAccountsOutcome(gson: Gson, json: String?): XtreamAccountLoadOutcome {
    if (json.isNullOrBlank()) return XtreamAccountLoadOutcome.Absent
    val raw = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull()
        ?: return XtreamAccountLoadOutcome.Corrupt
    var dropped = 0
    val accounts = raw.mapNotNull { element ->
        runCatching {
            val obj = element.asJsonObject
            gson.fromJson(element, XtreamAccount::class.java).withDecodeDefaults(
                hadAutoRefresh = obj.has("autoRefreshHours"),
                hadSendDeviceId = obj.has("sendDeviceId"),
                hadPreferM3u8CatchUp = obj.has("preferM3u8CatchUp"),
                hadCatchUpCorrection = obj.has("catchUpCorrectionMinutes"),
                hadGuideEpgCorrection = obj.has("guideEpgCorrectionMinutes")
            )
        }.getOrElse { dropped++; null }
    }
    return if (dropped == 0) {
        XtreamAccountLoadOutcome.Valid(accounts)
    } else {
        XtreamAccountLoadOutcome.Recovered(accounts, dropped)
    }
}

/**
 * Decodes the persisted account list. Existing callers (the read flow) just need the account list;
 * a Corrupt/Absent decode yields an empty list and a Recovered decode yields its usable rows, so
 * the read behavior is unchanged. The sync push uses [decodeXtreamAccountsOutcome] directly to tell
 * an absent/corrupt store from a genuine empty one.
 */
internal fun decodeXtreamAccountsJson(gson: Gson, json: String?): List<XtreamAccount> =
    decodeXtreamAccountsOutcome(gson, json).accounts

/**
 * Re-encode [accounts] for local storage while PRESERVING any per-row keys this build's schema does
 * not know (a forward-compat field a newer build wrote), matched to the original row by stable `id`
 * (B24 §3 — the TV twin of Mobile/Desktop's mergePlaylistJson). Each row is the NORMAL Gson encoding
 * (so the known-field on-disk format, including omitted nulls, is unchanged) plus only the original
 * row's UNKNOWN keys. A cleared known field is a KNOWN key, so it is never restored from the
 * original — its absence from the fresh encoding correctly clears it. A new row (no original) is
 * encoded fresh; a removed row is simply not emitted. Local storage only: the sync push + fixed
 * backend columns cannot carry an unknown field regardless.
 */
internal fun mergeXtreamAccountsJson(gson: Gson, originalStored: String?, accounts: List<XtreamAccount>): String {
    val originalById: Map<String, com.google.gson.JsonObject> =
        runCatching { JsonParser.parseString(originalStored ?: "").asJsonArray }.getOrNull()
            ?.mapNotNull { el -> runCatching { el.asJsonObject }.getOrNull() }
            ?.mapNotNull { obj -> obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.let { it to obj } }
            ?.toMap()
            .orEmpty()
    // serializeNulls emits EVERY declared field, so its key set is the full known-schema key set;
    // anything in the original NOT in it is a genuine unknown to preserve.
    val gsonNulls = gson.newBuilder().serializeNulls().create()
    val out = com.google.gson.JsonArray()
    accounts.forEach { acc ->
        val fresh = gson.toJsonTree(acc).asJsonObject
        val originalRow = originalById[acc.id]
        if (originalRow != null) {
            val knownKeys = gsonNulls.toJsonTree(acc).asJsonObject.keySet()
            originalRow.entrySet().forEach { (key, value) ->
                if (key !in knownKeys && !fresh.has(key)) fresh.add(key, value)
            }
        }
        out.add(fresh)
    }
    return gson.toJson(out)
}

/**
 * Gson instantiates [XtreamAccount] via Unsafe (no no-arg constructor), so Kotlin constructor
 * defaults do NOT apply — fields missing from previously-persisted JSON come back null even on
 * non-null types. Re-apply the defaults here. The elvis operators look useless to the compiler
 * but are load-bearing at runtime. (Can't use copy(): its non-null params null-check the current
 * field values and would throw.)
 */
@Suppress("USELESS_ELVIS")
private fun XtreamAccount.withDecodeDefaults(
    hadAutoRefresh: Boolean,
    hadSendDeviceId: Boolean,
    hadPreferM3u8CatchUp: Boolean,
    hadCatchUpCorrection: Boolean,
    hadGuideEpgCorrection: Boolean
): XtreamAccount = XtreamAccount(
    id = id,
    name = name ?: "",
    baseUrl = baseUrl,
    username = username,
    password = password,
    enabled = enabled,
    sourceType = sourceType ?: XtreamAccount.SOURCE_XTREAM,
    userAgent = userAgent,   // nullable; missing in older JSON -> null, which is the default anyway
    epgUrl = epgUrl,
    dnsProvider = dnsProvider ?: XtreamAccount.DNS_SYSTEM,
    // Missing (pre-playlist-manager JSON) → the 24h default, like a freshly-added playlist;
    // present → keep the stored value (incl. a deliberate 0 = Off).
    autoRefreshHours = if (hadAutoRefresh) autoRefreshHours else XtreamAccount.DEFAULT_AUTO_REFRESH_HOURS,
    contentTypes = contentTypes ?: XtreamAccount.DEFAULT_CONTENT_TYPES,
    categorySelections = categorySelections ?: CategorySelections(),
    fileName = fileName,   // nullable; missing in older JSON -> null, which is the default anyway
    // Stalker fields: missing in pre-P4 JSON -> Unsafe leaves them null on non-null types; re-apply
    // the constructor defaults (elvis is load-bearing at runtime, useless to the compiler).
    portalUrl = portalUrl ?: "",
    macAddress = macAddress ?: "",
    stalkerUsername = stalkerUsername ?: "",
    stalkerPassword = stalkerPassword ?: "",
    serialNumber = serialNumber ?: "",
    deviceId = deviceId ?: "",
    // sendDeviceId is a primitive boolean: Gson can't tell missing from an explicit false. Present ->
    // keep the stored value (incl. a deliberate false); missing (pre-P4 JSON) -> the `true` default.
    sendDeviceId = if (hadSendDeviceId) sendDeviceId else true,
    // Catch-up preferences: same primitive problem as sendDeviceId/autoRefreshHours — missing
    // (any JSON written before catch-up shipped) must read as the default, not as false/0, and an
    // explicitly stored false/0 must survive.
    preferM3u8CatchUp = if (hadPreferM3u8CatchUp) preferM3u8CatchUp else false,
    catchUpCorrectionMinutes = if (hadCatchUpCorrection) catchUpCorrectionMinutes else 0,
    // Guide EPG offset (fix 2): missing = 0 = auto-detect, the default every stored playlist gets.
    guideEpgCorrectionMinutes = if (hadGuideEpgCorrection) guideEpgCorrectionMinutes else 0
)
