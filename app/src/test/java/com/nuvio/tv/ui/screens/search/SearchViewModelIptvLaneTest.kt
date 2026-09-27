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

    private fun moviesRow(id: String, name: String) = IptvSearchRow(
        catalogId = "xtream_movies",
        name = "IPTV Movies",
        rawType = "movie",
        hits = listOf(IptvSearchHit(contentId = id, name = name, poster = null, isLive = false)),
    )

    private class FakeIptvSearch(
        private val hasSources: Boolean = true,
        private val rowsFor: (String) -> List<IptvSearchRow>,
        private val gateFor: (String) -> CompletableDeferred<Unit>? = { null },
    ) : IptvSearchProvider {
        val queries = mutableListOf<String>()
        override suspend fun hasSearchableSources(): Boolean = hasSources
        override suspend fun search(query: String): List<IptvSearchRow> {
            queries += query
            gateFor(query)?.await()
            return rowsFor(query)
        }
    }

    private fun newViewModel(addons: List<Addon>, iptv: IptvSearchProvider): SearchViewModel {
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
            catalogRepository = EchoCatalogRepository(),
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
}
