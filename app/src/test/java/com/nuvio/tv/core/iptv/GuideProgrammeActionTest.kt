package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.XtreamCatchUp.ProgrammeAction
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideProgrammeActionTest {

    private val now = 1_710_000_000_000L
    private val hour = 60 * 60_000L

    private fun actionFor(
        startMs: Long,
        endMs: Long,
        hasArchive: Boolean = true,
        catchUpDays: Int = 7,
    ) = XtreamCatchUp.actionFor(
        programmeStartMs = startMs,
        programmeEndMs = endMs,
        nowMs = now,
        hasArchive = hasArchive,
        catchUpDays = catchUpDays,
    )

    /** Nothing to offer for something that has not been broadcast yet. */
    @Test
    fun `a future programme offers nothing`() {
        assertEquals(ProgrammeAction.NONE, actionFor(now + hour, now + 2 * hour))
        assertEquals(ProgrammeAction.NONE, actionFor(now + hour, now + 2 * hour, hasArchive = false))
    }

    /**
     * A channel without catch-up can still be watched — the programme airing now is just "live".
     * Losing that would make the guide worse for the majority of channels, which have no archive.
     */
    @Test
    fun `the airing programme plays live when there is no archive`() {
        assertEquals(ProgrammeAction.PLAY_LIVE, actionFor(now - hour, now + hour, hasArchive = false))
    }

    /**
     * With an archive, the programme airing now can be restarted from the beginning. This is the
     * feature people call "start over", and it is the one catch-up affordance most viewers use.
     */
    @Test
    fun `the airing programme can be started over when there is an archive`() {
        assertEquals(ProgrammeAction.START_OVER, actionFor(now - hour, now + hour))
    }

    @Test
    fun `a finished programme inside the window is replayable`() {
        assertEquals(ProgrammeAction.REPLAY, actionFor(now - 3 * hour, now - 2 * hour))
    }

    @Test
    fun `a finished programme offers nothing without an archive`() {
        assertEquals(
            ProgrammeAction.NONE,
            actionFor(now - 3 * hour, now - 2 * hour, hasArchive = false)
        )
    }

    /** Past the provider's retention there is nothing left on the panel to play. */
    @Test
    fun `a finished programme older than the window offers nothing`() {
        val eightDaysAgo = now - 8L * 24 * hour
        assertEquals(
            ProgrammeAction.NONE,
            actionFor(eightDaysAgo, eightDaysAgo + hour, catchUpDays = 7)
        )
    }

    /** An unreported window must not hide catch-up — see isWithinWindow. */
    @Test
    fun `an unknown window still allows replay`() {
        val longAgo = now - 30L * 24 * hour
        assertEquals(
            ProgrammeAction.REPLAY,
            actionFor(longAgo, longAgo + hour, catchUpDays = 0)
        )
    }

    /** Boundary: a programme that ends exactly now has finished, so it replays rather than plays. */
    @Test
    fun `a programme ending exactly now is treated as finished`() {
        assertEquals(ProgrammeAction.REPLAY, actionFor(now - hour, now))
    }

    /** Boundary: a programme starting exactly now is airing, not future. */
    @Test
    fun `a programme starting exactly now is airing`() {
        assertEquals(ProgrammeAction.START_OVER, actionFor(now, now + hour))
    }
}
