package com.nuvio.tv.core.iptv

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchStateMutationStore
import com.nuvio.tv.core.sync.WatchStatePrefixMover
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Parity with Mobile's playlist removal: an explicit USER delete drops the playlist's watch progress
 * and watched marks locally AND queues their server deletes (Mobile: migrateIdPrefix(prefix, null) →
 * pushDeleteToServer), so a later pull cannot resurrect them as ghosts. A SYNC-PULL removal keeps
 * user data and queues nothing (PlaylistRemovalCleanup: SavedRefs is user data — Mobile's plan skips
 * it on pull too). Live progress is local-only; a non-full account syncs nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class IptvAccountPurgeWatchSyncTest {

    private val doomed = "http://doomed.example.com|u"
    private val prefix = XtreamItemRegistry.accountPrefix(doomed)

    private val movie = progress("${prefix}vod:1", "movie")
    private val episode = progress("${prefix}series:7", "series", season = 1, episode = 2)
    private val live = progress("${prefix}live:9", "live")
    private val other = progress("tt0111161", "movie")
    private val watchedMovie = watched("${prefix}vod:1", "movie")
    private val watchedEpisode = watched("${prefix}series:7", "series", season = 1, episode = 1)
    private val watchedOther = watched("tt0111161", "movie")

    @Test
    fun `a user delete queues server deletes for the playlist's progress and marks before the local purge`() = runTest {
        val h = harness(fullAccount = true)

        h.purge.purge(doomed, PlaylistRemovalOrigin.UserDelete)

        assertEquals(
            "old non-live progress keys queued for server delete",
            setOf(movie.contentId, "${episode.contentId}_s1e2"),
            h.mutations.pendingProgressDeletes(PROFILE),
        )
        assertEquals("a delete upserts nothing", emptyMap<String, WatchProgress>(), h.mutations.pendingProgressUpserts(PROFILE))
        assertEquals(
            "the playlist's watched marks queued for server delete",
            setOf(watchedMovie.mutationKey(), watchedEpisode.mutationKey()),
            h.mutations.pendingWatchedDeletes(PROFILE),
        )
        assertTrue("a delete upserts no mark", h.mutations.pendingWatchedUpserts(PROFILE).isEmpty())
        assertEquals("local progress dropped (live too)", setOf(other.contentId), h.progress.getAllRawEntries(PROFILE).keys)
        coVerifyOrder {
            h.mutations.queueRekey(any(), any(), any(), any(), PROFILE)
            h.progress.migrateIdPrefix(prefix, null, PROFILE)
            h.watched.migrateIdPrefix(prefix, null, PROFILE)
        }
        coVerify(exactly = 1) { h.progressSync.pushToRemote(PROFILE) }
        coVerify(exactly = 1) { h.watchedSync.pushToRemote(PROFILE) }

        // A pull before the push lands (old rows still on the server): no ghosts.
        val serverProgress = mapOf(movie.contentId to movie, "${episode.contentId}_s1e2" to episode, other.contentId to other)
        val serverWatched = listOf(watchedMovie, watchedEpisode, watchedOther)
        h.progress.replaceWithRemoteEntries(
            serverProgress,
            pendingUpsertKeys = h.mutations.pendingProgressUpserts(PROFILE).keys,
            pendingDeleteKeys = h.mutations.pendingProgressDeletes(PROFILE),
            profileId = PROFILE,
        )
        h.watched.replaceWithRemoteItems(
            serverWatched,
            pendingUpsertKeys = h.mutations.pendingWatchedUpserts(PROFILE).keys,
            pendingDeleteKeys = h.mutations.pendingWatchedDeletes(PROFILE),
            lastSuccessfulPushMs = Long.MAX_VALUE,
            profileId = PROFILE,
        )
        assertNoGhosts(h)

        // The deletes reach the server and are acknowledged; the next pull returns the server state.
        val progressAfter = serverProgress - h.mutations.pendingProgressDeletes(PROFILE)
        val watchedAfter = serverWatched.filterNot { it.mutationKey() in h.mutations.pendingWatchedDeletes(PROFILE) }
        h.mutations.acknowledgeProgressDeletes(h.mutations.pendingProgressDeletes(PROFILE), PROFILE)
        h.mutations.acknowledgeWatchedDeletes(h.mutations.pendingWatchedDeletes(PROFILE), PROFILE)
        h.progress.replaceWithRemoteEntries(progressAfter, profileId = PROFILE)
        h.watched.replaceWithRemoteItems(watchedAfter, lastSuccessfulPushMs = Long.MAX_VALUE, profileId = PROFILE)
        assertNoGhosts(h)
    }

    @Test
    fun `a sync-pull removal keeps user data and queues nothing`() = runTest {
        val h = harness(fullAccount = true)

        h.purge.purge(doomed, PlaylistRemovalOrigin.SyncPull)

        assertNothingQueued(h)
        assertEquals(
            "progress kept",
            setOf(movie.contentId, "${episode.contentId}_s1e2", live.contentId, other.contentId),
            h.progress.getAllRawEntries(PROFILE).keys,
        )
        assertEquals("marks kept", 3, h.watched.getAllItems(PROFILE).size)
        coVerify(exactly = 0) { h.progressSync.pushToRemote(any()) }
        coVerify(exactly = 0) { h.watchedSync.pushToRemote(any()) }
    }

    @Test
    fun `a user delete on a device that is not a full account drops locally and queues nothing`() = runTest {
        val h = harness(fullAccount = false)

        h.purge.purge(doomed, PlaylistRemovalOrigin.UserDelete)

        assertNothingQueued(h)
        assertEquals("local progress dropped", setOf(other.contentId), h.progress.getAllRawEntries(PROFILE).keys)
        assertEquals("local marks dropped", listOf(watchedOther), h.watched.getAllItems(PROFILE))
        coVerify(exactly = 0) { h.progressSync.pushToRemote(any()) }
        coVerify(exactly = 0) { h.watchedSync.pushToRemote(any()) }
    }

    private suspend fun assertNothingQueued(h: Harness) {
        assertTrue("no progress upsert queued", h.mutations.pendingProgressUpserts(PROFILE).isEmpty())
        assertTrue("no progress delete queued", h.mutations.pendingProgressDeletes(PROFILE).isEmpty())
        assertTrue("no watched upsert queued", h.mutations.pendingWatchedUpserts(PROFILE).isEmpty())
        assertTrue("no watched delete queued", h.mutations.pendingWatchedDeletes(PROFILE).isEmpty())
    }

    private suspend fun assertNoGhosts(h: Harness) {
        val keys = h.progress.getAllRawEntries(PROFILE).keys
        assertEquals("no progress ghost of the deleted playlist: $keys", setOf(other.contentId), keys)
        assertEquals("no watched ghost of the deleted playlist", listOf(watchedOther), h.watched.getAllItems(PROFILE))
    }

    private class Harness(
        val purge: IptvAccountPurge,
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
        every { profileManager.activeProfileId } returns MutableStateFlow(PROFILE)
        val progress = spyk(WatchProgressPreferences(factory, profileManager))
        val watched = spyk(WatchedItemsPreferences(factory, profileManager))
        val mutations = spyk(WatchStateMutationStore(factory))
        progress.saveProgressBatch(listOf(movie, episode, live, other), profileId = PROFILE)
        watched.markAsWatchedBatch(listOf(watchedMovie, watchedEpisode, watchedOther), profileId = PROFILE)

        val auth = mockk<AuthManager>()
        every { auth.isAuthenticated } returns fullAccount
        every { auth.canSync } returns fullAccount
        val progressSync = mockk<WatchProgressSyncService>()
        coEvery { progressSync.pushToRemote(any()) } returns Result.success(Unit)
        val watchedSync = mockk<WatchedItemsSyncService>()
        coEvery { watchedSync.pushToRemote(any()) } returns Result.success(Unit)

        val purge = IptvAccountPurge(
            registry = mockk(relaxed = true),
            searchIndex = mockk(relaxed = true),
            matchIndex = mockk(relaxed = true),
            contentDb = mockk(relaxed = true),
            refreshStore = mockk(relaxed = true),
            fileStore = mockk(relaxed = true),
            epgMirror = mockk(relaxed = true),
            stalkerClient = mockk(relaxed = true),
            catchUpWinners = mockk(relaxed = true),
            hubSelection = mockk(relaxed = true),
            overlay = mockk(relaxed = true),
            liveStore = mockk(relaxed = true),
            libraryPreferences = mockk(relaxed = true),
            profileManager = profileManager,
            serverFailover = mockk(relaxed = true),
            watchState = WatchStatePrefixMover(auth, mutations, progress, watched, progressSync, watchedSync),
        )
        return Harness(purge, progress, watched, mutations, progressSync, watchedSync)
    }

    private fun progress(contentId: String, type: String, season: Int? = null, episode: Int? = null) = WatchProgress(
        contentId = contentId, contentType = type, name = contentId, poster = null, backdrop = null, logo = null,
        videoId = contentId, season = season, episode = episode, episodeTitle = null,
        position = 60_000L, duration = 600_000L, lastWatched = 1_000L,
    )

    private fun watched(contentId: String, type: String, season: Int? = null, episode: Int? = null) = WatchedItem(
        contentId = contentId, contentType = type, title = contentId, season = season, episode = episode, watchedAt = 1_000L,
    )

    private companion object {
        const val PROFILE = 1
    }
}
