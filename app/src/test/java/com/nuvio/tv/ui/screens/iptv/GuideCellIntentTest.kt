package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamCatchUp
import com.nuvio.tv.core.iptv.XtreamCatchUp.ProgrammeAction
import com.nuvio.tv.ui.screens.iptv.GuideCellIntent.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What OK does on a programme cell, per the locked decision: a finished replayable programme plays
 * INSTANTLY, the airing programme on an archive channel opens the two-button sheet (the only state
 * with two reasonable destinations), an airing programme with no archive plays live, and anything
 * unplayable is not a target at all.
 */
class GuideCellIntentTest {

    private val now = 1_710_000_000_000L
    private val hour = 60 * 60_000L

    private fun actionFor(
        startMs: Long,
        endMs: Long,
        hasArchive: Boolean = true,
        catchUpDays: Int = 7,
        programmeHasArchive: Boolean? = null,
    ) = XtreamCatchUp.actionFor(startMs, endMs, now, hasArchive, catchUpDays, programmeHasArchive)

    @Test
    fun `a finished replayable programme plays instantly with no sheet`() {
        val action = actionFor(now - 3 * hour, now - 2 * hour)
        assertEquals("state", ProgrammeAction.REPLAY, action)
        assertEquals("OK replays", Intent.REPLAY, GuideCellIntent.forAction(action))
    }

    @Test
    fun `the airing programme on an archive channel opens the sheet`() {
        val action = actionFor(now - hour, now + hour)
        assertEquals("state", ProgrammeAction.START_OVER, action)
        assertEquals("OK opens the sheet", Intent.OPEN_SHEET, GuideCellIntent.forAction(action))
    }

    @Test
    fun `the airing programme without an archive plays live with no sheet`() {
        val action = actionFor(now - hour, now + hour, hasArchive = false)
        assertEquals("state", ProgrammeAction.PLAY_LIVE, action)
        assertEquals("OK plays live", Intent.PLAY_LIVE, GuideCellIntent.forAction(action))
    }

    @Test
    fun `an unplayable programme has no intent`() {
        val future = actionFor(now + hour, now + 2 * hour)
        assertEquals("state", ProgrammeAction.NONE, future)
        assertEquals("OK does nothing", Intent.NONE, GuideCellIntent.forAction(future))

        val gone = actionFor(now - 3 * hour, now - 2 * hour, hasArchive = false)
        assertEquals("state", ProgrammeAction.NONE, gone)
        assertEquals("OK does nothing", Intent.NONE, GuideCellIntent.forAction(gone))
    }

    /**
     * The D-pad must skip cells it cannot act on — otherwise travelling back through a channel with
     * no archive walks a row of dead targets, which is exactly the false promise the whole design
     * is trying to avoid (44 archive channels in 26,430 on a real panel).
     */
    @Test
    fun `only actionable cells take focus`() {
        assertFalse("nothing playable", GuideCellIntent.isFocusable(ProgrammeAction.NONE))
        assertTrue("replay", GuideCellIntent.isFocusable(ProgrammeAction.REPLAY))
        assertTrue("start over", GuideCellIntent.isFocusable(ProgrammeAction.START_OVER))
        assertTrue("live", GuideCellIntent.isFocusable(ProgrammeAction.PLAY_LIVE))
    }

    /** The badge marks what the panel actually kept — not "this cell is selectable". */
    @Test
    fun `the replay badge marks only archived programmes`() {
        assertTrue("replay is archived", GuideCellIntent.showsReplayBadge(ProgrammeAction.REPLAY))
        assertTrue("start over is archived", GuideCellIntent.showsReplayBadge(ProgrammeAction.START_OVER))
        assertFalse("plain live is not", GuideCellIntent.showsReplayBadge(ProgrammeAction.PLAY_LIVE))
        assertFalse("unplayable is not", GuideCellIntent.showsReplayBadge(ProgrammeAction.NONE))
    }

    /** The panel's own per-programme mark reaches the intent, not just the channel flag. */
    @Test
    fun `a marked programme on an unflagged channel still replays instantly`() {
        val action = actionFor(now - 3 * hour, now - 2 * hour, hasArchive = false, programmeHasArchive = true)
        assertEquals("OK replays", Intent.REPLAY, GuideCellIntent.forAction(action))
    }
}
