package com.nuvio.tv.core.iptv

import android.database.sqlite.SQLiteDatabase
import com.nuvio.tv.core.analytics.LiveEngineMemory
import com.nuvio.tv.core.analytics.LiveRecoveryCoordinator
import com.nuvio.tv.core.epg.EpgMappingRow
import com.nuvio.tv.core.epg.EpgMirrorDb
import com.nuvio.tv.core.iptv.content.EpgProgramme
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.content.M3UFileStore
import com.nuvio.tv.core.iptv.match.IndexedItem
import com.nuvio.tv.core.iptv.match.MatchKind
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.iptv.refresh.IptvRefreshStore
import com.nuvio.tv.core.iptv.stalker.StalkerClient
import com.nuvio.tv.core.epg.EpgMirrorRepository
import com.nuvio.tv.core.iptv.overlay.ChannelOverlay
import com.nuvio.tv.core.iptv.overlay.CategoryOverlay
import com.nuvio.tv.core.iptv.overlay.IptvOverlayDb
import com.nuvio.tv.core.iptv.overlay.IptvOverlayRepository
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.XtreamHubSelectionStore
import com.nuvio.tv.data.local.XtreamLiveStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Regression: removing a playlist left its on-device data behind.
 *
 * Real (Robolectric) SQLite for every store the purge asserts on; seeds a doomed playlist AND a
 * surviving one, purges the doomed one, and checks its rows are gone while the survivor's stay.
 * Red on the old purge (IptvAccountPurge.purgeCaches): the in-flight EPG shadow rows, the EPG-mirror
 * schedule meta and the catch-up dialect winner all survived a delete, and the overlay had no purge.
 *
 * @Config as IptvContentDbTest: plain Application, sdk 35 (36 needs Java 21), Conscrypt OFF.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class IptvAccountPurgeTest {

    private val context = RuntimeEnvironment.getApplication()
    private val contentDb = IptvContentDb(context)
    private val matchIndex = XtreamMatchIndex(context)
    private val mirrorDb = EpgMirrorDb(context)
    private val refreshStore = IptvRefreshStore(context)
    private val winners = CatchUpWinnerStore(CatchUpWinnerPrefs(context))
    private val fileStore = M3UFileStore(context)

    private val doomed = "http://doomed.example.com|u"
    private val keeper = "http://keeper.example.com|u"

    private val overlayDb = IptvOverlayDb(context)
    private val profileManager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(PROFILE) }
    private val overlay = IptvOverlayRepository(overlayDb, mockk(relaxed = true), profileManager, mockk(relaxed = true))
    private val hubSelection = mockk<XtreamHubSelectionStore>(relaxed = true)
    private val liveStore = mockk<XtreamLiveStore>(relaxed = true)
    private val library = mockk<LibraryPreferences>(relaxed = true)
    private val progress = mockk<WatchProgressPreferences>(relaxed = true)
    private val watched = mockk<WatchedItemsPreferences>(relaxed = true)

    private fun newPurge() = IptvAccountPurge(
        registry = XtreamItemRegistry(),
        searchIndex = mockk<XtreamSearchIndex>(relaxed = true),
        matchIndex = matchIndex,
        contentDb = contentDb,
        refreshStore = refreshStore,
        fileStore = fileStore,
        epgMirror = EpgMirrorRepository(mirrorDb, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)),
        stalkerClient = mockk<StalkerClient>(relaxed = true),
        catchUpWinners = winners,
        hubSelection = hubSelection,
        overlay = overlay,
        liveStore = liveStore,
        libraryPreferences = library,
        watchProgressPreferences = progress,
        watchedItemsPreferences = watched,
        profileManager = profileManager,
    )

    private fun programme(start: Long) = EpgProgramme("bbc.uk", start, start + 1_000L, "Show $start", null)

    private suspend fun seed(id: String) {
        // Whole-guide refresh (epg_meta + programmes) and a per-channel lazy refill (fetch stamp).
        contentDb.replaceEpg(id, builtAtMs = 10L) { w -> w.add(programme(1_000L)) }
        contentDb.refillChannelEpg(id, "bbc.uk", listOf(programme(5_000L)), fetchedAtMs = 42L)
        // A refresh that died mid-fill leaves its rows staged in the shadow table.
        runCatching { contentDb.replaceEpg(id, builtAtMs = 20L) { w -> w.add(programme(9_000L)); w.flush(); error("killed mid-fill") } }
        matchIndex.rebuild(id, MatchKind.LIVE, listOf(IndexedItem(1, "BBC ONE", null, null, null, epgId = "bbc.uk")))
        mirrorDb.replaceMapping(id, listOf(EpgMappingRow(1, "BBCOne.uk", "exact")))
        mirrorDb.setMeta("acct_attempt_ms:$id", "123")
        refreshStore.markChecked(id, 77L)
        winners.remember(id, CatchUpDialectWalk.StoredWinner("sig", CatchUpDialectWalk.Dialect.entries.first()))
        // Learned playback engine for one of the playlist's channels (persisted per content id).
        LiveEngineMemory.remember("${XtreamItemRegistry.accountPrefix(id)}live:1", LiveEngineMemory.Lane.LIVE, LiveRecoveryCoordinator.Engine.MPV)
        // The saved local copy of a file playlist (user data: the picked original may be gone).
        fileStore.fileFor(id).writeText("#EXTM3U\n")
        // Personalization overlay (user data).
        overlayDb.setChannel(PROFILE, "chan:$id", id, ChannelOverlay(hidden = true), 1L)
        overlayDb.setCategory(PROFILE, id, "live", "cat:$id", CategoryOverlay(hidden = true), 1L)
    }

    private fun overlayHas(id: String): Boolean {
        val snap = overlayDb.snapshot(PROFILE)
        return "chan:$id" in snap.channels && "cat:$id" in snap.categories
    }

    private fun shadowRows(id: String): Int =
        SQLiteDatabase.openDatabase(context.getDatabasePath("iptv_content.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM epg_programmes_shadow WHERE playlist_id = ?", arrayOf(id)).use { c ->
                c.moveToFirst(); c.getInt(0)
            }
        }

    private fun freshWinners() = CatchUpWinnerStore(CatchUpWinnerPrefs(context))

    private suspend fun assertCachesGone(id: String) {
        assertTrue("guide rows purged", contentDb.epgWindow(id, "bbc.uk", 0L, 100_000L).isEmpty())
        assertNull("epg_meta purged", contentDb.epgBuiltAt(id))
        assertNull("fetch stamp purged", contentDb.epgChannelFetchedAt(id, "bbc.uk"))
        assertEquals("in-flight shadow rows purged", 0, shadowRows(id))
        assertNull("match index purged", matchIndex.builtAt(id, MatchKind.LIVE))
        assertTrue("mirror mapping purged", mirrorDb.mappingFor(id).isEmpty())
        assertNull("mirror schedule meta purged", mirrorDb.meta("acct_attempt_ms:$id"))
        assertNull("refresh stamp purged", refreshStore.lastCheckedMs(id))
        val w = freshWinners().also { it.useAccountPreference(id, false) }
        assertNull("catch-up winner purged", w.recall(id))
        assertNull("learned live engine purged", LiveEngineMemory.preferredEngine("${XtreamItemRegistry.accountPrefix(id)}live:1", LiveEngineMemory.Lane.LIVE))
    }

    private suspend fun assertCachesIntact(id: String) {
        assertTrue("survivor keeps guide rows", contentDb.epgWindow(id, "bbc.uk", 0L, 100_000L).isNotEmpty())
        assertEquals("survivor keeps its fetch stamp", 42L, contentDb.epgChannelFetchedAt(id, "bbc.uk"))
        assertNotNull("survivor keeps its match index", matchIndex.builtAt(id, MatchKind.LIVE))
        assertEquals("survivor keeps its mirror mapping", 1, mirrorDb.mappingFor(id).size)
        assertEquals("survivor keeps its mirror meta", "123", mirrorDb.meta("acct_attempt_ms:$id"))
        assertEquals("survivor keeps its refresh stamp", 77L, refreshStore.lastCheckedMs(id))
        val w = freshWinners().also { it.useAccountPreference(id, false) }
        assertNotNull("survivor keeps its catch-up winner", w.recall(id))
        assertEquals("survivor keeps its learned live engine", LiveRecoveryCoordinator.Engine.MPV,
            LiveEngineMemory.preferredEngine("${XtreamItemRegistry.accountPrefix(id)}live:1", LiveEngineMemory.Lane.LIVE))
    }

    @Test
    fun `deleting a playlist clears every store keyed by it and spares the others`() = runTest {
        seed(doomed); seed(keeper)

        newPurge().purge(doomed, PlaylistRemovalOrigin.UserDelete)

        assertCachesGone(doomed)
        assertCachesIntact(keeper)
        assertFalse("an explicit delete drops the playlist's overlay", overlayHas(doomed))
        assertTrue("survivor keeps its overlay", overlayHas(keeper))
        assertFalse("an explicit delete removes the M3U file copy", fileStore.exists(doomed))
        assertTrue("survivor keeps its M3U file copy", fileStore.exists(keeper))
        val prefix = XtreamItemRegistry.accountPrefix(doomed)
        coVerify { hubSelection.forgetAccountIf(any()) }
        coVerify { liveStore.migrateAccount(prefix, null) }
        coVerify { library.migrateIdPrefix(prefix, null) }
        coVerify { progress.migrateIdPrefix(prefix, null, PROFILE) }
        coVerify { watched.migrateIdPrefix(prefix, null) }
    }

    @Test
    fun `a playlist dropped by a sync pull loses its caches but keeps user data`() = runTest {
        seed(doomed); seed(keeper)

        newPurge().purge(doomed, PlaylistRemovalOrigin.SyncPull)

        assertCachesGone(doomed)
        assertCachesIntact(keeper)
        // A pull can be transient (B24): the user's own data is never dropped on its say-so.
        assertTrue("a pull removal leaves the overlay alone", overlayHas(doomed))
        // The file copy holds bytes the user picked; if the playlist comes back it re-ingests from it.
        assertTrue("a pull removal keeps the M3U file copy", fileStore.exists(doomed))
        assertTrue("survivor keeps its M3U file copy", fileStore.exists(keeper))
        coVerify { hubSelection.forgetAccountIf(any()) }
        coVerify(exactly = 0) { liveStore.migrateAccount(any(), any()) }
        coVerify(exactly = 0) { library.migrateIdPrefix(any(), any()) }
        coVerify(exactly = 0) { progress.migrateIdPrefix(any(), any(), any()) }
        coVerify(exactly = 0) { watched.migrateIdPrefix(any(), any()) }
    }

    private companion object {
        const val PROFILE = 1
    }
}
