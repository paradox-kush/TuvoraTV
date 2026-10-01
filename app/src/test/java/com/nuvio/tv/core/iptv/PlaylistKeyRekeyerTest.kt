package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.content.M3UFileStore
import com.nuvio.tv.core.sync.WatchStatePrefixMover
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.XtreamLiveStore
import com.nuvio.tv.data.local.renameStoredAccountIds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Step 0 — the TV executor of a key adoption: every prefix-keyed store is re-written onto the new
 * key (NEVER dropped — no store is ever called with a null new prefix), the file copy moves with the
 * id (real file store), caches go through the SyncPull purge (caches only), and a second pull with
 * the ids already matching touches nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaylistKeyRekeyerTest {

    private val context = RuntimeEnvironment.getApplication()
    private val fileStore = M3UFileStore(context)
    private val accountStore = mockk<XtreamAccountStore>(relaxed = true)
    private val library = mockk<LibraryPreferences>(relaxed = true)
    private val progress = mockk<WatchProgressPreferences>(relaxed = true)
    private val watched = mockk<WatchedItemsPreferences>(relaxed = true)
    private val liveStore = mockk<XtreamLiveStore>(relaxed = true)
    private val purge = mockk<IptvAccountPurge>(relaxed = true)
    private val watchState = WatchStatePrefixMover(
        authManager = mockk(relaxed = true), // not a full account: local moves only
        mutationStore = mockk(relaxed = true),
        watchProgressPreferences = progress,
        watchedItemsPreferences = watched,
        watchProgressSyncService = mockk(relaxed = true),
        watchedItemsSyncService = mockk(relaxed = true),
    )
    private val rekeyer = PlaylistKeyRekeyer(accountStore, library, liveStore, fileStore, purge, watchState)

    private val oldId = "file:0b8f6c1e-uuid"
    private val newId = "m3u_file|tv.m3u|synced"
    private val local = XtreamAccount(
        id = oldId, name = "TV", baseUrl = "", username = "", password = "",
        sourceType = XtreamAccount.SOURCE_FILE, fileName = "tv.m3u",
    )
    private val pulled = listOf(PulledPlaylist(local.copy(id = newId), serverKeyed = true))

    @Test
    fun `adoption moves every saved store and the file copy onto the key and drops nothing`() = runTest {
        coEvery { accountStore.accountsForProfile(1) } returns listOf(local)
        fileStore.fileFor(oldId).writeText("#EXTM3U\n#EXTINF:-1,BBC\nhttp://x/1.ts\n")
        val oldPrefix = XtreamItemRegistry.accountPrefix(oldId)
        val newPrefix = XtreamItemRegistry.accountPrefix(newId)

        val result = rekeyer.adoptFromPull(1, pulled)

        assertEquals(listOf(PlaylistKeyAdoption.Rekey(oldId, newId)), result.rekeys)
        coVerify(exactly = 1) { accountStore.renameIds(1, listOf(PlaylistKeyAdoption.Rekey(oldId, newId))) }
        coVerify(exactly = 1) { library.migrateIdPrefix(oldPrefix, newPrefix) }
        coVerify(exactly = 1) { progress.migrateIdPrefix(oldPrefix, newPrefix, 1) }
        coVerify(exactly = 1) { watched.migrateIdPrefix(oldPrefix, newPrefix, 1) }
        coVerify(exactly = 1) { liveStore.migrateAccount(oldPrefix, any()) }
        // A null new prefix is a DROP — adoption must never ask any store for one.
        coVerify(exactly = 0) { library.migrateIdPrefix(any(), null) }
        coVerify(exactly = 0) { progress.migrateIdPrefix(any(), null, any()) }
        coVerify(exactly = 0) { watched.migrateIdPrefix(any(), null, any()) }
        coVerify(exactly = 0) { liveStore.migrateAccount(any(), null) }
        coVerify(exactly = 1) { purge.purge(oldId, PlaylistRemovalOrigin.SyncPull) }
        coVerify(exactly = 0) { purge.purge(any(), PlaylistRemovalOrigin.UserDelete) }
        assertTrue("the file copy followed the id", fileStore.exists(newId))
        assertFalse("and was moved, not copied", fileStore.exists(oldId))
        assertTrue(fileStore.fileFor(newId).readText().contains("BBC"))

        // The next pull: ids already match — nothing is touched again.
        coEvery { accountStore.accountsForProfile(1) } returns listOf(local.copy(id = newId))
        assertEquals(emptyList<PlaylistKeyAdoption.Rekey>(), rekeyer.adoptFromPull(1, pulled).rekeys)
        coVerify(exactly = 1) { library.migrateIdPrefix(any(), any()) }
        coVerify(exactly = 1) { purge.purge(any(), any()) }
    }

    @Test
    fun `renaming stored ids keeps every other key of the row`() {
        val raw = """[{"id":"old","name":"P","futureField":{"x":1}},{"id":"keep","name":"Q"}]"""
        val out = renameStoredAccountIds(raw, listOf(PlaylistKeyAdoption.Rekey("old", "new")))!!
        assertEquals("""[{"id":"new","name":"P","futureField":{"x":1}},{"id":"keep","name":"Q"}]""", out)
    }
}
