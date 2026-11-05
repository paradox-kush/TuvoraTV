package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.contracts.CatchUpProgramme
import com.nuvio.tv.ui.screens.player.CatchUpReplayAdapter.ContinuationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player side of a continuing replay: the adapter turns the policy's decision into one thing for
 * the controller to do. The decision itself is pinned by CatchUpContinuationPolicyTest.
 */
class CatchUpContinuationAdapterTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_710_000_000_000L

    // Programme A ran 10:00-11:00 (relative), B 11:00-12:00; the replay asked for A's start .. +3 h.
    private val aStart = now - 6 * hour
    private val aEnd = aStart + hour
    private val b = CatchUpProgramme("BBC · B", aEnd, aEnd + hour)

    private fun adapter(): CatchUpReplayAdapter =
        CatchUpReplayAdapter(isCatchUp = true, programmeStartMs = aStart, programmeEndMs = aEnd).also {
            it.enableContinuation(sessionStartMs = aStart, windowEndMs = aStart + 3 * hour, playingEndMs = aEnd)
        }

    @Test
    fun `inside the programme there is nothing to look up and nothing to do`() {
        val a = adapter()
        assertFalse("no lookup mid-show", a.needsLookup(30 * minute, now))
        assertEquals(ContinuationAction.None, a.onPlayhead(30 * minute, now, next = null))
    }

    @Test
    fun `crossing into the next show moves the title once`() {
        val a = adapter()
        assertTrue("at the boundary a lookup is worth making", a.needsLookup(61 * minute, now))
        assertEquals(ContinuationAction.MoveTo(b), a.onPlayhead(61 * minute, now, next = b))
        assertFalse("and the next boundary is B's end, not A's", a.needsLookup(90 * minute, now))
    }

    @Test
    fun `an unknown next show is looked up again only after a pause`() {
        val a = adapter()
        assertTrue(a.needsLookup(61 * minute, now))
        a.onPlayhead(61 * minute, now, next = null)
        assertFalse("not every tick", a.needsLookup(61 * minute + 500, now + 500))
        assertTrue("again after the retry delay", a.needsLookup(61 * minute + 6_000, now + 6_000))
    }

    @Test
    fun `the window running out in the middle of a show restarts at the playhead`() {
        val a = adapter()
        // B ends beyond the 3 h window; the playhead reaches the window end while B is on.
        val long = CatchUpProgramme("BBC · long", aEnd, aStart + 5 * hour)
        a.onPlayhead(61 * minute, now, next = long)
        assertEquals(
            ContinuationAction.Restart(aStart + 3 * hour + minute),
            a.onPlayhead(3 * hour + minute, now, next = long),
        )
        assertEquals("only once until the new window is open", ContinuationAction.None, a.onPlayhead(3 * hour + 2 * minute, now, next = long))
        a.restarted(sessionStartMs = aStart + 3 * hour + minute, windowEndMs = aStart + 6 * hour)
        assertEquals("a restarted replay seeks inside the new window", 0L, a.seekTargetMs(-1L, now))
    }

    @Test
    fun `reaching the live edge rejoins live exactly once`() {
        val a = CatchUpReplayAdapter(isCatchUp = true, programmeStartMs = now - 2 * hour, programmeEndMs = now - hour)
        a.enableContinuation(now - 2 * hour, windowEndMs = now - 30_000L, playingEndMs = now - hour)
        assertEquals(ContinuationAction.GoLive, a.onPlayhead(2 * hour - 10_000L, now, next = null))
        assertEquals(ContinuationAction.None, a.onPlayhead(2 * hour - 9_000L, now, next = null))
        assertEquals("a live stream is not clamped like a recording", 9 * hour, a.seekTargetMs(9 * hour, now))
    }

    @Test
    fun `a window that ran out with nothing to replay ends`() {
        val a = adapter()
        // Nothing covers the playhead (no guide rows) well before the live edge.
        assertEquals(ContinuationAction.Ended, a.onPlayhead(3 * hour + minute, now, next = null))
        assertEquals(ContinuationAction.None, a.onPlayhead(3 * hour + 2 * minute, now, next = null))
    }

    @Test
    fun `seeks may reach into the continuation window not just the first programme`() {
        val a = adapter()
        assertEquals(2 * hour, a.seekTargetMs(2 * hour, now))
        assertEquals("past the window stops at it", 3 * hour, a.seekTargetMs(5 * hour, now))
    }

    @Test
    fun `a replay that did not ask for a window never continues`() {
        val plain = CatchUpReplayAdapter(isCatchUp = true, programmeStartMs = aStart, programmeEndMs = aEnd)
        assertFalse(plain.needsLookup(2 * hour, now))
        assertEquals(ContinuationAction.None, plain.onPlayhead(2 * hour, now, next = null))
    }
}
