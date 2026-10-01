package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves (or, with a null new prefix, drops) a playlist's watch progress + watched marks AND syncs it —
 * the TV executor of [WatchStateRekeyPlan], shared by a pull-time key adoption (re-key) and an
 * explicit user delete of a playlist (drop). Behavioural twin of Mobile's
 * `WatchProgressRepository.migrateIdPrefix` / `WatchedRepository.migrateIdPrefix`.
 *
 * Ordering: the sync ops are queued in ONE atomic outbox edit BEFORE the local stores change. Dying
 * in between leaves the outbox complete (it still reaches the server); the reverse order could leave
 * the local stores changed with nothing queued, and the old-id server rows would come back on the
 * next pull as ghosts. Not a full account → local only, nothing queued.
 */
@Singleton
class WatchStatePrefixMover @Inject constructor(
    private val authManager: AuthManager,
    private val mutationStore: WatchStateMutationStore,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val watchedItemsSyncService: WatchedItemsSyncService,
) {
    /** Queues the sync ops, then changes the local stores. True when sync ops were queued. */
    suspend fun move(profileId: Int, oldPrefix: String, newPrefix: String?): Boolean {
        val plan = WatchStateRekeyPlan.build(
            progress = watchProgressPreferences.getAllRawEntries(profileId),
            watched = watchedItemsPreferences.getAllItems(profileId),
            oldPrefix = oldPrefix,
            newPrefix = newPrefix,
            fullAccount = authManager.isAuthenticated,
        )
        var queued = false
        if (!plan.isEmpty) guard("sync queue") {
            mutationStore.queueRekey(
                progressUpserts = plan.progressUpserts,
                progressDeletes = plan.progressDeletes,
                watchedUpserts = plan.watchedUpserts,
                watchedDeletes = plan.watchedDeletes,
                profileId = profileId,
            )
            queued = true
        }
        // Even if queueing failed the local change still happens: the device reflects the user's action.
        watchProgressPreferences.migrateIdPrefix(oldPrefix, newPrefix, profileId)
        watchedItemsPreferences.migrateIdPrefix(oldPrefix, newPrefix, profileId)
        return queued
    }

    /**
     * Sends the queued ops now (Mobile pushes at once too) when the session can sync. A failure or a
     * lapsed session only defers: the outbox is durable and the next sync cycle pushes it.
     */
    suspend fun pushQueued(profileId: Int) {
        if (!authManager.canSync) return
        guard("watch progress push") { watchProgressSyncService.pushToRemote(profileId) }
        guard("watched push") { watchedItemsSyncService.pushToRemote(profileId) }
    }

    private suspend fun guard(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "watch-state prefix move: $what failed", e)
        }
    }

    private companion object {
        const val TAG = "WatchStatePrefixMover"
    }
}
