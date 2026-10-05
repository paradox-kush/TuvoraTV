package com.nuvio.tv.core.iptv

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.WatchStatePrefixMover
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.XtreamLiveStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B64 phase 3 (TV) — moves each profile's saved refs of an M3U playlist (library incl. live favourites,
 * watch progress, watched marks, live channel refs, category selections) from the pre-B64 ids onto the
 * login-free ones, using the moves [IptvContentDb.captureLegacyIds] kept from the device's last pre-B64
 * catalog ([M3uLegacyIds] / [M3uSavedIdRewrite]).
 *
 * Runs for the ACTIVE profile at start, on every profile switch, and after every catalog rebuild; once
 * per (profile, playlist) — a marker in the profile's DataStore — and only once the playlist's catalog
 * is on the new ids (before that the old ids are still the right ones). Each run first logs what it
 * would move (dry run), then writes through each store's synced path (library: sync-reducer delete +
 * upsert; progress / watched: [WatchStatePrefixMover.rewrite], ops queued before the local change;
 * selections: a recorded v2 playlist edit).
 */
@Singleton
class M3uIdRekeyer @Inject constructor(
    private val db: IptvContentDb,
    private val m3uClient: M3UClient,
    private val accountStore: XtreamAccountStore,
    private val profileManager: ProfileManager,
    private val factory: ProfileDataStoreFactory,
    private val libraryPreferences: LibraryPreferences,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val watchState: WatchStatePrefixMover,
    private val liveStore: XtreamLiveStore,
    private val playlistSync: dagger.Lazy<com.nuvio.tv.core.sync.XtreamAccountSyncService>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val doneKey = stringSetPreferencesKey("m3u_ids_v2_done")
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            profileManager.activeProfileId.collect { pid -> guard("profile $pid") { runForProfile(pid) } }
        }
        scope.launch {
            m3uClient.catalogRebuilt.collect { playlistId ->
                guard("rebuilt $playlistId") { runForProfile(profileManager.activeProfileId.value, onlyPlaylist = playlistId) }
            }
        }
    }

    suspend fun runForProfile(profileId: Int, onlyPlaylist: String? = null) = mutex.withLock {
        if (profileManager.activeProfileId.value != profileId) return@withLock
        val markers = factory.get(profileId, FEATURE)
        val done = markers.data.first()[doneKey].orEmpty()
        val playlists = accountStore.accountsForProfile(profileId)
            .filter { it.isM3UBacked() && it.id !in done && (onlyPlaylist == null || it.id == onlyPlaylist) }
        for (acc in playlists) {
            // Still on the old ids (not rebuilt yet): those ARE the right ids for now — wait.
            if (db.idScheme(acc.id) < M3UClient.M3U_ID_SCHEME) continue
            apply(profileId, acc)
            markers.edit { it[doneKey] = it[doneKey].orEmpty() + acc.id }
        }
    }

    /** Step 0 key adoption renamed a playlist: its kept legacy moves and this profile's marker follow. */
    suspend fun onPlaylistRekeyed(profileId: Int, oldId: String, newId: String) {
        db.moveLegacyIds(oldId, newId)
        factory.get(profileId, FEATURE).edit { prefs ->
            val done = prefs[doneKey].orEmpty()
            if (oldId in done) prefs[doneKey] = done - oldId + newId
        }
    }

    private suspend fun apply(profileId: Int, acc: XtreamAccount) {
        if (!db.hasLegacyIds(acc.id)) return   // added after B64, or nothing to move
        val prefix = XtreamItemRegistry.accountPrefix(acc.id)
        val probe = M3uSavedIdRewrite(acc.id, emptyMap())

        // 1. Dry run — what this profile saved under the playlist.
        val library = libraryPreferences.getAllItems(profileId).filter { it.id.startsWith(prefix) }
        val progress = watchProgressPreferences.getAllRawEntries(profileId).values
            .filter { it.contentId.startsWith(prefix) || it.videoId.startsWith(prefix) }
        val watched = watchedItemsPreferences.getAllItems(profileId).filter { it.contentId.startsWith(prefix) }
        val live = liveStore.idsWithPrefix(prefix)
        val suffixes = buildSet {
            library.forEach { probe.suffixOf(it.id)?.let(::add) }
            progress.forEach { p -> probe.suffixOf(p.contentId)?.let(::add); probe.suffixOf(p.videoId)?.let(::add) }
            watched.forEach { probe.suffixOf(it.contentId)?.let(::add) }
            live.forEach { probe.suffixOf(it)?.let(::add) }
            val sel = acc.categorySelections
            listOf(
                IptvContentDb.TYPE_LIVE to sel.live, IptvContentDb.TYPE_VOD to sel.movies, IptvContentDb.TYPE_SERIES to sel.series,
            ).forEach { (type, ids) -> ids?.forEach { add("cat:$type:$it") } }
        }
        if (suffixes.isEmpty()) return
        val rewrite = M3uSavedIdRewrite(acc.id, db.legacyIds(acc.id, suffixes))
        val newSelections = CategorySelections(
            live = rewrite.categories(XtreamAccount.TYPE_LIVE, acc.categorySelections.live) ?: acc.categorySelections.live,
            movies = rewrite.categories(XtreamAccount.TYPE_MOVIES, acc.categorySelections.movies) ?: acc.categorySelections.movies,
            series = rewrite.categories(XtreamAccount.TYPE_SERIES, acc.categorySelections.series) ?: acc.categorySelections.series,
        )
        Log.i(
            TAG,
            "B64 re-key ${acc.name}: library=${library.count { rewrite.library(it) != null }} " +
                "progress=${progress.count { rewrite.progress(it) != null }} watched=${watched.count { rewrite.watched(it) != null }} " +
                "live=${live.count { rewrite.contentId(it) != null }} selections=${newSelections != acc.categorySelections}",
        )

        // 2. Writes, each through its store's synced path.
        libraryPreferences.rekeyItems(rewrite::library)
        if (watchState.rewrite(profileId, rewrite::progress, rewrite::watched)) watchState.pushQueued(profileId)
        liveStore.migrateAccount(prefix) { ref -> rewrite.liveRef(ref) ?: ref }
        if (newSelections != acc.categorySelections) {
            accountStore.update(acc.id) { it.copy(categorySelections = newSelections) }
            playlistSync.get().triggerRemoteSync()
        }
    }

    private suspend fun guard(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "B64 re-key ($what) failed — retried on the next run", e)
        }
    }

    private companion object {
        const val TAG = "M3uIdRekeyer"
        const val FEATURE = "m3u_id_rekey"
    }
}
