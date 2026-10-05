package com.nuvio.tv.core.iptv

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.iptv.content.M3UFileStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchStateMutationStore
import com.nuvio.tv.core.sync.WatchStatePrefixMover
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.XtreamLiveStore
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.mutationKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 0 parity (Mobile e6b7026a1): a pull-time key adoption must SYNC the re-key of watch progress
 * and watched marks — delete under the old playlist id + upsert under the new one — or the server
 * keeps rows under the old id and a later pull resurrects them as ghost Continue Watching / watched
 * entries pointing at a playlist id that no longer exists. Live progress stays local-only (never
 * synced), and a device that is not a full account syncs nothing.
 */
class PlaylistKeyRekeyWatchSyncTest {

    private val oldId = "file:0b8f6c1e-uuid"
    private val newId = "m3u_file|tv.m3u|synced"
    private val oldPrefix = XtreamItemRegistry.accountPrefix(oldId)
    private val newPrefix = XtreamItemRegistry.accountPrefix(newId)

    private val local = XtreamAccount(
        id = oldId, name = "TV", baseUrl = "", username = "", password = "",
        sourceType = XtreamAccount.SOURCE_FILE, fileName = "tv.m3u",
    )
    private val pulled = listOf(PulledPlaylist(local.copy(id = newId), serverKeyed = true))

    // Seeded local state under the old id (+ one unrelated entry of each kind).
    private val movie = progress("${oldPrefix}vod:1", "movie")
    private val episode = progress("${oldPrefix}series:7", "series", season = 1, episode = 2)
    private val live = progress("${oldPrefix}live:9", "live")
    private val other = progress("tt0111161", "movie")
    private val watchedMovie = watched("${oldPrefix}vod:1", "movie")
    private val watchedEpisode = watched("${oldPrefix}series:7", "series", season = 1, episode = 1)
    private val watchedOther = watched("tt0111161", "movie")

    @Test
    fun `adoption queues delete-old plus upsert-new for progress and watched, durably before the local rewrite`() = runTest {
        val h = harness(fullAccount = true)

        h.rekeyer.adoptFromPull(1, pulled)

        val movedMovie = movie.copy(contentId = "${newPrefix}vod:1", videoId = "${newPrefix}vod:1")
        val movedEpisode = episode.copy(contentId = "${newPrefix}series:7", videoId = "${newPrefix}series:7")
        assertEquals(
            "old progress keys are queued for server delete (live is local-only: never synced)",
            setOf(movie.contentId, "${episode.contentId}_s1e2"),
            h.mutations.pendingProgressDeletes(1),
        )
        assertEquals(
            "every moved non-live entry is queued for upsert under the new id",
            mapOf(movedMovie.contentId to movedMovie, "${movedEpisode.contentId}_s1e2" to movedEpisode),
            h.mutations.pendingProgressUpserts(1),
        )
        assertEquals(
            "old watched marks are queued for server delete",
            setOf(watchedMovie.mutationKey(), watchedEpisode.mutationKey()),
            h.mutations.pendingWatchedDeletes(1),
        )
        assertEquals(
            "every moved mark is queued for upsert under the new id",
            setOf(
                watchedMovie.copy(contentId = "${newPrefix}vod:1"),
                watchedEpisode.copy(contentId = "${newPrefix}series:7"),
            ),
            h.mutations.pendingWatchedUpserts(1).values.toSet(),
        )
        // Local stores moved (live included — it just never syncs).
        assertEquals(
            setOf(movedMovie.contentId, "${movedEpisode.contentId}_s1e2", "${newPrefix}live:9", other.contentId),
            h.progress.getAllRawEntries(1).keys,
        )
        // Crash safety: the outbox is written BEFORE the local stores move, in one edit.
        coVerifyOrder {
            h.mutations.queueRekey(any(), any(), any(), any(), 1)
            h.progress.migrateIdPrefix(oldPrefix, newPrefix, 1)
            h.watched.migrateIdPrefix(oldPrefix, newPrefix, 1)
        }
        // Pushed right away when the session can sync (through the existing outbox pushers).
        coVerify(exactly = 1) { h.progressSync.pushToRemote(1) }
        coVerify(exactly = 1) { h.watchedSync.pushToRemote(1) }
    }

