package com.nuvio.tv.core.iptv

import com.google.gson.Gson
import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOp
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOps
import com.nuvio.tv.core.mediaserver.api.MediaServerSyncBinding
import com.nuvio.tv.core.mediaserver.api.MediaServerSyncCodec

/**
 * B24 — the real app-level v2 sync engine for NuvioTV (twin of Mobile/Desktop's PlaylistV2Sync).
 * The engine logic (pull authoritative rows+revision → reconcile pending intent → push under the
 * revision contract with a stable, restart-persistent mutation id → clear acknowledged ops) is
 * identical; only the serialization uses Gson (TV's storage convention) instead of kotlinx. The
 * sync-state lives under its own DataStore key so it survives an accounts-store corruption reset.
 */

/** A durable, per-profile pending op (collapsed to the latest intent per id). */
data class PendingOpDto(
    val kind: String = "",           // "add" | "update" | "replace" | "delete"
    val id: String = "",
    val account: XtreamAccount? = null,
    /** The row as last synced, before this device's edit — the field-level merge's reference (B60).
     *  Additive: logs written by older builds decode with null (whole-row semantics). */
    val base: XtreamAccount? = null,
    /** For "replace" only: the id the edited playlist had before its URL/username/MAC changed. */
    val oldId: String? = null,
)

data class PlaylistSyncState(
    val revision: Long = 0,
    val mutationId: String? = null,
    // The payload mutationId was minted for (PlaylistMutationIdPolicy.fingerprint). Null for an id
    // persisted by a build that stored the id alone — such an id is never reused.
    val mutationFingerprint: String? = null,
    val pending: List<PendingOpDto> = emptyList(),
    val deleteAllIntent: Boolean = false,
    // The profile-lifetime generation this state (and its pending ops) is anchored to. Bumped by the
    // backend on a profile deletion; when a pull reports a newer generation, the pending ops belong to
    // a now-dead profile lifetime and are discarded rather than replayed onto the recreated profile.
    val generation: Long = 0,
    /**
     * Wave 3: the pending intent on this profile's Jellyfin/Emby SERVER ENTRIES, as a kotlinx-serialization JSON
     * string (read it through [mediaServerPending]). They ride the SAME engine and revision counter as the
     * playlists (a second sync loop would conflict with this one on every push). A string, not nested Gson
     * objects: Gson skips Kotlin constructors, so a field added to an entry later would decode as null; the
     * kotlinx decoder applies defaults. Additive: state written by an older build decodes with none.
     */
    val mediaServerPendingJson: String? = null,
)

private val mediaPendingJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val mediaPendingSerializer = kotlinx.serialization.builtins.ListSerializer(MediaServerPendingOp.serializer())

/** The pending media-server intent (empty when none / unreadable - an unreadable log is never replayed). */
val PlaylistSyncState.mediaServerPending: List<MediaServerPendingOp>
    get() = mediaServerPendingJson?.let { runCatching { mediaPendingJson.decodeFromString(mediaPendingSerializer, it) }.getOrNull() }.orEmpty()

fun PlaylistSyncState.withMediaServerPending(ops: List<MediaServerPendingOp>): PlaylistSyncState =
    copy(mediaServerPendingJson = ops.takeIf { it.isNotEmpty() }?.let { mediaPendingJson.encodeToString(mediaPendingSerializer, it) })

private fun PendingOpDto.toOp(): PendingPlaylistOp? = when (kind) {
    "add" -> account?.let { PendingPlaylistOp.Add(it) }
    "update" -> account?.let { PendingPlaylistOp.Update(it, base) }
    "replace" -> account?.let { acc -> oldId?.let { PendingPlaylistOp.Replace(it, acc, base) } }   // B60
    "delete" -> PendingPlaylistOp.Delete(id)
    else -> null
}

internal fun List<PendingOpDto>.toOps(): List<PendingPlaylistOp> = mapNotNull { it.toOp() }

internal fun List<PendingOpDto>.recordAdd(account: XtreamAccount): List<PendingOpDto> =
    filterNot { it.id == account.id } + PendingOpDto("add", account.id, account)

