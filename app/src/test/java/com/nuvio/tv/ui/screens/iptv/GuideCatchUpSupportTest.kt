package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamCatchUp.ProgrammeAction
import com.nuvio.tv.core.iptv.XtreamProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A playlist that cannot build a catch-up URL must not show catch-up.
 *
 * `tv_archive` is parsed for Stalker channels too, so without this gate a Stalker portal would show
 * replay badges and focusable past cells that press into nothing — a Stalker archive URL is built
 * SERVER-side from a create_link cmd, which is a path we do not have. An M3U playlist has no panel
 * to ask at all. Catch-up is already rare enough (44 archive channels in 26,430 on a real panel)
 * that the guide must never imply more of it than exists.
 */
class GuideCatchUpSupportTest {

    private val now = 1_710_000_000_000L
    private val hour = 60 * 60_000L

    private val archiveChannel = GuideChannel(
        contentId = "xtream:acc:live:101",
        name = "BBC One",
        logo = null,
        streamUrl = "http://host/live/u/p/101.ts",
        streamId = 101,
        hasArchive = true,
        catchUpDays = 7,
    )

    private fun programme(startMs: Long, endMs: Long, marked: Boolean? = null) =
        XtreamProgram("Gardeners' World", "", startMs, endMs, nowPlaying = false, hasArchive = marked)

    @Test
    fun `a supported playlist keeps every catch-up state`() {
        assertEquals(
            "finished replays",
            ProgrammeAction.REPLAY,
            guideActionFor(programme(now - 3 * hour, now - 2 * hour), archiveChannel, now, catchUpSupported = true),
        )
        assertEquals(
            "airing starts over",
            ProgrammeAction.START_OVER,
            guideActionFor(programme(now - hour, now + hour), archiveChannel, now, catchUpSupported = true),
        )
    }

    /** Start-over degrades to plain live — the channel is still watchable, just not restartable. */
    @Test
    fun `an unsupported playlist downgrades start over to live`() {
        assertEquals(
            "still watchable",
            ProgrammeAction.PLAY_LIVE,
            guideActionFor(programme(now - hour, now + hour), archiveChannel, now, catchUpSupported = false),
        )
    }

    /** A finished programme has nothing to offer at all — better an inert cell than a dead press. */
    @Test
    fun `an unsupported playlist offers nothing for a finished programme`() {
        val action =
            guideActionFor(programme(now - 3 * hour, now - 2 * hour), archiveChannel, now, catchUpSupported = false)
        assertEquals("not playable", ProgrammeAction.NONE, action)
        assertFalse("and not a focus target", GuideCellIntent.isFocusable(action))
        assertFalse("and carries no badge", GuideCellIntent.showsReplayBadge(action))
    }

    /** Not even the panel's own per-programme mark can conjure a URL builder we do not have. */
    @Test
    fun `a marked programme is still not offered on an unsupported playlist`() {
        assertEquals(
            "marked but unbuildable",
            ProgrammeAction.NONE,
            guideActionFor(
                programme(now - 3 * hour, now - 2 * hour, marked = true),
                archiveChannel.copy(hasArchive = false, catchUpDays = 0),
                now,
                catchUpSupported = false,
            ),
        )
    }

    /** A future programme is unplayable either way — support does not change that. */
    @Test
    fun `a future programme is unplayable regardless of support`() {
        assertEquals(
            "supported",
            ProgrammeAction.NONE,
            guideActionFor(programme(now + hour, now + 2 * hour), archiveChannel, now, catchUpSupported = true),
        )
        assertEquals(
            "unsupported",
            ProgrammeAction.NONE,
            guideActionFor(programme(now + hour, now + 2 * hour), archiveChannel, now, catchUpSupported = false),
        )
    }
}