    @Test
    fun `a pull after the queued ops leaves no ghost entries, before or after they reach the server`() = runTest {
        val h = harness(fullAccount = true)
        // What the server holds before adoption (live progress was never pushed).
        val serverProgress = mapOf(movie.contentId to movie, "${episode.contentId}_s1e2" to episode, other.contentId to other)
        val serverWatched = listOf(watchedMovie, watchedEpisode, watchedOther)

        h.rekeyer.adoptFromPull(1, pulled)

        // 1) A pull lands BEFORE the push (or the app died before it): the old rows still come back
        //    from the server, the pending outbox must keep them out and keep the moved rows in.
        h.progress.replaceWithRemoteEntries(
            serverProgress,
            pendingUpsertKeys = h.mutations.pendingProgressUpserts(1).keys,
            pendingDeleteKeys = h.mutations.pendingProgressDeletes(1),
            profileId = 1,
        )
        h.watched.replaceWithRemoteItems(
            serverWatched,
            pendingUpsertKeys = h.mutations.pendingWatchedUpserts(1).keys,
            pendingDeleteKeys = h.mutations.pendingWatchedDeletes(1),
            lastSuccessfulPushMs = Long.MAX_VALUE,
            profileId = 1,
        )
        assertNoGhosts(h)

        // 2) The outbox reaches the server (deletes, then upserts), is acknowledged, and a later
        //    pull returns exactly the server's state: still no ghosts, nothing lost.
        val progressAfter = serverProgress - h.mutations.pendingProgressDeletes(1) + h.mutations.pendingProgressUpserts(1)
        val watchedAfter = serverWatched.filterNot { it.mutationKey() in h.mutations.pendingWatchedDeletes(1) } +
            h.mutations.pendingWatchedUpserts(1).values
        h.mutations.acknowledgeProgressDeletes(h.mutations.pendingProgressDeletes(1), 1)
        h.mutations.acknowledgeProgressUpserts(h.mutations.pendingProgressUpserts(1), 1)
        h.mutations.acknowledgeWatchedDeletes(h.mutations.pendingWatchedDeletes(1), 1)
        h.mutations.acknowledgeWatchedUpserts(h.mutations.pendingWatchedUpserts(1).values, 1)
        assertTrue("server holds nothing under the old id", progressAfter.keys.none { it.startsWith(oldPrefix) })
        assertTrue("server holds no watched mark under the old id", watchedAfter.none { it.contentId.startsWith(oldPrefix) })

        h.progress.replaceWithRemoteEntries(progressAfter, profileId = 1)
        h.watched.replaceWithRemoteItems(watchedAfter, lastSuccessfulPushMs = Long.MAX_VALUE, profileId = 1)
        assertNoGhosts(h)
    }

    @Test
    fun `a device that is not a full account re-keys locally and queues or pushes nothing`() = runTest {
        val h = harness(fullAccount = false)

        h.rekeyer.adoptFromPull(1, pulled)

        assertTrue("no progress op queued", h.mutations.pendingProgressUpserts(1).isEmpty())
        assertTrue("no progress delete queued", h.mutations.pendingProgressDeletes(1).isEmpty())
        assertTrue("no watched op queued", h.mutations.pendingWatchedUpserts(1).isEmpty())
        assertTrue("no watched delete queued", h.mutations.pendingWatchedDeletes(1).isEmpty())
        assertTrue(
            "the local re-key still happened",
            h.progress.getAllRawEntries(1).keys.none { it.startsWith(oldPrefix) } &&
                h.watched.getAllItems(1).none { it.contentId.startsWith(oldPrefix) },
        )
        coVerify(exactly = 0) { h.progressSync.pushToRemote(any()) }
        coVerify(exactly = 0) { h.watchedSync.pushToRemote(any()) }
    }

