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
        assertEquals("Couldn't open \"Show\". Press OK to try again", GuideReplayMessages.openFailed("Show"))
    }
}
