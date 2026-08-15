package com.nuvio.tv.data.repository

import com.nuvio.tv.core.iptv.IptvClient
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.stalker.StalkerClient
import com.nuvio.tv.data.local.XtreamAccountStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [StreamRepositoryImpl.refreshIptvStreamUrl] is the player's mid-playback recovery for expired
 * Stalker links (401 → mint a fresh create_link). It must route every id kind to the right
 * resolver — including Stalker's colon-carrying episode ids — and fail soft (null) otherwise.
 */
class StreamRepositoryRefreshIptvTest {

    private val account = XtreamAccount(
        id = "http://portal.example.com:8080|mac",
        name = "Test",
        baseUrl = "http://portal.example.com:8080",
        username = "",
        password = "",
        sourceType = XtreamAccount.SOURCE_STALKER
    )

    private lateinit var client: StalkerClient
    private lateinit var repository: StreamRepositoryImpl

    @Before
    fun setUp() {
        client = mockk()
        val factory = mockk<IptvClientFactory> { every { clientFor(account) } returns client }
        val accountStore = mockk<XtreamAccountStore> { every { accounts } returns flowOf(listOf(account)) }
        repository = StreamRepositoryImpl(
            context = mockk(relaxed = true),
            api = mockk(relaxed = true),
            addonRepository = mockk(relaxed = true),
            pluginManager = mockk(relaxed = true),
            tmdbService = mockk(relaxed = true),
            debridStreamPresentation = mockk(relaxed = true),
            localDebridAvailabilityService = mockk(relaxed = true),
            xtreamRegistry = XtreamItemRegistry(),
            iptvClientFactory = factory,
            xtreamAccountStore = accountStore,
            xtreamStreamSource = mockk(relaxed = true)
        )
    }

    @Test
    fun `live id resolves fresh via the live kind`() = runTest {
        coEvery { client.resolveStreamUrl(account, "live", 7) } returns "http://fresh/live.ts"
        val url = repository.refreshIptvStreamUrl(XtreamItemRegistry.liveId(account.id, 7))
        assertEquals("http://fresh/live.ts", url)
    }

    @Test
    fun `vod id resolves via the movie kind`() = runTest {
        coEvery { client.resolveStreamUrl(account, "movie", 99) } returns "http://fresh/movie.mkv"
        val url = repository.refreshIptvStreamUrl(XtreamItemRegistry.vodId(account.id, 99))
        assertEquals("http://fresh/movie.mkv", url)
    }

    @Test
    fun `stalker episode id routes seriesId season epNum to the episode resolver`() = runTest {
        coEvery { client.resolveEpisodeUrl(account, 12, 3, 4) } returns "http://fresh/ep.ts"
        val url = repository.refreshIptvStreamUrl(XtreamItemRegistry.episodeId(account.id, "12:3:4"))
        assertEquals("http://fresh/ep.ts", url)
        coVerify(exactly = 1) { client.resolveEpisodeUrl(account, 12, 3, 4) }
    }

    @Test
    fun `legacy two-part episode id resolves with null season`() = runTest {
        coEvery { client.resolveEpisodeUrl(account, 12, null, 4) } returns "http://fresh/ep.ts"
        val url = repository.refreshIptvStreamUrl(XtreamItemRegistry.episodeId(account.id, "12:4"))
        assertEquals("http://fresh/ep.ts", url)
    }

    @Test
    fun `episode id on a non-stalker client returns null`() = runTest {
        val xtreamOnly = mockk<IptvClient>()
        val factory = mockk<IptvClientFactory> { every { clientFor(account) } returns xtreamOnly }
        val accountStore = mockk<XtreamAccountStore> { every { accounts } returns flowOf(listOf(account)) }
        val repo = StreamRepositoryImpl(
            context = mockk(relaxed = true),
            api = mockk(relaxed = true),
            addonRepository = mockk(relaxed = true),
            pluginManager = mockk(relaxed = true),
            tmdbService = mockk(relaxed = true),
            debridStreamPresentation = mockk(relaxed = true),
            localDebridAvailabilityService = mockk(relaxed = true),
            xtreamRegistry = XtreamItemRegistry(),
            iptvClientFactory = factory,
            xtreamAccountStore = accountStore,
            xtreamStreamSource = mockk(relaxed = true)
        )
        assertNull(repo.refreshIptvStreamUrl(XtreamItemRegistry.episodeId(account.id, "12:3:4")))
    }

    @Test
    fun `non-xtream id and unknown account return null`() = runTest {
        assertNull(repository.refreshIptvStreamUrl("tt0111161"))
        assertNull(repository.refreshIptvStreamUrl(XtreamItemRegistry.liveId("someone-else", 7)))
    }

    // --- TMDB-matched lane -----------------------------------------------------------------

