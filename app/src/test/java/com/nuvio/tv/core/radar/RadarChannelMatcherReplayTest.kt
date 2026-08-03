package com.nuvio.tv.core.radar

import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.data.local.XtreamAccountStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Replay is a timeshift URL built off the Xtream `player_api` scheme — Stalker and M3U sources
 * have no equivalent, so offering it for them fabricates a URL that can never play. Guarding it
 * inside [RadarChannelMatcher.replayFor] also hides the affordance: the sheet only draws a Replay
 * button for contentIds present in the replays map.
 */
class RadarChannelMatcherReplayTest {

    @Test
    fun `replay is refused for a stalker channel that advertises an archive`() = runTest {
        val registry = XtreamItemRegistry()
        val matcher = matcher(stalkerAccount(), registry)
        val match = match(hasArchive = true)

        assertNull(matcher.replayFor(match, STARTED_FIXTURE))
        // …and nothing bogus was registered for the play route to pick up.
        assertNull(registry.get(expectedReplayId()))
    }

    @Test
    fun `replay is offered for an xtream channel with an archive`() = runTest {
        val registry = XtreamItemRegistry()
        val matcher = matcher(xtreamAccount(), registry)

        val replay = matcher.replayFor(match(hasArchive = true), STARTED_FIXTURE)

        assertNotNull(replay)
        assertEquals(expectedReplayId(), replay!!.first)
        assertEquals(TIMESHIFT_URL, replay.second)
        assertEquals(TIMESHIFT_URL, registry.get(expectedReplayId())?.streamUrl)
    }

    @Test
    fun `replay is refused for an xtream channel without an archive`() = runTest {
        val matcher = matcher(xtreamAccount(), XtreamItemRegistry())

        assertNull(matcher.replayFor(match(hasArchive = false), STARTED_FIXTURE))
    }

    // --- helpers ---------------------------------------------------------------

    private fun matcher(account: XtreamAccount, registry: XtreamItemRegistry): RadarChannelMatcher {
        val store = mockk<XtreamAccountStore>()
        every { store.accounts } returns flowOf(listOf(account))
        val client = mockk<XtreamClient>()
        every { client.liveTimeshiftUrl(any(), any(), any(), any()) } returns TIMESHIFT_URL
        return RadarChannelMatcher(
            xtreamClient = client,
            accountStore = store,
            registry = registry,
            matchIndex = mockk(relaxed = true),
            resolver = mockk(relaxed = true),
            epgMirror = mockk(relaxed = true),
            clientFactory = mockk(relaxed = true),
        )
    }

    private fun xtreamAccount() = XtreamAccount(
        id = ACCOUNT_ID,
        name = "Panel",
        baseUrl = "http://panel.example:8080",
        username = "user",
        password = "pass",
    )

    private fun stalkerAccount() = XtreamAccount(
        id = ACCOUNT_ID,
        name = "Portal",
        baseUrl = "",
        username = "",
        password = "",
        sourceType = XtreamAccount.SOURCE_STALKER,
        portalUrl = "http://portal.example",
        macAddress = "00:1A:79:00:00:01",
    )

    private fun match(hasArchive: Boolean) = RadarChannelMatcher.ChannelMatch(
        channel = RadarChannelMatcher.CandidateChannel(
            playlistId = ACCOUNT_ID,
            playlistName = "Playlist",
            contentId = CONTENT_ID,
            name = "Sports 1",
            logo = null,
            streamId = STREAM_ID,
            streamUrl = "http://portal.example/live/$STREAM_ID",
            hasArchive = hasArchive,
        ),
        programme = null,
        score = 50,
    )

    /** Mirrors replayFor's id: no programme, so the window opens 15 minutes before kickoff. */
    private fun expectedReplayId(): String {
        val replayStart = STARTED_FIXTURE.startEpochMs!! - 15 * 60 * 1000L
        return "${CONTENT_ID}r${replayStart / 60_000L}"
    }

    private companion object {
        const val ACCOUNT_ID = "http://panel.example:8080|user"
        const val STREAM_ID = 4242
        val CONTENT_ID = XtreamItemRegistry.liveId(ACCOUNT_ID, STREAM_ID)
        const val TIMESHIFT_URL = "http://panel.example:8080/timeshift/user/pass/165/2020-05-01:11-45/4242.ts"

        /** Kickoff safely in the past, so only the source type decides the outcome. */
        val STARTED_FIXTURE = RadarFixture(
            id = "1",
            home = "Spain",
            away = "Austria",
            ts = "2020-05-01T12:00:00",
        )
    }
}
