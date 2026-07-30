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
}