internal fun List<PendingOpDto>.recordUpdate(account: XtreamAccount, base: XtreamAccount? = null): List<PendingOpDto> {
    val existing = firstOrNull { it.id == account.id }
    val entry = when (existing?.kind) {
        "add" -> PendingOpDto("add", account.id, account)
        // Still the same pending replace — only the edited row moves on; its old id and base stay.
        "replace" -> existing.copy(account = account)
        // Keep the FIRST base: it is the row as last synced, so every field edited since is applied.
        "update" -> PendingOpDto("update", account.id, account, base = existing.base)
        else -> PendingOpDto("update", account.id, account, base = base)
    }
    return filterNot { it.id == account.id } + entry
}

/**
 * B60 — the user edited a playlist's URL / username / MAC, so its id changed from [oldId] to
 * [account]'s. Recorded as ONE replace op (never delete + update: that pair deleted the playlist, and
 * for the only one pushed a delete-all). Collapse rules: a replace of a local add is an add of the new
 * row; a chain A→B→C is Replace(A, C) with A's base; a pending update of [oldId] folds in.
 */
internal fun List<PendingOpDto>.recordReplace(oldId: String, account: XtreamAccount, base: XtreamAccount? = null): List<PendingOpDto> {
    if (oldId == account.id) return recordUpdate(account, base)
    val existing = firstOrNull { it.id == oldId }
    val rest = filterNot { it.id == oldId || it.id == account.id }
    val entry = when (existing?.kind) {
        "add" -> PendingOpDto("add", account.id, account)
        "replace" -> PendingOpDto("replace", account.id, account, base = existing.base, oldId = existing.oldId)
        "update" -> PendingOpDto("replace", account.id, account, base = existing.base ?: base, oldId = oldId)
        else -> PendingOpDto("replace", account.id, account, base = base, oldId = oldId)
    }
    // A→B→A lands back on the original id: that is just an edit of A.
    if (entry.kind == "replace" && entry.oldId == entry.id) {
        return rest + PendingOpDto("update", account.id, account, base = entry.base)
    }
    return rest + entry
}

/** A row only ever added locally collapses to nothing; a pending replace deletes the id the server
 *  knows (its old id); otherwise a delete is recorded. */
internal fun List<PendingOpDto>.recordDelete(id: String): List<PendingOpDto> {
    val existing = firstOrNull { it.id == id }
    val rest = filterNot { it.id == id }
    return when {
        existing?.kind == "add" -> rest
        existing?.kind == "replace" && existing.oldId != null ->
            rest.filterNot { it.id == existing.oldId } + PendingOpDto("delete", existing.oldId)
        else -> rest + PendingOpDto("delete", id)
    }
}

internal interface PlaylistSyncTransport {
    suspend fun pull(profileId: Int): PlaylistPullResponse
    suspend fun push(
        profileId: Int,
        expectedRevision: Long?,
        accounts: List<XtreamAccount>,
        deleteAll: Boolean,
        mutationId: String,
        expectedGeneration: Long?,
    ): PlaylistPushResponse

    /**
     * Wave 3: the push that also carries the profile's media-server rows (the full replace is scoped to the union
     * of the source types this client understands). A transport that knows nothing of server entries keeps the
     * default - exactly the playlist push - so every playlist-only transport and test is unchanged.
     */
    suspend fun pushWithMediaServers(
        profileId: Int,
        expectedRevision: Long?,
        accounts: List<XtreamAccount>,
        mediaServers: List<MediaServerEntry>,
        deleteAll: Boolean,
        mutationId: String,
        expectedGeneration: Long?,
    ): PlaylistPushResponse = push(profileId, expectedRevision, accounts, deleteAll, mutationId, expectedGeneration)
}

data class PlaylistPullResponse(
    val revision: Long,
    val accounts: List<XtreamAccount>,
    val generation: Long = 0,
    /** Step 0: ids of [accounts] that are the server's stored `playlist_key` (not a local derivation). */
    val keyedIds: Set<String> = emptySet(),
    /** Wave 3: the pulled rows of type jellyfin/emby, mapped by the second row mapper (the first drops them). */
    val mediaServers: List<MediaServerEntry> = emptyList(),
)

sealed interface PlaylistPushResponse {
    data class Ok(val revision: Long, val deduped: Boolean = false) : PlaylistPushResponse
    data class Conflict(
        val currentRevision: Long,
        val currentRows: List<XtreamAccount>,
        /** Step 0: ids of [currentRows] that are the server's stored `playlist_key`. */
        val currentKeyedIds: Set<String> = emptySet(),
        val currentMediaServers: List<MediaServerEntry> = emptyList(),
    ) : PlaylistPushResponse
    data class Rejected(val reason: String) : PlaylistPushResponse
}

