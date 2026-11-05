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

    private fun guideWith(account: XtreamAccount): XtreamLiveGuideViewModel {
        val factory = mockk<IptvClientFactory> { every { clientFor(any()) } returns provider }
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
            contentDb = mockk(relaxed = true),
            catchUp = mockk(relaxed = true),
            matchIndex = mockk(relaxed = true),
            xmltv = mockk<com.nuvio.tv.core.iptv.epg.XmltvClient>(relaxed = true) {
                every { guideCommitted } returns MutableSharedFlow()
                coEvery { storedWindow(any(), any(), any(), any()) } returns stored
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
}
