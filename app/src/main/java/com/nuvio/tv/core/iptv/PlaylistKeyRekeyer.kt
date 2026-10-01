package com.nuvio.tv.core.iptv

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.iptv.content.M3UFileStore
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchStateMutationStore
import com.nuvio.tv.core.sync.WatchStateRekeyPlan
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.XtreamLiveStore
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Step 0 — executes [PlaylistKeyAdoption]'s re-keys on the TV's stores, once per mismatched id after a
 * pull (TV's old `m3u:` ids, `file:{uuid}` / `file:synced-` file ids, a rescued orphan):
 *  - the account (and the durable v2 pending log) is renamed in place — no pending op, no push echo;
 *  - the prefix-keyed user data follows: library, watch progress, watched marks, live
 *    favourites/recents — the same prefix rewrite an id-changing edit used to do. Synced writes
 *    (parity with Mobile's adoption):
 *     - library (incl. live favourites, which ARE library items): its sync reducer queues an upsert
 *       of every moved item under the new id + a delete of the old ids;
 *     - full account only — watch progress: delete of every old non-live key + upsert of every moved non-live entry
 *       (live progress is local-only on TV);
 *     - watched marks: delete of every old mark + upsert of every moved mark;
 *    the progress/watched ops land in ONE atomic outbox edit before the local stores move, then are
 *    pushed (deletes, then upserts) when the session can sync. Live refs/recents are device-local;
 *  - a file playlist's saved copy is moved to the new id's path (the store hashes the id);
 *  - caches built under the old id are purged ([PlaylistRemovalOrigin.SyncPull]: caches only).
 * The overlay (hidden/pinned channels) cannot follow — its keys hash the old id (accepted, Step 0).
 */
@Singleton
class PlaylistKeyRekeyer @Inject constructor(
    private val accountStore: XtreamAccountStore,
    private val libraryPreferences: LibraryPreferences,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val liveStore: XtreamLiveStore,
    private val fileStore: M3UFileStore,
    private val purge: IptvAccountPurge,
    private val authManager: AuthManager,
    private val mutationStore: WatchStateMutationStore,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val watchedItemsSyncService: WatchedItemsSyncService,
) {
    /** Resolves [pulled] against [profileId]'s stored playlists and executes every re-key it decides. */
    suspend fun adoptFromPull(profileId: Int, pulled: List<PulledPlaylist>): PlaylistKeyAdoption.Result {
        val local = runCatching { accountStore.accountsForProfile(profileId) }.getOrDefault(emptyList())
        val result = PlaylistKeyAdoption.resolve(pulled, local)
        adopt(profileId, result.rekeys)
        return result
    }

    suspend fun adopt(profileId: Int, rekeys: List<PlaylistKeyAdoption.Rekey>) {
        if (rekeys.isEmpty()) return
        accountStore.renameIds(profileId, rekeys)
        var queuedWatchSync = false
        for (rekey in rekeys) {
            step("saved data") { queuedWatchSync = rekeySavedData(rekey, profileId) || queuedWatchSync }
            step("file copy") { fileStore.move(rekey.oldId, rekey.newId) }
            step("cache purge") { purge.purge(rekey.oldId, PlaylistRemovalOrigin.SyncPull) }
        }
        // Send the queued watch-state moves now (Mobile pushes them at once too). A failure or a
        // lapsed session only defers: the outbox is durable and the next sync cycle pushes it.
        if (queuedWatchSync && authManager.canSync) {
            step("watch progress push") { watchProgressSyncService.pushToRemote(profileId) }
            step("watched push") { watchedItemsSyncService.pushToRemote(profileId) }
        }
    }

    /** Re-keys every prefix store; true when it queued watch-state sync ops. */
    private suspend fun rekeySavedData(rekey: PlaylistKeyAdoption.Rekey, profileId: Int): Boolean {
        val oldPrefix = XtreamItemRegistry.accountPrefix(rekey.oldId)
        val newPrefix = XtreamItemRegistry.accountPrefix(rekey.newId)
        libraryPreferences.migrateIdPrefix(oldPrefix, newPrefix)
        // Watch state: the sync ops are queued (one atomic outbox edit) BEFORE the local stores move.
        // Dying in between leaves the outbox holding the moved copies (they still reach the server)
        // and the server rows under the old id doomed; the reverse order could leave the local stores
        // moved with nothing queued — the old-id rows would then come back on the next pull as ghosts.
        val plan = WatchStateRekeyPlan.build(
            progress = watchProgressPreferences.getAllRawEntries(profileId),
            watched = watchedItemsPreferences.getAllItems(profileId),
            oldPrefix = oldPrefix,
            newPrefix = newPrefix,
            // Signed-out / non-full-account devices re-key locally only (nothing to queue for).
            fullAccount = authManager.isAuthenticated,
        )
        var queued = false
        if (!plan.isEmpty) step("watch-state sync queue") {
            mutationStore.queueRekey(
                progressUpserts = plan.progressUpserts,
                progressDeletes = plan.progressDeletes,
                watchedUpserts = plan.watchedUpserts,
                watchedDeletes = plan.watchedDeletes,
                profileId = profileId,
            )
            queued = true
        }
        // Even if queueing failed the local move still happens: the user keeps seeing their data.
        watchProgressPreferences.migrateIdPrefix(oldPrefix, newPrefix, profileId)
        watchedItemsPreferences.migrateIdPrefix(oldPrefix, newPrefix, profileId)
        liveStore.migrateAccount(oldPrefix) { ref -> ref.copy(id = newPrefix + ref.id.removePrefix(oldPrefix)) }
        return queued
    }

    private suspend fun step(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "playlist key adoption: $what failed", e)
        }
    }

    private companion object {
        const val TAG = "PlaylistKeyRekeyer"
    }
}