enum class PlaylistSyncOutcome {
    SYNCED, UP_TO_DATE, WITHHELD, PULL_FAILED, PUSH_FAILED, CONFLICT_EXHAUSTED, REJECTED,
}

/**
 * B68 — a mutation id names exactly ONE payload. The server dedups a resend of the id it last
 * committed and RAISES (SQLSTATE 22023) when that id arrives with a different body. The engine used to
 * keep the id across any failed push, so a commit whose response was lost, followed by a reconciled
 * payload that differed (a new edit, another device's change), re-sent the committed id on every sync
 * and the profile never synced again (prod 2026-09-27: 16 profiles, some wedged since 2026-09-14).
 *
 * Reuse the id only for the identical payload it was minted for; anything else — including an id
 * stored without a fingerprint by an older build — gets a fresh id. That is always safe: every push is
 * anchored to a fresh pull's revision, so a fresh id re-sending an already-committed set costs at
 * most one redundant revision, never a lost update.
 */
internal object PlaylistMutationIdPolicy {
    /** [wirePayload] is the push body the server hashes (or any superset of it). */
    fun fingerprint(wirePayload: String, deleteAll: Boolean, mediaServers: List<MediaServerEntry> = emptyList()): String =
        fnv1a64Hex(
            "$wirePayload|$deleteAll" +
                // unchanged for a profile without server entries, so an in-flight id minted before this build still matches
                if (mediaServers.isEmpty()) "" else "|ms:" + mediaServers.mapIndexed { i, e -> MediaServerSyncCodec.syncedFingerprint(e, i) }.joinToString(","),
        )

    fun idFor(storedId: String?, storedFingerprint: String?, fingerprint: String, mint: () -> String): String =
        if (storedId != null && storedFingerprint == fingerprint) storedId else mint()

    private fun fnv1a64Hex(text: String): String {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return java.lang.Long.toUnsignedString(hash, 16)
    }
}

