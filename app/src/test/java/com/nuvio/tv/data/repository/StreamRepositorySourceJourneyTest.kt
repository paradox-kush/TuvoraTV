package com.nuvio.tv.data.repository

import com.nuvio.tv.core.contracts.StreamSourceRegistry
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.LocalDebridAvailabilityService
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.mediaserver.ServerStreams
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.repository.AddonDiscoveryState
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class StreamRepositorySourceJourneyTest {
    private val discovery = MutableStateFlow(AddonDiscoveryState())
    private val api = mockk<AddonApi>()
    private val addons = mockk<AddonRepository> {
        every { getAddonDiscoveryState() } returns discovery
    }
    private val tmdb = mockk<com.nuvio.tv.core.tmdb.TmdbService>(relaxed = true)

    @Before fun setup() {
        StreamSourceRegistry.resetForTest()
        coEvery { api.getMeta(any()) } returns Response.success(com.nuvio.tv.data.remote.dto.MetaResponseDto())
    }
    @After fun cleanup() { StreamSourceRegistry.resetForTest() }

    private fun addon(name: String) = Addon(name, name, version = "1", description = null, logo = null,
        baseUrl = "https://$name.fixture.test", catalogs = emptyList(), types = emptyList(),
        resources = listOf(AddonResource("stream", listOf("movie"), listOf("tt"))))

    private fun repository(): StreamRepositoryImpl {
        val badges = mockk<DebridStreamPresentation> {
            every { apply(any(), any(), any(), any()) } answers { firstArg() }
        }
        val cacheChecks = mockk<LocalDebridAvailabilityService> {
            coEvery { markChecking(any()) } answers { firstArg() }
            coEvery { annotateCachedAvailability(any()) } answers { firstArg() }
        }
        val context = mockk<android.content.Context>(relaxed = true)
        every { context.getString(any(), *anyVararg()) } answers {
            "fixture-resource-${firstArg<Int>()}: " + args.drop(1).joinToString { (it as? Array<*>)?.contentToString() ?: it.toString() }
        }
        return StreamRepositoryImpl(
            context = context, api = api, addonRepository = addons,
            pluginManager = mockk(relaxed = true) { every { pluginsEnabled } returns flowOf(false) },
            profileManager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) },
            debridSettingsDataStore = mockk<DebridSettingsDataStore> { every { settings } returns flowOf(DebridSettings()) },
            tmdbService = tmdb, debridStreamPresentation = badges, localDebridAvailabilityService = cacheChecks,
            xtreamRegistry = XtreamItemRegistry(), iptvClientFactory = mockk(relaxed = true),
            xtreamAccountStore = mockk<XtreamAccountStore> { every { accounts } returns flowOf(emptyList()) },
            xtreamStreamSource = mockk(relaxed = true),
            serverStreams = mockk<ServerStreams>(relaxed = true) {
                every { isNativeRequest(any()) } returns false
                every { sources(any(), any(), any(), any(), any()) } returns emptyList()
            },
            usenetSourceSettings = mockk<com.nuvio.tv.core.usenet.UsenetSourceSettings> { every { read(any()) } returns com.nuvio.tv.core.usenet.UsenetSourceConfiguration() }, builtInUsenetService = mockk(relaxed = true)
        )
    }

    @Test fun coldDiscoveryDoesNotCommitAnInitialEmptyStateAsNoSources() = runBlocking {
        val ready = addon("ready")
        coEvery { api.getStreams(any()) } returns Response.success(StreamResponseDto(listOf(StreamDto(name = "edition", url = "https://video.fixture.test/a"))))
        val seen = MutableStateFlow<List<NetworkResult<List<AddonStreams>>>>(emptyList())
        val job = async { repository().getStreamsFromAllAddons("movie", "tt1", null, null, false).collect { seen.value += it } }
        withTimeout(5_000) { seen.first { it.isNotEmpty() } }
        assertTrue(seen.value.all { it is NetworkResult.Loading })
        discovery.value = AddonDiscoveryState(addons = listOf(ready), initialized = true)
        withTimeout(5_000) { job.await() }
        assertTrue("Outcomes: ${seen.value}", seen.value.any { it is NetworkResult.Success && it.data.single().streams.size == 1 })
        coVerify(exactly = 0) { tmdb.ensureTmdbId(any(), any(), any()) }
    }

    @Test fun readyProviderReturnsWhileAnotherManifestIsStillPendingThenBothFinish() = runBlocking {
        val fast = addon("fast")
        val slow = addon("slow")
        discovery.value = AddonDiscoveryState(listOf(fast), pendingUrls = setOf(slow.baseUrl), initialized = true)
        coEvery { api.getStreams(any()) } answers {
            Response.success(StreamResponseDto(listOf(StreamDto(name = "edition", url = firstArg<String>() + "/video"))))
        }
        val seen = MutableStateFlow<List<AddonStreams>>(emptyList())
        val job = async { repository().getStreamsFromAllAddons("movie", "tt1", null, null, false).collect {
            if (it is NetworkResult.Success) seen.value = it.data
        } }
        withTimeout(5_000) { seen.first { it.any { group -> group.addonName == "fast" } } }
        assertTrue(!job.isCompleted)
        discovery.value = AddonDiscoveryState(listOf(fast, slow), initialized = true)
        withTimeout(5_000) { job.await() }
        assertEquals(setOf("fast", "slow"), seen.value.map { it.addonName }.toSet())
        coVerify(exactly = 2) { api.getStreams(any()) }
    }
    @Test fun successfulEmptyResponsesAreNoMatchesRatherThanFetchFailures() = runBlocking {
        discovery.value = AddonDiscoveryState(listOf(addon("empty")), initialized = true)
        coEvery { api.getStreams(any()) } returns Response.success(StreamResponseDto(emptyList()))
        val results = mutableListOf<NetworkResult<List<AddonStreams>>>()
        withTimeout(5_000) { repository().getStreamsFromAllAddons("movie", "tt1", null, null, false).collect { results += it } }
        assertTrue("Outcomes: $results", results.last() is NetworkResult.Success)
        assertTrue((results.last() as NetworkResult.Success).data.isEmpty())
    }

    @Test fun aFailedProviderAlongsideAnEmptyProviderDoesNotClaimACompleteNoMatch() = runBlocking {
        discovery.value = AddonDiscoveryState(listOf(addon("empty"), addon("failed")), initialized = true)
        coEvery { api.getStreams(match { it.contains("empty.fixture") }) } returns Response.success(StreamResponseDto(emptyList()))
        coEvery { api.getStreams(match { it.contains("failed.fixture") }) } throws java.io.IOException("fixture unreachable")
        val results = mutableListOf<NetworkResult<List<AddonStreams>>>()
        withTimeout(5_000) { repository().getStreamsFromAllAddons("movie", "tt1", null, null, false).collect { results += it } }
        assertTrue("Outcomes: $results", results.last() is NetworkResult.Error)
    }

}
