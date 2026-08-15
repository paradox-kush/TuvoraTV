package com.nuvio.tv.core.radar

import androidx.media3.common.PlaybackException
import com.nuvio.tv.core.iptv.CatchUpPlaybackCoordinator
import com.nuvio.tv.core.iptv.CatchUpWinnerStore
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.ui.screens.player.CatchUpPlaybackPolicy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sports Centre replays must ride the SAME catch-up machinery the guide's replays ride (WP5):
 * the coordinator session is what makes the player treat the stream as a recording — flag on,
 * zapping inert, no live-edge resume, freeze watchdog disarmed — and what walks the URL dialects
 * on transport failure instead of dying on one hardcoded timeshift shape.
 *
 * Before this lane, replayFor built a single-dialect `liveTimeshiftUrl` and the launch carried no
 * `isCatchUp`, so a replay was live in every way that matters.
 */
class RadarSportsReplayTest {

    @Test
    fun `a sports replay carries the catch-up flag`() = runTest {
        val coordinator = coordinator()
        val matcher = matcher(xtreamAccount(), XtreamItemRegistry(), coordinator)

        val replay = matcher.replayFor(match(hasArchive = true), STARTED_FIXTURE)
        assertNotNull("an archived xtream channel must offer a replay", replay)

        val session = matcher.beginReplay(replay!!)
        assertNotNull("beginning the replay must mint a catch-up session", session)
        // The player resolves catch-up behaviour by looking this id up: a held session IS the flag.
        assertNotNull(
            "the coordinator must hold the session the player will look up",
            coordinator.sessionFor(session!!.contentId),
        )
        assertEquals(
            "the programme's start must thread through to the session",
            replay.programmeStartMs, session.programme.startMs,
        )
        assertEquals(
            "the programme's end must thread through to the session",
            replay.programmeEndMs, session.programme.endMs,
        )
    }

    @Test
    fun `zapping is inert during a sports replay`() = runTest {
        val coordinator = coordinator()
        val matcher = matcher(xtreamAccount(), XtreamItemRegistry(), coordinator)
        val session = matcher.beginReplay(matcher.replayFor(match(hasArchive = true), STARTED_FIXTURE)!!)!!

        // Exactly how the player derives the flag for a launched content id.
        val isCatchUpPlayback = coordinator.sessionFor(session.contentId) != null

        assertFalse(
            "up/down must not change channel during a sports replay",
            CatchUpPlaybackPolicy.allowsChannelZap(isLive = true, isCatchUpPlayback = isCatchUpPlayback),
        )
        assertFalse(
            "backgrounding must not resume a sports replay at the live edge",
            CatchUpPlaybackPolicy.allowsLiveEdgeResume(isLive = true, isCatchUpPlayback = isCatchUpPlayback),
        )
        assertFalse(
            "the freeze watchdog must stay disarmed against a recording",
            CatchUpPlaybackPolicy.armsFreezeWatchdog(isLive = true, isCatchUpPlayback = isCatchUpPlayback),
        )
    }

    @Test
    fun `a sports replay walks dialects on transport failure`() = runTest {
        val coordinator = coordinator()
        val matcher = matcher(xtreamAccount(), XtreamItemRegistry(), coordinator)
        val first = matcher.beginReplay(matcher.replayFor(match(hasArchive = true), STARTED_FIXTURE)!!)!!

        val next = coordinator.onFailed(first.contentId, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

        assertNotNull("a transport failure must advance to the next URL shape", next)
        assertNotEquals("the walk must offer a DIFFERENT URL shape", first.url, next!!.url)
        assertEquals("the walk must keep the replay's identity", first.contentId, next.contentId)
    }

    @Test
    fun `a live sports tune stays live`() = runTest {
        val coordinator = coordinator()
        val registry = XtreamItemRegistry()
        val matcher = matcher(xtreamAccount(), registry, coordinator)
        val liveMatch = match(hasArchive = true)

        // The live half of the sheet: resolve the browse URL and register — no replay anywhere.
        val url = matcher.playbackUrlFor(liveMatch)
        assertNotNull("an xtream channel lists with a playable browse URL", url)
        matcher.ensurePlayable(liveMatch, url!!)

        assertNull(
            "a live tune must never create a catch-up session",
            coordinator.sessionFor(liveMatch.channel.contentId),
        )
        assertTrue(
            "zapping must stay available on a live sports tune",
            CatchUpPlaybackPolicy.allowsChannelZap(isLive = true, isCatchUpPlayback = false),
        )
    }

    @Test
    fun `a replay session is registered so the live route can resolve it`() = runTest {
        val registry = XtreamItemRegistry()
        val matcher = matcher(xtreamAccount(), registry, coordinator())

        val session = matcher.beginReplay(matcher.replayFor(match(hasArchive = true), STARTED_FIXTURE)!!)!!

        assertEquals(
            "the registered item must carry the session's first URL",
            session.url, registry.get(session.contentId)?.streamUrl,
        )
    }

    // --- helpers ---------------------------------------------------------------

    private fun coordinator() = CatchUpPlaybackCoordinator(
        CatchUpWinnerStore(object : CatchUpWinnerStore.Persistence {
            private var stored: Map<String, String> = emptyMap()
            override fun load(): Map<String, String> = stored
            override fun save(entries: Map<String, String>) { stored = entries.toMap() }
        })
    )

    private fun matcher(
        account: XtreamAccount,
        registry: XtreamItemRegistry,
        coordinator: CatchUpPlaybackCoordinator,
    ): RadarChannelMatcher {
        val store = mockk<XtreamAccountStore>()
        every { store.accounts } returns flowOf(listOf(account))
        return RadarChannelMatcher(
            xtreamClient = mockk<XtreamClient>(relaxed = true),
            accountStore = store,
            registry = registry,
            matchIndex = mockk(relaxed = true),
            resolver = mockk(relaxed = true),
            epgMirror = mockk(relaxed = true),
            contentDb = mockk(relaxed = true),
            clientFactory = mockk(relaxed = true),
            catchUp = coordinator,
        )
    }

    private fun xtreamAccount() = XtreamAccount(
        id = ACCOUNT_ID,
        name = "Panel",
        baseUrl = "http://panel.example:8080",
        username = "user",
        password = "pass",
    )

    private fun match(hasArchive: Boolean) = RadarChannelMatcher.ChannelMatch(
        channel = RadarChannelMatcher.CandidateChannel(
            playlistId = ACCOUNT_ID,
            playlistName = "Playlist",
            contentId = CONTENT_ID,
            name = "Sports 1",
            logo = null,
            streamId = STREAM_ID,
            streamUrl = "http://panel.example:8080/live/user/pass/$STREAM_ID.ts",
            hasArchive = hasArchive,
        ),
        programme = null,
        score = 50,
    )

    private companion object {
        const val ACCOUNT_ID = "http://panel.example:8080|user"
        const val STREAM_ID = 4242
        val CONTENT_ID = XtreamItemRegistry.liveId(ACCOUNT_ID, STREAM_ID)

        /** Kickoff safely in the past so only archive/source decide the outcome. */
        val STARTED_FIXTURE = RadarFixture(
            id = "1",
            home = "Spain",
            away = "Austria",
            ts = "2020-05-01T12:00:00",
        )
    }
}
