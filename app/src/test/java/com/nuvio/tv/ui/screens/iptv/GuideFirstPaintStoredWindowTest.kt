package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.iptv.BoundedLoad
import com.nuvio.tv.core.iptv.IptvClient
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.IptvImportProgress
import com.nuvio.tv.core.iptv.LoadStatus
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.content.EpgProgramme
import com.nuvio.tv.core.iptv.XtreamCategory
import com.nuvio.tv.core.iptv.XtreamChannel
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamLivePlaylist
import com.nuvio.tv.core.iptv.XtreamMovie
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.core.iptv.XtreamSeriesDetail
import com.nuvio.tv.core.iptv.XtreamSeriesItem
import com.nuvio.tv.core.iptv.overlay.OverlaySnapshot
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.XtreamLiveStore
import com.nuvio.tv.domain.repository.LibraryRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * First paint of the M3U guide (Android TV emulator): the rows showed only now + next for ~2 minutes,
 * until focus moved. The first channel is marked focused as the list lands, so the UI's own focus
 * event for it is a no-op and the stored-window read (which only ran on focus and on data commit)
 * never ran for the opening screen. The opening screen must publish the stored window itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideFirstPaintStoredWindowTest {
    @get:Rule val main = MainDispatcherRule()

    private val m3u = XtreamAccount(
        id = "http://list.invalid/p.m3u|", name = "List", baseUrl = "http://list.invalid/p.m3u", username = "", password = "",
        sourceType = XtreamAccount.SOURCE_URL,
    )
    private val xtream = m3u.copy(id = "http://guide.invalid|u", sourceType = XtreamAccount.SOURCE_XTREAM)
    private val realSink = BoundedLoad.sink
    private val half = 30 * 60_000L
    private val stored: List<EpgProgramme> = run {
        val base = System.currentTimeMillis() / half * half
        (-3..3).map { EpgProgramme("epg:7", base + it * half, base + (it + 1) * half, "P$it", null) }
    }

    private val provider = object : IptvClient {
        override suspend fun liveCategories(acc: XtreamAccount): Result<List<XtreamCategory>> = Result.success(listOf(XtreamCategory("1", "News")))
        override suspend fun vodCategories(acc: XtreamAccount) = Result.success(emptyList<XtreamCategory>())
        override suspend fun seriesCategories(acc: XtreamAccount) = Result.success(emptyList<XtreamCategory>())
        override suspend fun liveChannels(acc: XtreamAccount, categoryId: String?): Result<List<XtreamChannel>> = Result.success(
            listOf(XtreamChannel(streamId = 7, name = "BBC One", logo = null, epgChannelId = null, categoryId = "1", hasArchive = false, streamUrl = "http://guide.invalid/live/7.ts")),
        )
        override suspend fun vodMovies(acc: XtreamAccount, categoryId: String?) = Result.success(emptyList<XtreamMovie>())
        override suspend fun series(acc: XtreamAccount, categoryId: String?) = Result.success(emptyList<XtreamSeriesItem>())
        override suspend fun seriesInfo(acc: XtreamAccount, seriesId: Int): Result<XtreamSeriesDetail> = Result.failure(UnsupportedOperationException())
        override suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int) = Result.success(emptyList<XtreamProgram>())
        override suspend fun resolveStreamUrl(acc: XtreamAccount, kind: String, streamId: Int, forceFresh: Boolean): String? = null
    }

    @Before
    fun setUp() {
        BoundedLoad.sink = { _, _ -> }
    }

    @After
    fun tearDown() {
        BoundedLoad.sink = realSink
    }

    private fun guideWith(
        account: XtreamAccount,
        client: IptvClient = provider,
        db: com.nuvio.tv.core.iptv.content.IptvContentDb = mockk(relaxed = true),
    ): XtreamLiveGuideViewModel {
        val factory = mockk<IptvClientFactory> { every { clientFor(any()) } returns client }
        val profiles = mockk<ProfileManager>(relaxed = true) { every { activeProfileId } returns MutableStateFlow(1) }
        val overlay = mockk<com.nuvio.tv.core.iptv.overlay.IptvOverlayRepository>(relaxed = true) {
            every { uiState } returns MutableStateFlow(OverlaySnapshot.EMPTY)
        }
        val mirror = mockk<com.nuvio.tv.core.epg.EpgMirrorRepository>(relaxed = true) {
            every { programmesCommitted } returns MutableSharedFlow()
        }
        return XtreamLiveGuideViewModel(
            clientFactory = factory,
            registry = XtreamItemRegistry(),
            liveStore = mockk<XtreamLiveStore>(relaxed = true) { every { recents } returns flowOf(emptyList()) },
            livePlaylist = XtreamLivePlaylist(),
            profileManager = profiles,
            libraryRepository = mockk<LibraryRepository>(relaxed = true) { every { libraryItems } returns flowOf(emptyList()) },
            epgMirror = mirror,
            contentDb = db,
            catchUp = mockk(relaxed = true),
            matchIndex = mockk(relaxed = true),
            xmltv = mockk<com.nuvio.tv.core.iptv.epg.XmltvClient>(relaxed = true) {
                every { guideCommitted } returns MutableSharedFlow()
                coEvery { storedWindow(any(), any(), any(), any()) } returns (if (account.sourceType == XtreamAccount.SOURCE_STALKER) emptyList() else stored)
            },
            overlayRepository = overlay,
            accountStore = mockk<com.nuvio.tv.data.local.XtreamAccountStore>(relaxed = true) {
                every { accounts } returns flowOf(listOf(account))
            },
        )
    }

    /** The guide maps channels on Dispatchers.Default (a real thread): drain the test scheduler until it lands. */
    private fun kotlinx.coroutines.test.TestScope.settle(vm: XtreamLiveGuideViewModel, done: (LiveGuideUiState) -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!done(vm.uiState.value) && System.currentTimeMillis() < until) {
            runCurrent()
            Thread.sleep(5)
        }
        runCurrent()
        Thread.sleep(50)
        runCurrent()
    }

    private fun guide(acc: XtreamAccount): XtreamLiveGuideViewModel = guideWith(acc)

    private val stalker = m3u.copy(id = "stalker|portal", sourceType = XtreamAccount.SOURCE_STALKER)

    /** A Stalker portal's bulk guide, keyed by the portal's channel id: the past half-hours carry programme ids. */
    private val stalkerStored: List<EpgProgramme> = (-3..3).map {
        EpgProgramme("7", stored[it + 3].startMs, stored[it + 3].endMs, "P$it", null, hasArchive = it < 0, programmeId = if (it < 0) "9${it + 3}" else null)
    }

    @Test
    fun `an M3U guide opens with its stored window, not just now and next`() = runTest(main.dispatcher) {
        val vm = guide(m3u)
        vm.setAccount(m3u)
        settle(vm) { it.channelsLoad == LoadStatus.Loaded && it.epg[7]?.storedWindow == true }

        val row = vm.uiState.value.epg[7]
        assertNotNull("the first row has guide data without any focus move", row)
        assertTrue("it is the stored window", row!!.storedWindow)
        assertEquals("every stored cell is on the row", stored.size, row.programmes.size)
    }

    @Test
    fun `an Xtream guide keeps its now-next first paint at the live edge`() = runTest(main.dispatcher) {
        val vm = guide(xtream)
        vm.setAccount(xtream)
        settle(vm) { it.channelsLoad == LoadStatus.Loaded }

        assertTrue("the panel's per-channel history fetch owns that window, not the store", vm.uiState.value.epg[7]?.storedWindow != true)
    }

    @Test
    fun `a Stalker guide opens with the past of its stored bulk guide`() = runTest(main.dispatcher) {
        val db = mockk<com.nuvio.tv.core.iptv.content.IptvContentDb>(relaxed = true) {
            coEvery { epgWindow(stalker.id, "7", any(), any(), any()) } returns stalkerStored
        }
        val vm = guideWith(stalker, db = db)
        vm.setAccount(stalker)
        settle(vm) { it.channelsLoad == LoadStatus.Loaded && it.epg[7]?.storedWindow == true }

        val row = vm.uiState.value.epg[7]
        assertNotNull("the first row has guide data without any focus move", row)
        assertTrue("it is the stored window", row!!.storedWindow)
        val past = row.programmes.filter { it.endMs <= System.currentTimeMillis() }
        assertTrue("the aired programmes are on the row", past.isNotEmpty())
        assertTrue("with the portal's programme ids a replay is addressed by", past.all { it.programmeId != null && it.hasArchive == true })
    }

    /**
     * The first run: the bulk guide is not on disk when the guide opens, and lands while the now/next ask
     * waits on it. The stored window read before that found nothing; it must be read again once it can.
     */
    @Test
    fun `a Stalker guide shows the past once the bulk guide lands`() = runTest(main.dispatcher) {
        var landed = false
        val db = mockk<com.nuvio.tv.core.iptv.content.IptvContentDb>(relaxed = true) {
            coEvery { epgWindow(stalker.id, "7", any(), any(), any()) } answers { if (landed) stalkerStored else emptyList() }
        }
        val now = System.currentTimeMillis()
        val stalkerClient = object : IptvClient by provider {
            override suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int): Result<List<XtreamProgram>> {
                landed = true   // ensureBulkEpg finished: the guide is on disk
                return Result.success(listOf(XtreamProgram("Now", "", now - half / 2, now + half / 2, nowPlaying = true)))
            }
        }
        val vm = guideWith(stalker, client = stalkerClient, db = db)
        vm.setAccount(stalker)
        settle(vm) { it.channelsLoad == LoadStatus.Loaded && it.epg[7]?.storedWindow == true }

        assertTrue("the stored window reached the row", vm.uiState.value.epg[7]?.storedWindow == true)
    }
}
