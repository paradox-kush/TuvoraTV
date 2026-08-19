package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.stalker.StalkerClient
import com.nuvio.tv.data.local.XtreamAccountStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression guard for the anti-jank cold-start fix (see anti-jank F1): [rebuildFromId] rebuilds
 * DISPLAY metadata for a saved/Continue-Watching IPTV id and must NOT resolve the play URL eagerly.
 * For a Stalker vod/live id, resolveStreamUrl re-pages the whole catalog (get_ordered_list until the
 * id is found, ~23 requests) — and that fired for every Stalker CW item on every cold start. The URL
 * is resolved FRESH at play time instead (StreamRepositoryImpl.resolveXtreamPlayStreams).
 *
 * The client mock is deliberately NON-relaxed: any eager resolveStreamUrl call throws, so this test
 * is red on the old eager behaviour and green on the deferred fix.
 */
class XtreamItemRegistryRebuildTest {

    private val account = XtreamAccount(
        id = "http://portal.example.com:8080|mac",
        name = "Test",
        baseUrl = "http://portal.example.com:8080",
        username = "",
        password = "",
        sourceType = XtreamAccount.SOURCE_STALKER
    )

    private fun fixtureFactory(client: StalkerClient) =
        mockk<IptvClientFactory> { every { clientFor(account) } returns client }

    private fun fixtureStore() =
        mockk<XtreamAccountStore> { every { accounts } returns flowOf(listOf(account)) }

    @Test
    fun `rebuildFromId defers the stream URL for a vod id (no eager catalog scan)`() = runTest {
        val client = mockk<StalkerClient>() // non-relaxed: an eager resolveStreamUrl call throws
        val item = XtreamItemRegistry().rebuildFromId(
            XtreamItemRegistry.vodId(account.id, 20642), fixtureStore(), fixtureFactory(client)
        )
        assertNotNull("vod rebuild must succeed without resolving the URL", item)
        assertEquals("stream URL is deferred to play time", "", item!!.streamUrl)
        assertEquals("streamId preserved", 20642, item.streamId)
        assertEquals("kind preserved", XtreamKind.VOD, item.kind)
        coVerify(exactly = 0) { client.resolveStreamUrl(any(), any(), any(), any()) }
    }

    @Test
    fun `rebuildFromId defers the stream URL for a live id (no eager resolve)`() = runTest {
        val client = mockk<StalkerClient>()
        val item = XtreamItemRegistry().rebuildFromId(
            XtreamItemRegistry.liveId(account.id, 7), fixtureStore(), fixtureFactory(client)
        )
        assertNotNull("live rebuild must succeed without resolving the URL", item)
        assertEquals("stream URL is deferred to play time", "", item!!.streamUrl)
        assertEquals("kind preserved", XtreamKind.LIVE, item.kind)
        coVerify(exactly = 0) { client.resolveStreamUrl(any(), any(), any(), any()) }
    }
}
