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
 *   edit A's URL,   server holds [A,B]  ⇒  Replace(A→A2) = [A2,B]        (B60: in place, never a delete)
 *
 * Edits carry the pre-edit row as `base`, and only the fields the user changed (`edit != base`) are
 * applied onto the server row — per-field last-writer-wins, so a stale form on one device cannot
 * blank a user agent or revert a refresh interval that another device (or the web) set.
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

    /** An edit. [base] is the row before the edit: when present only the fields that differ from it are
     *  applied ([mergeEditOntoServer]); null (a log written by an older build) = whole-row replace. */
    data class Update(val account: XtreamAccount, val base: XtreamAccount? = null) : PendingPlaylistOp {
        override val id: String get() = account.id
    }

    /**
     * B60 — an edit of a playlist's URL / username / MAC, which changes its id. One atomic op: [oldId]
     * leaves the set and [account] takes its position. As a Delete+Update pair it deleted the playlist
     * (the new id was not on the server, so the update was dropped) and, for the only playlist, pushed
     * a delete-all. If [oldId] is already gone on the server the row is kept as an add.
     */
    data class Replace(val oldId: String, val account: XtreamAccount, val base: XtreamAccount? = null) : PendingPlaylistOp {
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
            is PendingPlaylistOp.Update -> {
                val server = byId[op.account.id]
                if (server != null) byId[op.account.id] = mergeEditOntoServer(server, op.base, op.account)
                else dropped += op.account.id
            }
            is PendingPlaylistOp.Delete -> byId.remove(op.id)
            is PendingPlaylistOp.Replace -> {
                val serverOld = byId[op.oldId]
                if (serverOld == null) {
                    // Old row deleted elsewhere: keep the user's row as an add (merged onto any server row
                    // that already carries the new id).
                    val serverNew = byId[op.account.id]
                    byId[op.account.id] = if (serverNew != null) mergeEditOntoServer(serverNew, op.base, op.account) else op.account
                } else {
                    val merged = mergeEditOntoServer(serverOld, op.base, op.account)
                    val rebuilt = LinkedHashMap<String, XtreamAccount>()
                    byId.forEach { (id, row) ->
                        when (id) {
                            op.oldId -> rebuilt[op.account.id] = merged   // new id takes the old one's position
                            op.account.id -> Unit                          // a pre-existing duplicate of the new id
                            else -> rebuilt[id] = row
                        }
                    }
                    byId.clear()
                    byId.putAll(rebuilt)
                }
            }
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

/**
 * B60/B04 — per-field last-writer-wins for an edit replayed onto the server's row (twin of Mobile's).
 * A synced field the user changed ([edit] differs from [base]) takes the edited value; every other
 * synced field keeps the [server]'s. The id and this device's local-only preferences come from [edit].
 * A null [base] (a pending log from before this field existed) is the old whole-row replace.
 */
fun mergeEditOntoServer(server: XtreamAccount, base: XtreamAccount?, edit: XtreamAccount): XtreamAccount {
    if (base == null) return edit
    fun <T> pick(serverValue: T, baseValue: T, editValue: T): T = if (editValue != baseValue) editValue else serverValue
    return edit.copy(
        name = pick(server.name, base.name, edit.name),
        baseUrl = pick(server.baseUrl, base.baseUrl, edit.baseUrl),
        username = pick(server.username, base.username, edit.username),
        password = pick(server.password, base.password, edit.password),
        enabled = pick(server.enabled, base.enabled, edit.enabled),
        sourceType = pick(server.sourceType, base.sourceType, edit.sourceType),
        userAgent = pick(server.userAgent, base.userAgent, edit.userAgent),
        epgUrl = pick(server.epgUrl, base.epgUrl, edit.epgUrl),
        dnsProvider = pick(server.dnsProvider, base.dnsProvider, edit.dnsProvider),
        autoRefreshHours = pick(server.autoRefreshHours, base.autoRefreshHours, edit.autoRefreshHours),
        contentTypes = pick(server.contentTypes, base.contentTypes, edit.contentTypes),
        categorySelections = pick(server.categorySelections, base.categorySelections, edit.categorySelections),
        fileName = pick(server.fileName, base.fileName, edit.fileName),
        portalUrl = pick(server.portalUrl, base.portalUrl, edit.portalUrl),
        macAddress = pick(server.macAddress, base.macAddress, edit.macAddress),
        stalkerUsername = pick(server.stalkerUsername, base.stalkerUsername, edit.stalkerUsername),
        stalkerPassword = pick(server.stalkerPassword, base.stalkerPassword, edit.stalkerPassword),
        serialNumber = pick(server.serialNumber, base.serialNumber, edit.serialNumber),
        deviceId = pick(server.deviceId, base.deviceId, edit.deviceId),
        sendDeviceId = pick(server.sendDeviceId, base.sendDeviceId, edit.sendDeviceId),
        deviceId2 = pick(server.deviceId2, base.deviceId2, edit.deviceId2),
        signature = pick(server.signature, base.signature, edit.signature),
        stbModel = pick(server.stbModel, base.stbModel, edit.stbModel),
        hwVersion = pick(server.hwVersion, base.hwVersion, edit.hwVersion),
        backupUrls = pick(server.backupUrls, base.backupUrls, edit.backupUrls),
    )
}

/** A row with this device's local-only preferences (never on the wire) neutralised — what two rows
 *  must share to be "in sync" when no wire mapping is supplied (see PlaylistV2SyncEngine.syncedKey). */
fun XtreamAccount.withoutDeviceLocalPrefs(): XtreamAccount =
    copy(
        preferM3u8CatchUp = false, catchUpCorrectionMinutes = 0, guideEpgCorrectionMinutes = 0,
        cleanChannelNames = false, channelNameTags = null,
    )