    private suspend fun assertNoGhosts(h: Harness) {
        val progressKeys = h.progress.getAllRawEntries(1).keys
        assertTrue("no progress ghost under the old id: $progressKeys", progressKeys.none { it.startsWith(oldPrefix) })
        assertTrue("moved progress kept: $progressKeys", "${newPrefix}vod:1" in progressKeys && "${newPrefix}series:7_s1e2" in progressKeys)
        val watchedIds = h.watched.getAllItems(1).map { it.contentId to it.episode }.toSet()
        assertTrue("no watched ghost under the old id: $watchedIds", watchedIds.none { it.first.startsWith(oldPrefix) })
        assertEquals(
            "moved marks kept",
            setOf("${newPrefix}vod:1" to null, "${newPrefix}series:7" to 1, "tt0111161" to null),
            watchedIds,
        )
    }

    private class Harness(
        val rekeyer: PlaylistKeyRekeyer,
        val progress: WatchProgressPreferences,
        val watched: WatchedItemsPreferences,
        val mutations: WatchStateMutationStore,
        val progressSync: WatchProgressSyncService,
        val watchedSync: WatchedItemsSyncService,
    )

    private suspend fun harness(fullAccount: Boolean): Harness {
        val stores = mutableMapOf<Pair<Int, String>, DataStore<Preferences>>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg<Int>() to secondArg<String>()) { TestPreferencesStore() }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val progress = spyk(WatchProgressPreferences(factory, profileManager))
        val watched = spyk(WatchedItemsPreferences(factory, profileManager))
        val mutations = spyk(WatchStateMutationStore(factory))
        progress.saveProgressBatch(listOf(movie, episode, live, other), profileId = 1)
        watched.markAsWatchedBatch(listOf(watchedMovie, watchedEpisode, watchedOther), profileId = 1)

        val accountStore = mockk<XtreamAccountStore>(relaxed = true)
        coEvery { accountStore.accountsForProfile(1) } returns listOf(local)
        val auth = mockk<AuthManager>()
        every { auth.isAuthenticated } returns fullAccount
        every { auth.canSync } returns fullAccount
        val progressSync = mockk<WatchProgressSyncService>()
        coEvery { progressSync.pushToRemote(any()) } returns Result.success(Unit)
        val watchedSync = mockk<WatchedItemsSyncService>()
        coEvery { watchedSync.pushToRemote(any()) } returns Result.success(Unit)

        val rekeyer = PlaylistKeyRekeyer(
            accountStore = accountStore,
            libraryPreferences = mockk<LibraryPreferences>(relaxed = true),
            liveStore = mockk<XtreamLiveStore>(relaxed = true),
            fileStore = mockk<M3UFileStore>(relaxed = true),
            purge = mockk<IptvAccountPurge>(relaxed = true),
            watchState = WatchStatePrefixMover(auth, mutations, progress, watched, progressSync, watchedSync),
            m3uIds = dagger.Lazy { mockk<M3uIdRekeyer>(relaxed = true) },
        )
        return Harness(rekeyer, progress, watched, mutations, progressSync, watchedSync)
    }

    private fun progress(contentId: String, type: String, season: Int? = null, episode: Int? = null) = WatchProgress(
        contentId = contentId,
        contentType = type,
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = 60_000L,
        duration = 600_000L,
        lastWatched = 1_000L,
    )

    private fun watched(contentId: String, type: String, season: Int? = null, episode: Int? = null) = WatchedItem(
        contentId = contentId,
        contentType = type,
        title = contentId,
        season = season,
        episode = episode,
        watchedAt = 1_000L,
    )
}
