package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.iptv.BoundedLoad
import com.nuvio.tv.core.iptv.IptvClient
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.IptvImportProgress
import com.nuvio.tv.core.iptv.LoadStatus
import com.nuvio.tv.core.iptv.XtreamAccount
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
 * Regression ("Live TV spins forever", the TV guide): the category column read `liveCategories(...)
 * .getOrDefault(emptyList())` with no deadline — a stalled provider left the guide blank forever, and a failed
 * one read as "this playlist has no groups" — and the channel list's `loadingChannels = true` had nothing
 * guaranteeing it ever switched off. Both now go through BoundedLoad (real deadlines, virtual time).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveGuideBoundedLoadTest {
    @get:Rule val main = MainDispatcherRule()

    private val account = XtreamAccount(
        id = "http://guide.invalid|u", name = "Guide", baseUrl = "http://guide.invalid", username = "u", password = "p",
    )
    private var hangCategories = false
    private var hangChannels = false
    private val realSink = BoundedLoad.sink

    private val provider = object : IptvClient {
        override suspend fun liveCategories(acc: XtreamAccount): Result<List<XtreamCategory>> {
            if (hangCategories) awaitCancellation()
            return Result.success(listOf(XtreamCategory("1", "News")))
        }
        override suspend fun vodCategories(acc: XtreamAccount) = Result.success(emptyList<XtreamCategory>())
        override suspend fun seriesCategories(acc: XtreamAccount) = Result.success(emptyList<XtreamCategory>())
        override suspend fun liveChannels(acc: XtreamAccount, categoryId: String?): Result<List<XtreamChannel>> {
            if (hangChannels) awaitCancellation()
            return Result.success(
                listOf(XtreamChannel(streamId = 7, name = "BBC One", logo = null, epgChannelId = null, categoryId = "1", hasArchive = false, streamUrl = "http://guide.invalid/live/7.ts")),
            )
        }
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

    private fun guide(): XtreamLiveGuideViewModel {
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
    }

    @Test
    fun `a provider that never answers the category column ends on the error card, not a blank guide`() = runTest(main.dispatcher) {
        hangCategories = true
        val vm = guide()
        vm.setAccount(account)
        runCurrent()
        assertTrue("the column starts loading", vm.uiState.value.categoriesLoad is LoadStatus.Loading)

        advanceTimeBy(25_001)

        val st = vm.uiState.value
        assertEquals("the wait ended as a timeout", LoadStatus.Failed(timedOut = true), st.categoriesLoad)
        assertNotNull("the grid explains it, with Retry", st.error)
        assertTrue("Favorites / Recent stay usable offline", st.categories.any { it.special == GuideSpecial.FAVORITES })
    }

    @Test
    fun `the guide heals by itself when the catalog lands`() = runTest(main.dispatcher) {
        hangCategories = true
        val vm = guide()
        vm.setAccount(account)
        runCurrent()
        advanceTimeBy(25_001)

        hangCategories = false
        IptvImportProgress.finished(account.id)
        settle(vm) { it.channelsLoad == LoadStatus.Loaded }

        val st = vm.uiState.value
        assertEquals(LoadStatus.Loaded, st.categoriesLoad)
        assertTrue("the provider's groups arrived", st.categories.any { it.id == "1" })
        assertEquals("and All channels loaded", LoadStatus.Loaded, st.channelsLoad)
        assertEquals(listOf("BBC One"), st.channels.map { it.name })
    }

    @Test
    fun `a channel list whose provider never answers ends at its deadline`() = runTest(main.dispatcher) {
        hangChannels = true
        val vm = guide()
        vm.setAccount(account)
        runCurrent()
        assertTrue("All channels is loading", vm.uiState.value.channelsLoad is LoadStatus.Loading)

        advanceTimeBy(25_001)

        assertEquals("the spinner ended as a timeout", LoadStatus.Failed(timedOut = true), vm.uiState.value.channelsLoad)
        assertNotNull("with the error card's Retry", vm.uiState.value.error)

        hangChannels = false
        vm.retry()
        settle(vm) { it.channelsLoad == LoadStatus.Loaded }
        assertEquals("Retry loads it", LoadStatus.Loaded, vm.uiState.value.channelsLoad)
    }
}
