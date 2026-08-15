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
        programmeHasArchive: Boolean? = null,
    ) = XtreamCatchUp.actionFor(
        programmeStartMs = startMs,
        programmeEndMs = endMs,
        nowMs = now,
        hasArchive = hasArchive,
        catchUpDays = catchUpDays,
        programmeHasArchive = programmeHasArchive,
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

    /**
     * Panels ship degenerate EPG rows — end at or before start, or a zero/epoch start (an absent
     * timestamp parses to 0). Offering REPLAY of a zero-length programme at epoch is a
     * guaranteed-dead URL, so a row that cannot describe a real broadcast offers nothing.
     * StreamVault refuses these outright (start > 0, end > start); so do we.
     */
    @Test
    fun `a degenerate programme offers nothing`() {
        // Zero length: end == start.
        assertEquals(ProgrammeAction.NONE, actionFor(now - hour, now - hour))
        // Negative length: end before start.
        assertEquals(ProgrammeAction.NONE, actionFor(now - hour, now - 2 * hour))
        // Epoch start — the shape a missing start_timestamp produces.
        assertEquals(ProgrammeAction.NONE, actionFor(0L, hour, catchUpDays = 0))
        // Negative start is no better.
        assertEquals(ProgrammeAction.NONE, actionFor(-hour, hour, catchUpDays = 0))
    }

    // --- Per-programme has_archive: the panel speaking recording by recording. ------------------
    // POSITIVE override only (the locked policy): true = the panel says THIS programme is kept,
    // which beats every channel-level rule; false and null leave the existing rules untouched,
    // because many panels serve catch-up while never marking anything.

    /** The spec-named baseline: a marked programme on an unknown-window channel replays. */
    @Test
    fun `a marked programme replays on an unknown-window channel`() {
        val longAgo = now - 30L * 24 * hour
        assertEquals(
            ProgrammeAction.REPLAY,
            actionFor(longAgo, longAgo + hour, catchUpDays = 0, programmeHasArchive = true)
        )
    }

    /** The override's real teeth: the panel keeping a programme beats a stated window. */
    @Test
    fun `a marked programme replays outside the stated window`() {
        val tenDaysAgo = now - 10L * 24 * hour
        assertEquals(
            ProgrammeAction.REPLAY,
            actionFor(tenDaysAgo, tenDaysAgo + hour, catchUpDays = 7, programmeHasArchive = true)
        )
    }

    /** ...and beats a channel the panel forgot to flag tv_archive on. */
    @Test
    fun `a marked programme replays on a channel with no archive flag`() {
        assertEquals(
            ProgrammeAction.REPLAY,
            actionFor(now - 3 * hour, now - 2 * hour, hasArchive = false, programmeHasArchive = true)
        )
    }

    @Test
    fun `a marked airing programme can start over on an unflagged channel`() {
        assertEquals(
            ProgrammeAction.START_OVER,
            actionFor(now - hour, now + hour, hasArchive = false, programmeHasArchive = true)
        )
    }

    /** A mark is not a time machine: the start must still have passed. */
    @Test
    fun `a marked future programme still offers nothing`() {
        assertEquals(
            ProgrammeAction.NONE,
            actionFor(now + hour, now + 2 * hour, programmeHasArchive = true)
        )
    }

    /** Nor does a mark rescue a degenerate row — the URL would still be dead. */
    @Test
    fun `a marked degenerate programme still offers nothing`() {
        assertEquals(
            ProgrammeAction.NONE,
            actionFor(now - hour, now - hour, programmeHasArchive = true)
        )
    }

    /** The spec-named permissive pin: silence (null) must not become restrictive. */
    @Test
    fun `an unmarked programme still replays when the panel never marks anything`() {
        val longAgo = now - 30L * 24 * hour
        assertEquals(
            ProgrammeAction.REPLAY,
            actionFor(longAgo, longAgo + hour, catchUpDays = 0, programmeHasArchive = null)
        )
    }

    /** false is NOT a veto — the override is positive-only, so channel rules decide as before. */
    @Test
    fun `an explicitly unmarked programme follows the channel rules`() {
        assertEquals(
            ProgrammeAction.REPLAY,
            actionFor(now - 3 * hour, now - 2 * hour, programmeHasArchive = false)
        )
        assertEquals(
            ProgrammeAction.NONE,
            actionFor(now - 3 * hour, now - 2 * hour, hasArchive = false, programmeHasArchive = false)
        )
    }
}
