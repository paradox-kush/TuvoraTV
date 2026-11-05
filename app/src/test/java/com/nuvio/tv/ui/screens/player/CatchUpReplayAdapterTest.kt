package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.CatchUpReplayAdapter.ErrorRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catch-up replay adapter on upstream's VOD player (vod-converge step 3, audit §7a option i).
 * Upstream's player has no idea a replay exists, so these pin the three things it must do for one:
 * keep seeks inside what the panel archived, run the URL-dialect walk BEFORE its own recovery
 * ladder, and report a proven URL shape exactly once.
 */
class CatchUpReplayAdapterTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_710_000_000_000L

    private fun replay(startMs: Long, endMs: Long) =
        CatchUpReplayAdapter(isCatchUp = true, programmeStartMs = startMs, programmeEndMs = endMs)

    private val vod = CatchUpReplayAdapter(isCatchUp = false, programmeStartMs = null, programmeEndMs = null)

    @Test
    fun `a finished programme clamps seeks to its own length`() {
        val adapter = replay(startMs = now - 3 * hour, endMs = now - 2 * hour)
        assertEquals("past the end stops at the end", hour, adapter.seekTargetMs(5 * hour, now))
        assertEquals("before the start stops at the start", 0L, adapter.seekTargetMs(-30_000L, now))
        assertEquals("inside the programme is untouched", 20 * minute, adapter.seekTargetMs(20 * minute, now))
    }

    @Test
    fun `a programme still airing clamps to what has aired less the live-edge guard`() {
        val adapter = replay(startMs = now - 30 * minute, endMs = now + 30 * minute)
        val ceiling = 30 * minute - CatchUpPlaybackPolicy.LIVE_EDGE_GUARD_MS
        assertEquals(ceiling, adapter.seekTargetMs(55 * minute, now))
    }

    @Test
    fun `VOD and a replay without bounds keep the player's own seek target`() {
        assertEquals(5 * hour, vod.seekTargetMs(5 * hour, now))
        val unbounded = CatchUpReplayAdapter(isCatchUp = true, programmeStartMs = null, programmeEndMs = now)
        assertEquals(5 * hour, unbounded.seekTargetMs(5 * hour, now))
    }

    /**
     * Ordering: a failed replay attempt is most often the wrong URL shape for this panel. Upstream's
     * retry rungs would spend the account's single connection replaying that same wrong shape, so
     * the dialect walk must get the error first.
     */
    @Test
    fun `before the replay plays, the dialect walk gets the error ahead of player recovery`() {
        val adapter = replay(startMs = now - 2 * hour, endMs = now - hour)
        assertEquals(ErrorRoute.DIALECT_WALK_FIRST, adapter.routeError())
    }

    @Test
    fun `once the replay has played, errors go to the player's own recovery`() {
        val adapter = replay(startMs = now - 2 * hour, endMs = now - hour)
        adapter.onFirstFrame()
        assertEquals(ErrorRoute.PLAYER_RECOVERY, adapter.routeError())
    }

    @Test
    fun `VOD errors never enter the dialect walk`() {
        assertEquals(ErrorRoute.PLAYER_RECOVERY, vod.routeError())
    }

    @Test
    fun `the proven URL shape is reported once per replay, and never for VOD`() {
        val adapter = replay(startMs = now - 2 * hour, endMs = now - hour)
        assertTrue("first frame reports", adapter.onFirstFrame())
        assertFalse("a later first frame (seek, re-buffer) does not report again", adapter.onFirstFrame())
        assertFalse(vod.onFirstFrame())
    }

    @Test
    fun `a relinked replay is a fresh attempt with a fresh startup budget`() {
        val adapter = replay(startMs = now - 3 * hour, endMs = now - 2 * hour)
        assertTrue(adapter.onFirstFrame())
        assertEquals("a replay that played no longer has a startup deadline", null, adapter.startupDeadlineMs(now))

        adapter.onRelinked()

        assertEquals("the walk is ours to route again", ErrorRoute.DIALECT_WALK_FIRST, adapter.routeError())
        assertTrue("it has a deadline again", adapter.startupDeadlineMs(now + hour) != null)
        assertTrue("and reports its first frame again", adapter.onFirstFrame())
    }
}
