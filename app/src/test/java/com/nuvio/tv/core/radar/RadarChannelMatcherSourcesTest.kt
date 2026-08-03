package com.nuvio.tv.core.radar

import com.nuvio.tv.core.iptv.IptvClient
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamChannel
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.data.local.XtreamAccountStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sports Centre matches every playlist type, not just Xtream panels: assembly routes through
 * [IptvClientFactory], so a Stalker portal's lineup is a candidate like any other. Stalker lists
 * channels with a BLANK url (the portal mints a single-use link per play), so playback resolves
 * a fresh link at play time instead of reading the browse row.
 */
class RadarChannelMatcherSourcesTest {

    @Test
    fun `channels from xtream m3u and stalker playlists all match`() = runTest {
        val matcher = matcher(
            xtreamAccount() to clientWith(channel(1, "Spain Austria Sports", XTREAM_URL)),
            m3uAccount() to clientWith(channel(2, "Spain v Austria Stream", M3U_URL)),
            stalkerAccount() to clientWith(channel(3, "Austria Spain Live", "")),
        )

        val matched = matcher.match(FIXTURE, league = null)

        assertEquals(
            setOf(XTREAM_ID, M3U_ID, STALKER_ID),
            matched.map { it.channel.playlistId }.toSet(),
        )
    }

    @Test
    fun `stalker playback resolves a fresh link instead of the blank browse url`() = runTest {
        val client = clientWith(channel(3, "Austria Spain Live", ""))
        coEvery { client.resolveStreamUrl(any(), "live", 3) } returns CREATE_LINK_URL
        val matcher = matcher(stalkerAccount() to client)

        val match = matcher.match(FIXTURE, league = null).single()

        assertEquals("", match.channel.streamUrl)
        assertEquals(CREATE_LINK_URL, matcher.playbackUrlFor(match))
    }

    @Test
    fun `xtream playback uses the browse url without asking the panel again`() = runTest {
        val client = clientWith(channel(1, "Spain Austria Sports", XTREAM_URL))
        coEvery { client.resolveStreamUrl(any(), any(), any()) } throws
            AssertionError("must not re-resolve a URL the source already listed")
        val matcher = matcher(xtreamAccount() to client)

        val match = matcher.match(FIXTURE, league = null).single()

        assertEquals(XTREAM_URL, matcher.playbackUrlFor(match))
    }

    @Test
    fun `stalker playback is null when the portal cannot mint a link`() = runTest {
        val client = clientWith(channel(3, "Austria Spain Live", ""))
        coEvery { client.resolveStreamUrl(any(), any(), any()) } returns null
        val matcher = matcher(stalkerAccount() to client)

        val match = matcher.match(FIXTURE, league = null).single()

        assertNull(matcher.playbackUrlFor(match))
    }

    @Test
    fun `a disabled playlist contributes nothing`() = runTest {
        val matcher = matcher(
            stalkerAccount().copy(enabled = false) to clientWith(channel(3, "Austria Spain Live", "")),
        )

        assertEquals(emptyList<RadarChannelMatcher.ChannelMatch>(), matcher.match(FIXTURE, league = null))
    }

    // --- helpers ---------------------------------------------------------------

    private fun matcher(vararg playlists: Pair<XtreamAccount, IptvClient>): RadarChannelMatcher {
        val store = mockk<XtreamAccountStore>()
        every { store.accounts } returns flowOf(playlists.map { it.first })
        val factory = mockk<IptvClientFactory>()
        playlists.forEach { (account, client) -> every { factory.clientFor(account) } returns client }
        return RadarChannelMatcher(
            xtreamClient = mockk(relaxed = true),
            accountStore = store,
            registry = XtreamItemRegistry(),
            matchIndex = mockk(relaxed = true),
            resolver = mockk(relaxed = true),
            epgMirror = mockk(relaxed = true),
            clientFactory = factory,
        )
    }

    private fun clientWith(vararg channels: XtreamChannel): IptvClient = mockk<IptvClient>().apply {
        coEvery { liveChannels(any(), any()) } returns Result.success(channels.toList())
        coEvery { shortEpg(any(), any(), any()) } returns Result.success(emptyList())
    }

    private fun channel(streamId: Int, name: String, url: String) = XtreamChannel(
        streamId = streamId,
        name = name,
        logo = null,
        epgChannelId = null,
        categoryId = null,
        hasArchive = false,
        streamUrl = url,
    )

    private fun xtreamAccount() = XtreamAccount(
        id = XTREAM_ID, name = "Panel", baseUrl = "http://panel.example:8080",
        username = "user", password = "pass",
    )

    private fun m3uAccount() = XtreamAccount(
        id = M3U_ID, name = "Playlist", baseUrl = "", username = "", password = "",
        sourceType = XtreamAccount.SOURCE_URL,
    )

    private fun stalkerAccount() = XtreamAccount(
        id = STALKER_ID, name = "Portal", baseUrl = "", username = "", password = "",
        sourceType = XtreamAccount.SOURCE_STALKER,
        portalUrl = "http://portal.example", macAddress = "00:1A:79:00:00:01",
    )

    private companion object {
        const val XTREAM_ID = "http://panel.example:8080|user"
        const val M3U_ID = "m3u|playlist"
        const val STALKER_ID = "stalker|portal"
        const val XTREAM_URL = "http://panel.example:8080/live/user/pass/1.ts"
        const val M3U_URL = "http://cdn.example/playlist/2.m3u8"
        const val CREATE_LINK_URL = "http://portal.example/play/live.php?token=single-use"

        /** Kickoff in the past, so the EPG stage runs like it would for a real opened fixture. */
        val FIXTURE = RadarFixture(
            id = "1",
            home = "Spain",
            away = "Austria",
            ts = "2020-05-01T12:00:00",
        )
    }
}
