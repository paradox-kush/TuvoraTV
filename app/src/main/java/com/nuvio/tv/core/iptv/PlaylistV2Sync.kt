package com.nuvio.tv.core.iptv

import com.google.gson.Gson

/**
 * B24 — the real app-level v2 sync engine for NuvioTV (twin of Mobile/Desktop's PlaylistV2Sync).
 * The engine logic (pull authoritative rows+revision → reconcile pending intent → push under the
 * revision contract with a stable, restart-persistent mutation id → clear acknowledged ops) is
 * identical; only the serialization uses Gson (TV's storage convention) instead of kotlinx. The
 * sync-state lives under its own DataStore key so it survives an accounts-store corruption reset.
 */

/** A durable, per-profile pending op (collapsed to the latest intent per id). */
data class PendingOpDto(
    val kind: String = "",           // "add" | "update" | "delete"
    val id: String = "",
    val account: XtreamAccount? = null,
)

data class PlaylistSyncState(
    val revision: Long = 0,
    val mutationId: String? = null,
    val pending: List<PendingOpDto> = emptyList(),
    val deleteAllIntent: Boolean = false,
    // The profile-lifetime generation this state (and its pending ops) is anchored to. Bumped by the
    // backend on a profile deletion; when a pull reports a newer generation, the pending ops belong to
    // a now-dead profile lifetime and are discarded rather than replayed onto the recreated profile.
    val generation: Long = 0,
)

private fun PendingOpDto.toOp(): PendingPlaylistOp? = when (kind) {
    "add" -> account?.let { PendingPlaylistOp.Add(it) }
    "update" -> account?.let { PendingPlaylistOp.Update(it) }
    "delete" -> PendingPlaylistOp.Delete(id)
    else -> null
}

internal fun List<PendingOpDto>.toOps(): List<PendingPlaylistOp> = mapNotNull { it.toOp() }

internal fun List<PendingOpDto>.recordAdd(account: XtreamAccount): List<PendingOpDto> =
    filterNot { it.id == account.id } + PendingOpDto("add", account.id, account)

internal fun List<PendingOpDto>.recordUpdate(account: XtreamAccount): List<PendingOpDto> {
    val kind = if (firstOrNull { it.id == account.id }?.kind == "add") "add" else "update"
    return filterNot { it.id == account.id } + PendingOpDto(kind, account.id, account)
}

internal fun List<PendingOpDto>.recordDelete(id: String): List<PendingOpDto> {
    val wasLocalAdd = firstOrNull { it.id == id }?.kind == "add"
    val base = filterNot { it.id == id }
    return if (wasLocalAdd) base else base + PendingOpDto("delete", id)
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
}

data class PlaylistPullResponse(val revision: Long, val accounts: List<XtreamAccount>, val generation: Long = 0)

sealed interface PlaylistPushResponse {
    data class Ok(val revision: Long, val deduped: Boolean = false) : PlaylistPushResponse
    data class Conflict(val currentRevision: Long, val currentRows: List<XtreamAccount>) : PlaylistPushResponse
    data class Rejected(val reason: String) : PlaylistPushResponse
}

