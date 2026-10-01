package com.nuvio.tv.ui.screens.search

import android.content.Context
import com.nuvio.tv.core.contracts.IptvSearchHit
import com.nuvio.tv.core.contracts.IptvSearchProvider
import com.nuvio.tv.core.contracts.IptvSearchRow
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SearchHistoryDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B12 regression guard: TV search lost its IPTV lane in upstream merge 7d69acade because nothing
 * failed when the call vanished. These tests fail if the next merge drops it again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelIptvLaneTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `IPTV movies row is published with zero searchable addons`() = runTest {
        val iptv = FakeIptvSearch(rowsFor = { query -> listOf(moviesRow("xtream:acc:vod:7", "$query (2024)")) })
        val viewModel = newViewModel(addons = emptyList(), iptv = iptv)

        viewModel.onEvent(SearchEvent.QueryChanged("Matrix"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val iptvRows = state.catalogRows.filter { it.addonId == "xtream" }
        assertEquals("one IPTV row is published", 1, iptvRows.size)
        val row = iptvRows.single()
        assertEquals("row keeps the IPTV Movies title", "IPTV Movies", row.catalogName)
        assertEquals("row routes as movie", "movie", row.apiType)
        assertEquals("hit id survives for click-through", listOf("xtream:acc:vod:7"), row.items.map { it.id })
        assertEquals("provider was asked for the submitted query", listOf("Matrix"), iptv.queries)
        assertFalse("search settles", state.isSearching)
        assertEquals("no 'no catalogs' error when IPTV can search", null, state.error)
    }

    @Test
    fun `IPTV rows come after the addon rows`() = runTest {
        val iptv = FakeIptvSearch(rowsFor = { listOf(moviesRow("xtream:acc:vod:7", "Matrix")) })
        val addon = searchableAddon()
        val viewModel = newViewModel(addons = listOf(addon), iptv = iptv)

        viewModel.onEvent(SearchEvent.QueryChanged("Matrix"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()

        val ids = viewModel.uiState.value.catalogRows.map { it.addonId }
        assertEquals("addon row first, IPTV row after it", listOf("addon", "xtream"), ids)
    }

    @Test
    fun `no IPTV sources keeps the no-catalog behaviour and never calls the provider`() = runTest {
        val iptv = FakeIptvSearch(hasSources = false, rowsFor = { listOf(moviesRow("xtream:acc:vod:7", "Matrix")) })
        val viewModel = newViewModel(addons = emptyList(), iptv = iptv)

        viewModel.onEvent(SearchEvent.QueryChanged("Matrix"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()

        assertTrue("no rows", viewModel.uiState.value.catalogRows.isEmpty())
        assertTrue("provider not called", iptv.queries.isEmpty())
    }

    @Test
    fun `a stale IPTV result cannot land in a newer search`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val iptv = FakeIptvSearch(
            rowsFor = { query -> listOf(moviesRow("xtream:acc:vod:${query.length}", query)) },
            gateFor = { query -> if (query == "Deep") gate else null },
        )
        val viewModel = newViewModel(addons = emptyList(), iptv = iptv)

        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        runCurrent()
        viewModel.onEvent(SearchEvent.QueryChanged("Deep Cover"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        val items = viewModel.uiState.value.catalogRows.flatMap { row -> row.items.map { it.name } }
        assertEquals("only the newer query's IPTV hit is shown", listOf("Deep Cover"), items)
    }

    @Test
    fun `changed IPTV content settings refresh the shown IPTV rows without refetching addons`() = runTest {
        val iptv = FakeIptvSearch(rowsFor = { query -> listOf(moviesRow("xtream:acc:vod:1", "$query (old)")) })
        val catalogs = EchoCatalogRepository()
        val viewModel = newViewModel(addons = listOf(searchableAddon()), iptv = iptv, catalogs = catalogs)

        viewModel.onEvent(SearchEvent.QueryChanged("Matrix"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()
        assertEquals("first run shows the old IPTV hit", listOf("Matrix (old)"), iptvItemNames(viewModel))

        // The viewer changes a playlist's content settings (e.g. switches Movies categories).
        iptv.rowsFor = { query -> listOf(moviesRow("xtream:acc:vod:2", "$query (new)")) }
        iptv.signature.value = "sig-B"
        advanceUntilIdle()

        assertEquals("IPTV rows follow the new settings", listOf("Matrix (new)"), iptvItemNames(viewModel))
        assertEquals("IPTV lane re-ran for the shown query", listOf("Matrix", "Matrix"), iptv.queries)
        assertEquals("addon catalogs were not refetched", 1, catalogs.calls)
        assertEquals("addon row kept, IPTV row after it", listOf("addon", "xtream"), viewModel.uiState.value.catalogRows.map { it.addonId })
    }

    @Test
    fun `settings that leave no IPTV hit remove the stale IPTV row`() = runTest {
        val iptv = FakeIptvSearch(rowsFor = { query -> listOf(moviesRow("xtream:acc:vod:1", query)) })
        val viewModel = newViewModel(addons = listOf(searchableAddon()), iptv = iptv)

        viewModel.onEvent(SearchEvent.QueryChanged("Matrix"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()
        iptv.rowsFor = { emptyList() }
        iptv.signature.value = "sig-B"
        advanceUntilIdle()

        assertEquals("only the addon row is left", listOf("addon"), viewModel.uiState.value.catalogRows.map { it.addonId })
    }

    @Test
    fun `enabling the first playlist reruns the shown search with the IPTV lane`() = runTest {
        val iptv = FakeIptvSearch(hasSources = false, rowsFor = { query -> listOf(moviesRow("xtream:acc:vod:1", query)) })
        val viewModel = newViewModel(addons = listOf(searchableAddon()), iptv = iptv)

        viewModel.onEvent(SearchEvent.QueryChanged("Matrix"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()
        assertTrue("no IPTV yet", iptv.queries.isEmpty())

        iptv.signature.value = "sig-A"
        advanceUntilIdle()

        assertEquals("IPTV row appears", listOf("addon", "xtream"), viewModel.uiState.value.catalogRows.map { it.addonId })
    }

    @Test
    fun `an unchanged IPTV source set never re-runs the search`() = runTest {
        val iptv = FakeIptvSearch(rowsFor = { query -> listOf(moviesRow("xtream:acc:vod:1", query)) })
        val catalogs = EchoCatalogRepository()
        val viewModel = newViewModel(addons = listOf(searchableAddon()), iptv = iptv, catalogs = catalogs)

        viewModel.onEvent(SearchEvent.QueryChanged("Matrix"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()
        iptv.signature.value = "sig-A"
        advanceUntilIdle()

        assertEquals("one IPTV query", listOf("Matrix"), iptv.queries)
        assertEquals("one addon fetch", 1, catalogs.calls)
    }

    private fun iptvItemNames(viewModel: SearchViewModel): List<String> =
        viewModel.uiState.value.catalogRows.filter { it.addonId == "xtream" }.flatMap { row -> row.items.map { it.name } }

    private fun moviesRow(id: String, name: String) = IptvSearchRow(
        catalogId = "xtream_movies",
        name = "IPTV Movies",
        rawType = "movie",
        hits = listOf(IptvSearchHit(contentId = id, name = name, poster = null, isLive = false)),
    )

    private class FakeIptvSearch(
        hasSources: Boolean = true,
        var rowsFor: (String) -> List<IptvSearchRow>,
        private val gateFor: (String) -> CompletableDeferred<Unit>? = { null },
    ) : IptvSearchProvider {
        val queries = mutableListOf<String>()
        /** The playlists' settings fingerprint; null = no enabled playlist. */
        val signature = MutableStateFlow<String?>(if (hasSources) "sig-A" else null)
        override suspend fun hasSearchableSources(): Boolean = signature.value != null
        override suspend fun search(query: String): List<IptvSearchRow> {
            queries += query
            gateFor(query)?.await()
            return rowsFor(query)
        }
        override fun sourceSignature(): Flow<String?> = signature
    }

    private fun newViewModel(
        addons: List<Addon>,
        iptv: IptvSearchProvider,
        catalogs: EchoCatalogRepository = EchoCatalogRepository(),
    ): SearchViewModel {
        val layoutPreferences = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferences.discoverLocation } returns flowOf(DiscoverLocation.OFF)
        every { layoutPreferences.posterCardWidthDp } returns flowOf(126)
        every { layoutPreferences.posterLabelsEnabled } returns flowOf(true)
        every { layoutPreferences.catalogAddonNameEnabled } returns flowOf(true)
        every { layoutPreferences.posterCardHeightDp } returns flowOf(189)
        every { layoutPreferences.posterCardCornerRadiusDp } returns flowOf(12)
        every { layoutPreferences.catalogTypeSuffixEnabled } returns flowOf(true)
        every { layoutPreferences.hideUnreleasedContent } returns flowOf(false)

        val history = mockk<SearchHistoryDataStore>(relaxed = true)
        every { history.recentSearches } returns flowOf(emptyList())

        val watchProgress = mockk<WatchProgressRepository>()
        every { watchProgress.observeWatchedMovieIds() } returns flowOf(emptySet())

        val watchedSeries = mockk<WatchedSeriesStateHolder>()
        every { watchedSeries.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())

        return SearchViewModel(
            addonRepository = FixedAddonRepository(addons),
            catalogRepository = catalogs,
            metaRepository = mockk(relaxed = true),
            discoverSelectionDataStore = mockk(relaxed = true),
            layoutPreferenceDataStore = layoutPreferences,
            searchHistoryDataStore = history,
            watchProgressRepository = watchProgress,
            watchedSeriesStateHolder = watchedSeries,
            posterOptions = mockk<PosterOptionsController>(relaxed = true),
            iptvSearchProvider = iptv,
            context = mockk<Context>(relaxed = true)
        )
    }

    private class FixedAddonRepository(private val addons: List<Addon>) : AddonRepository {
        override fun getInstalledAddons(): Flow<List<Addon>> = flowOf(addons)
        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> = error("unused")
        override suspend fun addAddon(url: String) = error("unused")
        override suspend fun removeAddon(url: String) = error("unused")
        override suspend fun setAddonOrder(urls: List<String>) = error("unused")
        override suspend fun setAddonEnabled(url: String, enabled: Boolean) = error("unused")
    }

    private class EchoCatalogRepository : CatalogRepository {
        var calls = 0
        override fun getCatalog(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean,
            posterScreen: com.nuvio.tv.core.poster.CustomPosterScreen
        ): Flow<NetworkResult<CatalogRow>> = flow {
            calls++
            val query = extraArgs.getValue("search")
            emit(
                NetworkResult.Success(
                    CatalogRow(
                        addonId = addonId,
                        addonName = addonName,
                        addonBaseUrl = addonBaseUrl,
                        catalogId = catalogId,
                        catalogName = catalogName,
                        type = ContentType.MOVIE,
                        items = listOf(
                            MetaPreview(
                                id = "addon:$query", type = ContentType.MOVIE, name = query, poster = null,
                                posterShape = PosterShape.POSTER, background = null, logo = null,
                                description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
                            )
                        )
                    )
                )
            )
        }
    }

    private fun searchableAddon(): Addon = Addon(
        id = "addon",
        name = "Addon",
        version = "1",
        description = null,
        logo = null,
        baseUrl = "https://example.test",
        catalogs = listOf(
            CatalogDescriptor(type = ContentType.MOVIE, id = "top", name = "Top", extra = listOf(CatalogExtra(name = "search")))
        ),
        types = listOf(ContentType.MOVIE),
        resources = emptyList()
    )
}

/** For search tests that do not exercise the IPTV lane. */
internal object NoIptvSearch : IptvSearchProvider {
    override suspend fun hasSearchableSources(): Boolean = false
    override suspend fun search(query: String): List<IptvSearchRow> = emptyList()
    override fun sourceSignature(): Flow<String?> = flowOf(null)
}