    private fun matchedStream(label: String, url: String) = com.nuvio.tv.domain.model.Stream(
        name = label, title = null, description = null, url = url, ytId = null,
        infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
        addonName = account.name, addonLogo = null
    )

    @Test
    fun `matched refresh re-runs the matcher and prefers the same edition label`() = runTest {
        val source = mockk<com.nuvio.tv.core.iptv.match.XtreamStreamSource>()
        coEvery { source.streamsFor(account, "movie", "tmdb:603", null, null) } returns listOf(
            matchedStream("Movie 4K", "http://fresh/4k.ts"),
            matchedStream("Movie HD", "http://fresh/hd.ts"),
        )
        // Production routes every candidate through the deferred-mint step; a non-deferred URL
        // passes through unchanged (the real resolveDeferredUrl's contract).
        coEvery { source.resolveDeferredUrl(any(), any(), any()) } answers { firstArg() }
        val repo = repositoryWith(source)
        val url = repo.refreshMatchedIptvStreamUrl(
            type = "movie", videoId = "tmdb:603", season = null, episode = null,
            addonName = account.name, streamName = "Movie HD", failedUrl = "http://dead/old.ts"
        )
        assertEquals("http://fresh/hd.ts", url)
    }

    /**
     * Matched Stalker candidates are DEFERRED ("stalker-deferred:…") — the listing never mints,
     * and the recovery swaps its result straight into the engine. The refresh must therefore
     * mint the REAL link itself, and mint it FRESH (forceFresh=true): with static-cmd playback a
     * plain resolve could rebuild the very URL that just died. Regression for the recovery
     * handing the player an unplayable deferred URL.
     */
    @Test
    fun `matched refresh mints a deferred stalker candidate before returning it`() = runTest {
        val source = mockk<com.nuvio.tv.core.iptv.match.XtreamStreamSource>()
        val deferred = "stalker-deferred:${account.id}|movie|42|Movie HD"
        coEvery { source.streamsFor(account, "movie", "tmdb:603", null, null) } returns listOf(
            matchedStream("Movie HD", deferred),
        )
        coEvery {
            source.resolveDeferredUrl(deferred, listOf(account), forceFresh = true)
        } returns "http://fresh/minted.ts?play_token=x"
        val repo = repositoryWith(source)
        val url = repo.refreshMatchedIptvStreamUrl(
            type = "movie", videoId = "tmdb:603", season = null, episode = null,
            addonName = account.name, streamName = "Movie HD", failedUrl = "http://dead/old.ts"
        )
        assertEquals("http://fresh/minted.ts?play_token=x", url)
        coVerify(exactly = 1) { source.resolveDeferredUrl(deferred, listOf(account), forceFresh = true) }
    }

    @Test
    fun `matched refresh bails without network for a foreign addon label`() = runTest {
        val source = mockk<com.nuvio.tv.core.iptv.match.XtreamStreamSource>()
        val repo = repositoryWith(source)
        assertNull(
            repo.refreshMatchedIptvStreamUrl(
                type = "movie", videoId = "tmdb:603", season = null, episode = null,
                addonName = "Torrentio", streamName = "Movie HD", failedUrl = "http://dead/old.ts"
            )
        )
        coVerify(exactly = 0) { source.streamsFor(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `matched refresh returns null when the rebuilt URL is the same dead one`() = runTest {
        val source = mockk<com.nuvio.tv.core.iptv.match.XtreamStreamSource>()
        coEvery { source.streamsFor(account, "movie", "tmdb:603", null, null) } returns listOf(
            matchedStream("Movie HD", "http://dead/old.ts"),
        )
        val repo = repositoryWith(source)
        assertNull(
            repo.refreshMatchedIptvStreamUrl(
                type = "movie", videoId = "tmdb:603", season = null, episode = null,
                addonName = account.name, streamName = "Movie HD", failedUrl = "http://dead/old.ts"
            )
        )
    }

    private fun repositoryWith(source: com.nuvio.tv.core.iptv.match.XtreamStreamSource): StreamRepositoryImpl {
        val factory = mockk<IptvClientFactory> { every { clientFor(account) } returns client }
        val accountStore = mockk<XtreamAccountStore> { every { accounts } returns flowOf(listOf(account)) }
        return StreamRepositoryImpl(
            context = mockk(relaxed = true),
            api = mockk(relaxed = true),
            addonRepository = mockk(relaxed = true),
            pluginManager = mockk(relaxed = true),
            tmdbService = mockk(relaxed = true),
            debridStreamPresentation = mockk(relaxed = true),
            localDebridAvailabilityService = mockk(relaxed = true),
            xtreamRegistry = XtreamItemRegistry(),
            iptvClientFactory = factory,
            xtreamAccountStore = accountStore,
            xtreamStreamSource = source
        )
    }
}