enum class PlaylistSyncOutcome {
    SYNCED, UP_TO_DATE, WITHHELD, PULL_FAILED, PUSH_FAILED, CONFLICT_EXHAUSTED, REJECTED,
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
) {
    suspend fun sync(profileId: Int): PlaylistSyncOutcome {
        val pull = runCatching { transport.pull(profileId) }.getOrNull() ?: return PlaylistSyncOutcome.PULL_FAILED
        if (!stillActive(profileId)) return PlaylistSyncOutcome.PULL_FAILED

        var state = loadState(profileId)
        // Generation reset (B24 profile-recreation safety): if the server reports a newer generation
        // than the one our pending ops are anchored to, the profile was deleted (and possibly recreated
        // at the reused id) after those ops were recorded. They belong to a dead profile lifetime, so we
        // DISCARD them — replaying them would silently populate the recreated profile. We then adopt the
        // new generation so any genuinely new local edit is anchored correctly. Persisted immediately so
        // the discard survives a crash before the push.
        if (pull.generation > state.generation && state.pending.isNotEmpty()) {
            state = state.copy(pending = emptyList(), deleteAllIntent = false)
        }
        if (state.generation != pull.generation) {
            state = state.copy(generation = pull.generation)
            saveState(profileId, state)
        }
        val recorded = state.pending.toOps()
        // The pending entries this sync will push; on commit we remove ONLY these so a newer edit
        // recorded during the push is preserved (B24 §3).
        val ackedPending: List<PendingOpDto> = state.pending

        val pending: List<PendingPlaylistOp>
        var expected: Long?
        when {
            recorded.isNotEmpty() -> { pending = recorded; expected = pull.revision }
            sameSet(currentAccounts(), pull.accounts) -> {
                saveState(profileId, state.copy(revision = pull.revision, mutationId = null))
                return PlaylistSyncOutcome.UP_TO_DATE
            }
            pull.accounts.isEmpty() && pull.revision == 0L && canPush() && currentAccounts().isNotEmpty() -> {
                pending = currentAccounts().map { PendingPlaylistOp.Add(it) }
                expected = null
            }
            else -> {
                applyLocal(profileId, pull.accounts)
                saveState(profileId, state.copy(revision = pull.revision, pending = emptyList(), mutationId = null, deleteAllIntent = false))
                return if (canPush()) PlaylistSyncOutcome.UP_TO_DATE else PlaylistSyncOutcome.WITHHELD
            }
        }

        val mutationId = state.mutationId ?: newMutationId()
        var baseRows = pull.accounts
        // Re-read before persisting the mutation id so an edit recorded since loadState is not clobbered.
        state = loadState(profileId).copy(mutationId = mutationId, revision = pull.revision)
        saveState(profileId, state)

        var retries = 0
        while (true) {
            if (!stillActive(profileId)) return PlaylistSyncOutcome.PULL_FAILED
            val reconciled = reconcilePendingOntoBaseline(baseRows, pending)
            applyLocal(profileId, reconciled.accounts)
            val deleteAll = reconciled.accounts.isEmpty()
            val resp = runCatching {
                // Anchor the write to the generation we observed on pull; the server rejects it as
                // stale_generation if the profile was deleted since, so a reconcile-retry cannot
                // resurrect a deleted-then-recreated profile even if this client did not discard.
                transport.push(profileId, expected, reconciled.accounts, deleteAll, mutationId, pull.generation)
            }.getOrElse { return PlaylistSyncOutcome.PUSH_FAILED }

            when (resp) {
                is PlaylistPushResponse.Ok -> {
                    // Remove ONLY the entries this request carried; a newer edit stays pending (B24 §3).
                    val fresh = loadState(profileId)
                    saveState(profileId, fresh.copy(
                        revision = resp.revision,
                        pending = fresh.pending.filterNot { it in ackedPending },
                        mutationId = null,
                        deleteAllIntent = false,
                    ))
                    return PlaylistSyncOutcome.SYNCED
                }
                is PlaylistPushResponse.Conflict -> {
                    if (retries++ >= maxConflictRetries) {
                        saveState(profileId, state.copy(revision = resp.currentRevision))
                        return PlaylistSyncOutcome.CONFLICT_EXHAUSTED
                    }
                    baseRows = resp.currentRows
                    expected = resp.currentRevision
                    state = state.copy(revision = expected!!)
                    saveState(profileId, state)
                }
                is PlaylistPushResponse.Rejected -> return PlaylistSyncOutcome.REJECTED
            }
        }
    }
}

private fun sameSet(a: List<XtreamAccount>, b: List<XtreamAccount>): Boolean =
    a.size == b.size && a.map { it.id }.toSet() == b.map { it.id }.toSet()

internal fun decodePlaylistSyncState(gson: Gson, raw: String?): PlaylistSyncState =
    raw?.let { runCatching { gson.fromJson(it, PlaylistSyncState::class.java) }.getOrNull() } ?: PlaylistSyncState()

internal fun encodePlaylistSyncState(gson: Gson, state: PlaylistSyncState): String = gson.toJson(state)
