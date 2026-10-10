package com.nuvio.tv.ui.screens.iptv

import android.content.Context
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
import com.nuvio.tv.core.iptv.XtreamMovie
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.core.iptv.XtreamSeriesDetail
import com.nuvio.tv.core.iptv.XtreamSeriesItem
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.RememberedHubSelection
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.XtreamHubSelectionStore
import com.nuvio.tv.data.local.XtreamLiveStore
import com.nuvio.tv.domain.repository.LibraryRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Regression ("the IPTV page spins forever", TV twin of NuvioMobile's XtreamHubBoundedLoadTest): the hub's
 * category list waited on a provider that never answered (or trickled bytes so no socket timeout fired)
 * with no deadline, and a row whose fetch failed was swallowed into an empty list
 * (`getOrDefault(emptyList())`), so the row vanished for the session. Every load now goes through
 * BoundedLoad: it ends at its deadline, and a failure is never "empty".
 *
 * The real [XtreamHubViewModel] over a fake provider; deadlines are the real ones on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XtreamHubBoundedLoadTest {
    @get:Rule val main = MainDispatcherRule()

    private val account = XtreamAccount(
        id = "http://stall.invalid|u", name = "Stall", baseUrl = "http://stall.invalid", username = "u", password = "p",
    )

    private var hangCategories = false
    private var failRowTimes = 0
    private var hangRow = false
    private var rowAsks = 0
    private val realSink = BoundedLoad.sink

    private val provider = object : IptvClient {
        override suspend fun liveCategories(acc: XtreamAccount) = Result.success(emptyList<XtreamCategory>())
        override suspend fun vodCategories(acc: XtreamAccount): Result<List<XtreamCategory>> {
            if (hangCategories) awaitCancellation()
            return Result.success(listOf(XtreamCategory("1", "Action")))
        }
        override suspend fun seriesCategories(acc: XtreamAccount) = Result.success(emptyList<XtreamCategory>())
        override suspend fun liveChannels(acc: XtreamAccount, categoryId: String?) = Result.success(emptyList<XtreamChannel>())
        override suspend fun vodMovies(acc: XtreamAccount, categoryId: String?): Result<List<XtreamMovie>> {
            rowAsks++
            if (hangRow) awaitCancellation()
            if (rowAsks <= failRowTimes) return Result.failure(IOException("HTTP 503"))
            return Result.success(
                listOf(XtreamMovie(streamId = 7, name = "Heat", poster = null, categoryId = "1", rating = null, streamUrl = "http://stall.invalid/movie/7.mp4", tmdb = null)),
            )
        }
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

    private fun hub(): XtreamHubViewModel {
        val store = mockk<XtreamAccountStore> { every { accounts } returns MutableStateFlow(listOf(account)) }
        val factory = mockk<IptvClientFactory> { every { clientFor(any()) } returns provider }
        val selection = mockk<XtreamHubSelectionStore>(relaxed = true) {
            coEvery { read() } returns RememberedHubSelection(account.id, XtreamSection.MOVIES.name)
        }
        val liveStore = mockk<XtreamLiveStore>(relaxed = true) { every { recents } returns flowOf(emptyList()) }
        val library = mockk<LibraryRepository>(relaxed = true) { every { libraryItems } returns flowOf(emptyList()) }
        val matchIndex = mockk<com.nuvio.tv.core.iptv.match.XtreamMatchIndex>(relaxed = true) {
            coEvery { categoriesFor(any(), any()) } returns emptyList()   // no catalog index yet: the network path
            coEvery { builtAt(any(), any()) } returns null
        }
        val enricher = mockk<com.nuvio.tv.core.iptv.match.PosterEnricher>(relaxed = true) {
            every { updates } returns MutableSharedFlow()
        }
        val failover = mockk<com.nuvio.tv.core.iptv.PlaylistServerFailover>(relaxed = true) {
            every { lastFailedServerUrl(any()) } returns null
        }
        val layout = mockk<LayoutPreferenceDataStore> {
            every { posterCardWidthDp } returns flowOf(126)
            every { posterCardHeightDp } returns flowOf(189)
            every { posterCardCornerRadiusDp } returns flowOf(12)
            every { posterLabelsEnabled } returns flowOf(true)
        }
        val context = mockk<Context>(relaxed = true) { every { getString(any()) } returns "unreachable" }
        return XtreamHubViewModel(
            store = store,
            clientFactory = factory,
            registry = XtreamItemRegistry(),
            liveStore = liveStore,
            selectionStore = selection,
            libraryRepository = library,
            fileStore = mockk(relaxed = true),
            contentDb = mockk(relaxed = true),
            matchIndex = matchIndex,
            posterEnricher = enricher,
            failover = failover,
            layoutPreferenceDataStore = layout,
            context = context,
        )
    }

    @Test
    fun `a provider that never answers the category list ends on the error card at the page deadline`() = runTest(main.dispatcher) {
        hangCategories = true
        val vm = hub()
        runCurrent()
        assertTrue("the page starts loading", vm.uiState.value.categoriesLoad is LoadStatus.Loading)

        advanceTimeBy(25_001)

        assertEquals("the skeleton ended as a timeout", LoadStatus.Failed(timedOut = true), vm.uiState.value.categoriesLoad)
        assertNotNull("the error card has something to say, with Retry", vm.uiState.value.error)
    }

    @Test
    fun `a row whose fetch fails stays on the page instead of vanishing as empty`() = runTest(main.dispatcher) {
        failRowTimes = Int.MAX_VALUE
        val vm = hub()
        runCurrent()
        vm.loadCategory("1")
        runCurrent()

        val st = vm.uiState.value
        assertEquals("the row is Failed (its Retry tile shows)", LoadStatus.Failed(timedOut = false), st.rowLoads["1"])
        assertFalse("a failed row is not 'loaded and empty' (the page hides those)", st.itemsByCategory.containsKey("1"))
        vm.loadCategory("1")   // composing it again (a scroll back) must not ask again
        vm.prefetchCategory("1")
        runCurrent()
        assertEquals("a failed row is re-asked only by Retry or a landed catalog", 1, rowAsks)
    }

    @Test
    fun `a row whose provider never answers ends at the row deadline`() = runTest(main.dispatcher) {
        hangRow = true
        val vm = hub()
        runCurrent()
        vm.loadCategory("1")
        runCurrent()
        assertTrue("the row is loading", vm.uiState.value.rowLoads["1"] is LoadStatus.Loading)

        advanceTimeBy(20_001)

        assertEquals("the row ended as a timeout", LoadStatus.Failed(timedOut = true), vm.uiState.value.rowLoads["1"])
    }

    @Test
    fun `the Retry tile asks a failed row again`() = runTest(main.dispatcher) {
        failRowTimes = 1
        val vm = hub()
        runCurrent()
        vm.loadCategory("1")
        runCurrent()
        assertEquals(LoadStatus.Failed(timedOut = false), vm.uiState.value.rowLoads["1"])

        vm.retryCategory("1")
        runCurrent()

        assertEquals("Retry asked once more", 2, rowAsks)
        assertEquals("and the row loaded", 1, vm.uiState.value.itemsByCategory["1"]?.size)
        assertEquals(LoadStatus.Loaded, vm.uiState.value.rowLoads["1"])
    }

    @Test
    fun `a failed row loads once its catalog lands`() = runTest(main.dispatcher) {
        failRowTimes = 1
        val vm = hub()
        runCurrent()
        vm.loadCategory("1")
        runCurrent()
        assertEquals(LoadStatus.Failed(timedOut = false), vm.uiState.value.rowLoads["1"])

        IptvImportProgress.finished(account.id)
        runCurrent()

        assertEquals("the landed catalog re-asked the failed row once", 2, rowAsks)
        assertEquals("the row healed without the viewer doing anything", 1, vm.uiState.value.itemsByCategory["1"]?.size)
    }

    @Test
    fun `a page that gave up re-shows when its catalog lands`() = runTest(main.dispatcher) {
        hangCategories = true
        val vm = hub()
        runCurrent()
        advanceTimeBy(25_001)
        assertTrue(vm.uiState.value.categoriesLoad is LoadStatus.Failed)

        hangCategories = false
        IptvImportProgress.finished(account.id)
        runCurrent()

        assertEquals("the page came back on its own", LoadStatus.Loaded, vm.uiState.value.categoriesLoad)
        assertEquals(listOf("1"), vm.uiState.value.categories.map { it.id })
        assertEquals("and the error card went away", null, vm.uiState.value.error)
    }
}
