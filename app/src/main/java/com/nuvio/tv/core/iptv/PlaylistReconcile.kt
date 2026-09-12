package com.nuvio.tv.core.iptv

/**
 * B24 §4 — the pure reconcile core for the revision contract's conflict path (NuvioTV twin of
 * Mobile/Desktop's PlaylistReconcile). When a full-replace push is rejected with the server's
 * authoritative baseline, the client must NOT discard the local edit and must NOT blindly re-push
 * its own set (that is the corruption-reset → add-C wipe). It replays the pending mutation BY INTENT
 * onto the server baseline:
 *
 *   reset → add C, server holds [A,B]  ⇒  Add(C) onto [A,B] = [A,B,C]   (A,B preserved)
 *   delete A,       server holds [A,B]  ⇒  Delete(A) onto [A,B] = [B]     (not a resurrection)
 *   edit B,         server holds [A,B]  ⇒  Update(B') onto [A,B] = [A,B'] (edit re-applied)
 *   edit B,         server deleted B    ⇒  Update(B') dropped, surfaced   (no zombie re-add)
 *
 * Intent, not a set-union: a set diff would resurrect a row the user deleted on this device the
 * moment another device still lists it. Pure functions — the durable op log, the v2 push loop and
 * the baseline persistence are the I/O around them.
 */
sealed interface PendingPlaylistOp {
    val id: String

    data class Add(val account: XtreamAccount) : PendingPlaylistOp {
        override val id: String get() = account.id
    }

    data class Update(val account: XtreamAccount) : PendingPlaylistOp {
        override val id: String get() = account.id
    }

    data class Delete(override val id: String) : PendingPlaylistOp
}

data class PlaylistReconcileResult(
    val accounts: List<XtreamAccount>,
    val droppedUpdateIds: List<String>,
)

/**
 * Replay [ops] (in order) onto the authoritative server [baseline]. A [PendingPlaylistOp.Update]
 * whose id is absent from the baseline is dropped and reported in [droppedUpdateIds] — the client
 * surfaces it rather than silently resurrecting a row deleted on another device.
 */
fun reconcilePendingOntoBaseline(
    baseline: List<XtreamAccount>,
    ops: List<PendingPlaylistOp>,
): PlaylistReconcileResult {
    val byId = LinkedHashMap<String, XtreamAccount>()
    baseline.forEach { byId[it.id] = it }
    val dropped = mutableListOf<String>()

    ops.forEach { op ->
        when (op) {
            is PendingPlaylistOp.Add -> byId[op.account.id] = op.account
            is PendingPlaylistOp.Update ->
                if (byId.containsKey(op.account.id)) byId[op.account.id] = op.account
                else dropped += op.account.id
            is PendingPlaylistOp.Delete -> byId.remove(op.id)
        }
    }
    return PlaylistReconcileResult(byId.values.toList(), dropped)
}

/**
 * B24 §4 — the outcome of a v2 pull, kept explicit so the client never infers "the server has no
 * collection" from a failure. Treating a timeout / permission error / filtered-empty result as
 * absence is exactly what turns a transient error into a destructive fresh-create.
 */
sealed interface PlaylistPullOutcome {
    data class Present(val accounts: List<XtreamAccount>, val revision: Long) : PlaylistPullOutcome
    data object AuthoritativeAbsent : PlaylistPullOutcome
    data object Indeterminate : PlaylistPullOutcome
}

/**
 * Classify a v2 pull. [succeeded] must be true ONLY when the read authoritatively completed; a false
 * [succeeded] is [Indeterminate] regardless of the row/revision values, so a failed pull can never be
 * mistaken for an empty server.
 */
fun classifyPlaylistPull(succeeded: Boolean, accounts: List<XtreamAccount>, revision: Long): PlaylistPullOutcome = when {
    !succeeded -> PlaylistPullOutcome.Indeterminate
    revision == 0L && accounts.isEmpty() -> PlaylistPullOutcome.AuthoritativeAbsent
    else -> PlaylistPullOutcome.Present(accounts, revision)
}

/** Only an authoritative absence permits a fresh creation (expected-revision = null) — B24 §4. */
fun PlaylistPullOutcome.permitsFreshCreation(): Boolean = this is PlaylistPullOutcome.AuthoritativeAbsent
