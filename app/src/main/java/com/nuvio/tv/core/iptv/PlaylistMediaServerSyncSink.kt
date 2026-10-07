package com.nuvio.tv.core.iptv

import android.util.Log
import com.google.gson.Gson
import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOp
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOps.recordAdd
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOps.recordDelete
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOps.recordUpdate
import com.nuvio.tv.core.mediaserver.api.MediaServerSyncSink
import com.nuvio.tv.core.sync.XtreamAccountSyncService
import com.nuvio.tv.data.local.XtreamAccountStore
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where a LOCAL media-server entry change enters the playlist-sync engine (Wave 3, design 5.3): appended to the
 * durable per-profile pending log - the same state blob, rules and activation gate as a playlist edit (only
 * recorded while the v2 path is active or adopted; the legacy v1 path never carries server entries) - then a
 * debounced push is requested. The engine reconciles the intent onto the server's rows and acknowledges it only
 * after the commit.
 *
 * The entry store calls this from inside its own lock and the account store is a DataStore (suspending), so the
 * record is applied on a single-threaded queue: changes land in the order they were made, and the caller never waits.
 */
@Singleton
internal class PlaylistMediaServerSyncSink @Inject constructor(
    private val accountStore: XtreamAccountStore,
    private val syncService: Lazy<XtreamAccountSyncService>,
) : MediaServerSyncSink {
    private val gson = Gson()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val queue = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    override fun recordAdd(profileId: Int, entry: MediaServerEntry) = record(profileId) { it.recordAdd(entry) }

    override fun recordUpdate(profileId: Int, entry: MediaServerEntry, base: MediaServerEntry?) =
        record(profileId) { it.recordUpdate(entry, base) }

    override fun recordDelete(profileId: Int, key: String) = record(profileId) { it.recordDelete(key) }

    private fun record(profileId: Int, transform: (List<MediaServerPendingOp>) -> List<MediaServerPendingOp>) {
        queue.launch {
            try {
                val state = decodePlaylistSyncState(gson, accountStore.loadPlaylistSyncStateRaw(profileId))
                val adopted = state.revision > 0 || state.pending.isNotEmpty() || state.mediaServerPending.isNotEmpty()
                if (!PlaylistSyncConfig.recordsPending(adopted)) return@launch
                accountStore.savePlaylistSyncStateRaw(
                    profileId,
                    encodePlaylistSyncState(gson, state.withMediaServerPending(transform(state.mediaServerPending))),
                )
                syncService.get().triggerRemoteSync()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MediaServerSyncSink", "could not record a media-server change: ${e::class.simpleName}")
            }
        }
    }
}
