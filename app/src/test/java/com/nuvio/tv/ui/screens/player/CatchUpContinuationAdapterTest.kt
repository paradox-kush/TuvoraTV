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
    fun `seeks stay inside the programme being watched not the whole window`() {
        val a = adapter()
        assertEquals("inside the programme is untouched", 40 * minute, a.seekTargetMs(40 * minute, now))
        assertEquals("past the programme stops at its end", hour, a.seekTargetMs(2 * hour, now))
        assertEquals("past the window too", hour, a.seekTargetMs(5 * hour, now))
    }

    @Test
    fun `after moving to the next show seeks clamp to that show`() {
        val a = adapter()
        a.onPlayhead(61 * minute, now, next = b)
        assertEquals("back before B stops at B's start", hour, a.seekTargetMs(10 * minute, now))
        assertEquals("forward past B stops at B's end", 2 * hour, a.seekTargetMs(2 * hour + 30 * minute, now))
        assertEquals(90 * minute, a.seekTargetMs(90 * minute, now))
    }

    @Test
    fun `a replay that did not ask for a window never continues`() {
        val plain = CatchUpReplayAdapter(isCatchUp = true, programmeStartMs = aStart, programmeEndMs = aEnd)
        assertFalse(plain.needsLookup(2 * hour, now))
        assertEquals(ContinuationAction.None, plain.onPlayhead(2 * hour, now, next = null))
    }
}

/** The seek bar of a continuing replay spans the programme, re-based as the playhead moves on. */
class CatchUpReplayBarTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_710_000_000_000L
    private val aStart = now - 6 * hour
    private val aEnd = aStart + 30 * minute
    private val b = CatchUpProgramme("BBC · B", aEnd, aEnd + hour)

    private fun m3u(start: Long = aStart, end: Long = aEnd, window: Long = start + 3 * hour) =
        CatchUpReplayAdapter(isCatchUp = true, programmeStartMs = start, programmeEndMs = end).also {
            it.enableContinuation(sessionStartMs = start, windowEndMs = window, playingEndMs = end)
        }

    @Test
    fun `a 30 minute programme in a 3 hour window shows a 30 minute bar`() {
        val r = m3u().barReading(22_000L, 3 * hour, 5 * minute, now)
        assertEquals("position", 22_000L, r.positionMs)
        assertEquals("the bar is the programme, not the window", 30 * minute, r.durationMs)
        assertEquals("buffered", 5 * minute, r.bufferedMs)
        assertEquals("Ends at counts the programme", 30 * minute, r.programmeLengthMs)
    }

    @Test
    fun `the bar re-bases when the playhead moves to the next programme`() {
        val a = m3u()
        a.onPlayhead(31 * minute, now, next = b)
        val r = a.barReading(45 * minute, 3 * hour, 50 * minute, now)
        assertEquals("origin is B's start", 15 * minute, r.positionMs)
        assertEquals("length is B's", hour, r.durationMs)
        assertEquals(hour, r.programmeLengthMs)
        assertEquals("bar position maps back to the player", 45 * minute, a.playerPositionMs(15 * minute, now))
    }

    @Test
    fun `position never leaves the bar even before the next show is looked up`() {
        val r = m3u().barReading(33 * minute, 3 * hour, 33 * minute, now)
        assertEquals(30 * minute, r.positionMs)
    }

    @Test
    fun `a programme still airing keeps the programme length as the total while seeks stop short of live`() {
        val start = now - 30 * minute
        val end = now + 30 * minute
        val a = m3u(start = start, end = end, window = now - 30_000L)
        val r = a.barReading(10 * minute, 30 * minute, 10 * minute, now)
        assertEquals("total is the programme, not what has aired", hour, r.durationMs)
        assertEquals(hour, r.programmeLengthMs)
        assertEquals(
            "seeks still stop the guard short of live",
            30 * minute - CatchUpPlaybackPolicy.LIVE_EDGE_GUARD_MS,
            a.seekTargetMs(hour, now) - 0L,
        )
    }

    @Test
    fun `the total does not grow while the player duration grows`() {
        // 02:30-03:00 programme, now 02:35, Start over: the player has served 5 min, then 6 min.
        val start = now - 5 * minute
        val a = m3u(start = start, end = start + 30 * minute, window = now + 3 * hour)
        val first = a.barReading(20_000L, 44_000L, 44_000L, now)
        val later = a.barReading(65_000L, 90_000L, 90_000L, now + minute)
        assertEquals("total is the programme length", 30 * minute, first.durationMs)
        assertEquals("and stays it a minute later", 30 * minute, later.durationMs)
        assertEquals("Ends at counts the programme", 30 * minute, later.programmeLengthMs)
        assertEquals("buffer reflects what is served", 44_000L, first.bufferedMs)
    }

    @Test
    fun `an unknown duration stays unknown so the bar does not pretend to scrub`() {
        assertEquals(0L, m3u().barReading(0L, 0L, 0L, now).durationMs)
    }

    @Test
    fun `a restarted window re-bases to the programme at its start`() {
        val a = m3u()
        a.onPlayhead(31 * minute, now, next = b)
        // The window ran out mid-B; the new one opens 40 min into B.
        a.restarted(sessionStartMs = b.startMs + 40 * minute, windowEndMs = b.startMs + 3 * hour)
        val r = a.barReading(5 * minute, 3 * hour, 5 * minute, now)
        assertEquals("bar starts where the new window does", 5 * minute, r.positionMs)
        assertEquals("and runs to B's end", 20 * minute, r.durationMs)
    }

    @Test
    fun `Xtream and plain replays behave exactly as before`() {
        val plain = CatchUpReplayAdapter(isCatchUp = true, programmeStartMs = aStart, programmeEndMs = aEnd)
        val r = plain.barReading(12 * minute, 30 * minute, 20 * minute, now)
        assertEquals(12 * minute, r.positionMs)
        assertEquals(30 * minute, r.durationMs)
        assertEquals(20 * minute, r.bufferedMs)
        assertEquals("no programme override: Ends at keeps using the duration", null, r.programmeLengthMs)
        assertEquals("positions pass through", 7 * minute, plain.playerPositionMs(7 * minute, now))
        assertEquals("seek clamp is the programme's", 30 * minute, plain.seekTargetMs(2 * hour, now))
        val vod = CatchUpReplayAdapter(isCatchUp = false, programmeStartMs = null, programmeEndMs = null)
        assertEquals(40 * minute, vod.barReading(40 * minute, hour, hour, now).positionMs)
    }

    @Test
    fun `the pure bar arithmetic round-trips`() {
        val bar = CatchUpPlaybackPolicy.replayBar(
            streamStartMs = 0L, windowEndMs = 3 * hour, programmeStartMs = hour, programmeEndMs = 2 * hour, nowMs = now,
        )
        assertEquals(hour, bar.originMs)
        assertEquals(hour, bar.lengthMs)
        assertEquals(15 * minute, CatchUpPlaybackPolicy.barPositionMs(75 * minute, bar))
        assertEquals(75 * minute, CatchUpPlaybackPolicy.playerPositionMs(15 * minute, bar))
        assertEquals(hour, CatchUpPlaybackPolicy.clampPlayerSeekMs(0L, bar))
        assertEquals(2 * hour, CatchUpPlaybackPolicy.clampPlayerSeekMs(5 * hour, bar))
    }
}