internal class PlaylistV2SyncEngine(
    private val transport: PlaylistSyncTransport,
    private val loadState: suspend (Int) -> PlaylistSyncState,
    private val saveState: suspend (Int, PlaylistSyncState) -> Unit,
    private val currentAccounts: () -> List<XtreamAccount>,
    private val canPush: () -> Boolean,
    private val applyLocal: suspend (profileId: Int, accounts: List<XtreamAccount>) -> Unit,
    private val stillActive: (Int) -> Boolean,
    private val newMutationId: () -> String,
    private val maxConflictRetries: Int = 5,
    /** The value two rows must share to count as "in sync": what the server stores for a row. The
     *  production service passes the wire mapping; the default neutralises local-only preferences. */
    private val syncedKey: (XtreamAccount) -> Any = { it.withoutDeviceLocalPrefs() },
    /** The push body for [accounts], for the mutation-id fingerprint (B68). The production service
     *  passes the wire mapping; the default (every field) is a safe superset of it. */
    private val wirePayload: (List<XtreamAccount>) -> String = { it.toString() },
    /**
     * Step 0 — reconciles this device's playlist ids with a pulled set BEFORE anything compares or
     * reconciles against it ([PlaylistKeyAdoption]): re-keys local ids onto server keys (moving their
     * prefix-keyed data, once) and returns the pulled rows as they should be applied. Runs before the
     * sync state is loaded, so pending ops it rewrites are the ones this sync replays.
     */
    private val adoptKeys: suspend (profileId: Int, pulled: List<XtreamAccount>, keyedIds: Set<String>) -> PlaylistKeyAdoption.Result =
        { _, pulled, _ -> PlaylistKeyAdoption.Result(pulled, emptyList()) },
    /**
     * Wave 3 - the media-server half (design 5.3): the profile's Jellyfin/Emby entries ride this same engine.
     * Null (the default) = playlists only, behaviour identical to before.
     */
    private val mediaServers: MediaServerSyncBinding? = null,
) {
    suspend fun sync(profileId: Int): PlaylistSyncOutcome {
        val rawPull = runCatching { transport.pull(profileId) }.getOrNull() ?: return PlaylistSyncOutcome.PULL_FAILED
        if (!stillActive(profileId)) return PlaylistSyncOutcome.PULL_FAILED
        val pull = rawPull.copy(accounts = adoptKeys(profileId, rawPull.accounts, rawPull.keyedIds).accounts)

        var state = loadState(profileId)
        // Generation reset (B24 profile-recreation safety): if the server reports a newer generation
        // than the one our pending ops are anchored to, the profile was deleted (and possibly recreated
        // at the reused id) after those ops were recorded. They belong to a dead profile lifetime, so we
        // DISCARD them — replaying them would silently populate the recreated profile. We then adopt the
        // new generation so any genuinely new local edit is anchored correctly. Persisted immediately so
        // the discard survives a crash before the push.
        if (pull.generation > state.generation && (state.pending.isNotEmpty() || state.mediaServerPending.isNotEmpty())) {
            state = state.copy(pending = emptyList(), deleteAllIntent = false).withMediaServerPending(emptyList())
        }
        if (state.generation != pull.generation) {
            state = state.copy(generation = pull.generation)
            saveState(profileId, state)
        }
        val recorded = state.pending.toOps()
        val recordedMedia = if (mediaServers != null) state.mediaServerPending else emptyList()
        // The pending entries this sync will push; on commit we remove ONLY these so a newer edit
        // recorded during the push is preserved (B24 §3).
        var ackedPending: List<PendingOpDto> = state.pending
        var ackedMedia: List<MediaServerPendingOp> = recordedMedia

        var pending: List<PendingPlaylistOp>
        var pendingMedia: List<MediaServerPendingOp> = recordedMedia
        var expected: Long?
        val localMedia = mediaServers?.currentEntries().orEmpty()
        // Without the binding the engine is playlists-only: whatever server rows the pull carried are none of its business.
        val remoteMedia = if (mediaServers != null) pull.mediaServers else emptyList()
        when {
            recorded.isNotEmpty() || recordedMedia.isNotEmpty() -> { pending = recorded; expected = pull.revision }
            // B60: ids AND every synced field must match — an id-only comparison left a field changed on
            // another device (a UA, a refresh interval) never applied here. A divergence with nothing
            // pending falls through to "adopt the server" below.
            sameSyncedSet(currentAccounts(), pull.accounts, syncedKey) && sameMediaSet(localMedia, remoteMedia) -> {
                saveState(profileId, state.copy(revision = pull.revision, mutationId = null, mutationFingerprint = null))
                return PlaylistSyncOutcome.UP_TO_DATE
            }
            pull.accounts.isEmpty() && remoteMedia.isEmpty() && pull.revision == 0L &&
                ((canPush() && currentAccounts().isNotEmpty()) || (mediaServers?.canPushFullReplace?.invoke() == true && localMedia.isNotEmpty())) -> {
                pending = if (canPush()) currentAccounts().map { PendingPlaylistOp.Add(it) } else emptyList()
                pendingMedia = if (mediaServers?.canPushFullReplace?.invoke() == true) localMedia.map { MediaServerPendingOp("add", it.key, it) } else emptyList()
                expected = null
            }
            else -> {
                applyLocal(profileId, pull.accounts)
                mediaServers?.applyFromRemote?.invoke(profileId, remoteMedia)
                saveState(profileId, state.copy(revision = pull.revision, pending = emptyList(), mutationId = null, mutationFingerprint = null, deleteAllIntent = false).withMediaServerPending(emptyList()))
                return if (canPush()) PlaylistSyncOutcome.UP_TO_DATE else PlaylistSyncOutcome.WITHHELD
            }
        }

        // The mutation id is reused (across retries AND restarts) only for the identical payload it was
        // minted for — see PlaylistMutationIdPolicy.
        var baseRows = pull.accounts
        var mediaBaseline = remoteMedia
        var baselineRevision = pull.revision

        var retries = 0
        while (true) {
            if (!stillActive(profileId)) return PlaylistSyncOutcome.PULL_FAILED
            val reconciled = reconcilePendingOntoBaseline(baseRows, pending)
            applyLocal(profileId, reconciled.accounts)
            // The server entries: a damaged/absent local store carries no intent of its own, so the server's rows go
            // back unchanged (never a truncated push that would delete them); otherwise the intent replays onto them.
            val reconciledMedia = if (mediaServers == null) emptyList() else MediaServerPendingOps.reconcile(mediaBaseline, pendingMedia)
            if (mediaServers != null) mediaServers.applyFromRemote(profileId, reconciledMedia)
            val deleteAll = reconciled.accounts.isEmpty() && reconciledMedia.isEmpty()
            val fingerprint = PlaylistMutationIdPolicy.fingerprint(wirePayload(reconciled.accounts), deleteAll, reconciledMedia)
            val mutationId = PlaylistMutationIdPolicy.idFor(state.mutationId, state.mutationFingerprint, fingerprint, newMutationId)
            // Re-read before persisting the mutation id so an edit recorded since loadState is not clobbered.
            state = loadState(profileId).copy(mutationId = mutationId, mutationFingerprint = fingerprint, revision = baselineRevision)
            saveState(profileId, state)
            val resp = runCatching {
                // Anchor the write to the generation we observed on pull; the server rejects it as
                // stale_generation if the profile was deleted since, so a reconcile-retry cannot
                // resurrect a deleted-then-recreated profile even if this client did not discard.
                if (mediaServers == null) transport.push(profileId, expected, reconciled.accounts, deleteAll, mutationId, pull.generation)
                else transport.pushWithMediaServers(profileId, expected, reconciled.accounts, reconciledMedia, deleteAll, mutationId, pull.generation)
            }.getOrElse { return PlaylistSyncOutcome.PUSH_FAILED }

            when (resp) {
                is PlaylistPushResponse.Ok -> {
                    // Remove ONLY the entries this request carried; a newer edit stays pending (B24 §3).
                    val fresh = loadState(profileId)
                    saveState(profileId, fresh.copy(
                        revision = resp.revision,
                        pending = fresh.pending.filterNot { it in ackedPending },
                        mutationId = null,
                        mutationFingerprint = null,
                        deleteAllIntent = false,
                    ).withMediaServerPending(fresh.mediaServerPending.filterNot { it in ackedMedia }))
                    return PlaylistSyncOutcome.SYNCED
                }
                is PlaylistPushResponse.Conflict -> {
                    if (retries++ >= maxConflictRetries) {
                        saveState(profileId, state.copy(revision = resp.currentRevision))
                        return PlaylistSyncOutcome.CONFLICT_EXHAUSTED
                    }
                    val adopted = adoptKeys(profileId, resp.currentRows, resp.currentKeyedIds)
                    baseRows = adopted.accounts
                    mediaBaseline = if (mediaServers != null) resp.currentMediaServers else emptyList()
                    // A re-key here also rewrote the durable pending log; replay (and later ack) the
                    // same rewritten entries, or an edit recorded under the old id would be dropped.
                    if (adopted.rekeys.isNotEmpty()) {
                        pending = PlaylistKeyAdoption.rewriteOps(pending, adopted.rekeys)
                        ackedPending = PlaylistKeyAdoption.rewritePending(ackedPending, adopted.rekeys)
                    }
                    expected = resp.currentRevision
                    baselineRevision = resp.currentRevision
                    state = state.copy(revision = expected!!)
                    saveState(profileId, state)
                }
                is PlaylistPushResponse.Rejected -> return PlaylistSyncOutcome.REJECTED
            }
        }
    }
}

/** Order-independent comparison of what the server stores for each media-server row (sort order is positional). */
private fun sameMediaSet(local: List<MediaServerEntry>, remote: List<MediaServerEntry>): Boolean =
    local.size == remote.size &&
        local.map { MediaServerSyncCodec.syncedFingerprint(it, 0) }.sorted() == remote.map { MediaServerSyncCodec.syncedFingerprint(it, 0) }.sorted()

/** Order-independent comparison of what the server stores for each row. */
private fun sameSyncedSet(a: List<XtreamAccount>, b: List<XtreamAccount>, key: (XtreamAccount) -> Any): Boolean =
    a.size == b.size && a.groupingBy(key).eachCount() == b.groupingBy(key).eachCount()

internal fun decodePlaylistSyncState(gson: Gson, raw: String?): PlaylistSyncState =
    raw?.let { runCatching { gson.fromJson(it, PlaylistSyncState::class.java) }.getOrNull() } ?: PlaylistSyncState()

internal fun encodePlaylistSyncState(gson: Gson, state: PlaylistSyncState): String = gson.toJson(state)
