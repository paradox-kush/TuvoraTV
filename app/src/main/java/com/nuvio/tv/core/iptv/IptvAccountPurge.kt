package com.nuvio.tv.core.iptv

import android.util.Log
import com.nuvio.tv.core.analytics.LiveEngineMemory
import com.nuvio.tv.core.epg.EpgMirrorRepository
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.content.M3UFileStore
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.iptv.overlay.IptvOverlayRepository
import com.nuvio.tv.core.iptv.refresh.IptvRefreshStore
import com.nuvio.tv.core.iptv.stalker.StalkerClient
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.XtreamHubSelectionStore
import com.nuvio.tv.data.local.XtreamLiveStore
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes [PlaylistRemovalCleanup]'s plan for a playlist that went away — an explicit user delete
 * ([PlaylistRemovalOrigin.UserDelete]) or a remote sync that no longer lists it
 * ([PlaylistRemovalOrigin.SyncPull]). The plan decides WHAT goes (caches always; the user's own data —
 * overlay, live favorites/recents, library/progress/watched — only on an explicit delete, because a
 * pull can be transient); this class only maps each target onto the TV's stores.
 *
 * Every step is isolated: one store failing never stops the rest.
 */
@Singleton
class IptvAccountPurge @Inject constructor(
    private val registry: XtreamItemRegistry,
    private val searchIndex: XtreamSearchIndex,
    private val matchIndex: XtreamMatchIndex,
    private val contentDb: IptvContentDb,
    private val refreshStore: IptvRefreshStore,
    private val fileStore: M3UFileStore,
    private val epgMirror: EpgMirrorRepository,
    private val stalkerClient: StalkerClient,
    private val catchUpWinners: CatchUpWinnerStore,
    private val hubSelection: XtreamHubSelectionStore,
    private val overlay: IptvOverlayRepository,
    private val liveStore: XtreamLiveStore,
    private val libraryPreferences: LibraryPreferences,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val profileManager: ProfileManager,
) {
    suspend fun purge(accountId: String, origin: PlaylistRemovalOrigin) {
        val prefix = XtreamItemRegistry.accountPrefix(accountId)
        for (target in PlaylistRemovalCleanup.plan(origin)) {
            try {
                purgeTarget(target, accountId, prefix)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "purge $target failed for a removed playlist", e)
            }
        }
    }

    private suspend fun purgeTarget(target: PlaylistRemovalTarget, accountId: String, prefix: String) {
        when (target) {
            // Every source type: Xtream fills the per-playlist EPG tables too (xmltv lane + refills).
            PlaylistRemovalTarget.ContentDb -> contentDb.purge(accountId)
            PlaylistRemovalTarget.MatchIndex -> matchIndex.purge(accountId)
            // Mapping rows AND the schedule meta (mapped generation / last attempt).
            PlaylistRemovalTarget.EpgMirror -> epgMirror.purgeProvider(accountId)
            PlaylistRemovalTarget.RefreshStamp -> refreshStore.clear(accountId)
            PlaylistRemovalTarget.M3uFileCopy -> fileStore.delete(accountId)   // no-op when absent
            PlaylistRemovalTarget.CatchUp -> catchUpWinners.forget(accountId)
            PlaylistRemovalTarget.SessionCaches -> {
                registry.clear() // global in-memory map; rebuilds lazily on next browse
                searchIndex.evict(accountId)
                stalkerClient.evictCaches(accountId)   // cached lineup + create_link cmds
                EpgSourceLadder.sessionMemory.forgetAccount(accountId)
                // Learned per-channel playback engine (persisted by LiveEngineMemoryStore).
                LiveEngineMemory.forgetPrefix(prefix)
            }
            PlaylistRemovalTarget.HubSelection ->
                hubSelection.forgetAccountIf { PlaylistRemovalCleanup.dropsHubSelection(it, accountId) }
            PlaylistRemovalTarget.Overlay -> overlay.onPlaylistRemoved(accountId)
            PlaylistRemovalTarget.LiveChannels -> liveStore.migrateAccount(prefix, null)
            PlaylistRemovalTarget.SavedRefs -> {
                libraryPreferences.migrateIdPrefix(prefix, null)
                watchProgressPreferences.migrateIdPrefix(prefix, null, profileManager.activeProfileId.value)
                watchedItemsPreferences.migrateIdPrefix(prefix, null)
            }
        }
    }

    private companion object {
        const val TAG = "IptvAccountPurge"
    }
}
