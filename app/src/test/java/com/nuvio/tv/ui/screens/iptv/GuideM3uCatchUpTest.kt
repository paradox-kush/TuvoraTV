package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.M3uCatchUpInfo
import com.nuvio.tv.core.iptv.M3uCatchUpType
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamCatchUp.ProgrammeAction
import com.nuvio.tv.core.iptv.XtreamProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An M3U playlist's channels with catch-up attributes show a replay in the guide; the rest stay live-only. */
class GuideM3uCatchUpTest {

    private val now = 1_710_000_000_000L
    private val hour = 60 * 60_000L
    private val info = M3uCatchUpInfo(M3uCatchUpType.SHIFT, null, 3)

    private fun channel(withCatchUp: Boolean) = GuideChannel(
        contentId = "xtream:list:live:5", name = "BBC", logo = null, streamUrl = "http://h/5.ts", streamId = 5,
        hasArchive = withCatchUp, catchUpDays = if (withCatchUp) 3 else 0, m3uCatchUp = info.takeIf { withCatchUp },
    )

    private fun finished() = XtreamProgram("Show", "", now - 3 * hour, now - 2 * hour, nowPlaying = false)

    @Test
    fun `a catch-up channel on an M3U playlist replays a finished programme`() {
        assertEquals(ProgrammeAction.REPLAY, guideActionFor(finished(), channel(true), now, catchUpSupported = true))
    }

    private fun airing() = XtreamProgram("Live show", "", now - 20 * 60_000L, now + 40 * 60_000L, nowPlaying = true)

    @Test
    fun `the airing programme of an M3U catch-up channel offers Start over and opens the sheet`() {
        val action = guideActionFor(airing(), channel(true), now, catchUpSupported = true)
        assertEquals("airing + catch-up channel", ProgrammeAction.START_OVER, action)
        assertEquals("OK opens the Start over / Watch live sheet", GuideCellIntent.Intent.OPEN_SHEET, GuideCellIntent.forAction(action))
    }

    @Test
    fun `the airing programme of a channel without catch-up just plays live`() {
        val action = guideActionFor(airing(), channel(false), now, catchUpSupported = true)
        assertEquals(ProgrammeAction.PLAY_LIVE, action)
        assertEquals(GuideCellIntent.Intent.PLAY_LIVE, GuideCellIntent.forAction(action))
    }

    @Test
    fun `a past programme on a channel without catch-up explains itself instead of doing nothing`() {
        val action = guideActionFor(finished(), channel(false), now, catchUpSupported = true)
        assertEquals(ProgrammeAction.NONE, action)
        assertTrue(GuideCellIntent.explainsNoRecording(action, finished(), now))
        // The future, a degenerate row, and a replayable programme say nothing.
        val future = XtreamProgram("Later", "", now + hour, now + 2 * hour, nowPlaying = false)
        assertFalse("future", GuideCellIntent.explainsNoRecording(ProgrammeAction.NONE, future, now))
        val degenerate = XtreamProgram("Bad", "", 0L, 0L, nowPlaying = false)
        assertFalse("degenerate row", GuideCellIntent.explainsNoRecording(ProgrammeAction.NONE, degenerate, now))
        assertFalse("replayable", GuideCellIntent.explainsNoRecording(ProgrammeAction.REPLAY, finished(), now))
    }

    @Test
    fun `the channel menu offers Start over for the airing programme of an M3U catch-up channel only`() {
        val rows = listOf(finished(), airing())
        fun candidate(ch: GuideChannel) =
            GuideCellIntent.startOverCandidate(rows, now) { guideActionFor(it, ch, now, catchUpSupported = true) }
        assertEquals("catch-up channel", airing(), candidate(channel(true)))
        assertEquals("no catch-up", null, candidate(channel(false)))
        assertEquals(
            "nothing airing",
            null,
            GuideCellIntent.startOverCandidate(listOf(finished()), now) { guideActionFor(it, channel(true), now, true) },
        )
    }

    @Test
    fun `a channel without catch-up attributes offers no replay`() {
        assertEquals(ProgrammeAction.NONE, guideActionFor(finished(), channel(false), now, catchUpSupported = true))
    }

    @Test
    fun `M3U playlists are recognised and xtream and stalker are not`() {
        fun acc(type: String) = XtreamAccount(id = "a", name = "n", baseUrl = "", username = "", password = "", sourceType = type)
        assertTrue(acc(XtreamAccount.SOURCE_URL).isM3uPlaylist())
        assertTrue(acc(XtreamAccount.SOURCE_FILE).isM3uPlaylist())
        assertFalse(acc(XtreamAccount.SOURCE_XTREAM).isM3uPlaylist())
        assertFalse(acc(XtreamAccount.SOURCE_STALKER).isM3uPlaylist())
    }

    @Test
    fun `a favourites row takes its catalog twin's M3U catch-up`() {
        val bare = GuideChannel("xtream:list:live:5", "BBC", null, "", 5)
        val twin = channel(true).copy(entityId = "e")
        val out = GuideSyntheticRows.withCatalogIdentity(listOf(bare)) { twin }
        assertEquals(info, out.single().m3uCatchUp)
    }

    @Test
    fun `replay notices read plainly`() {
        assertEquals("This provider has no recording of \"Show\"", GuideReplayMessages.noRecording("Show"))
        assertEquals("Couldn't open \"Show\"", GuideReplayMessages.openFailed("Show"))
    }
}
