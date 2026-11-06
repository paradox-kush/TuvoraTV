package com.nuvio.tv.core.contracts

import com.nuvio.tv.core.contracts.CatchUpContinuationPolicy.Decision
import com.nuvio.tv.core.contracts.CatchUpContinuationPolicy.Programme
import org.junit.Assert.assertEquals
import org.junit.Test

/** Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0): continuing a replay into the next show. */
class CatchUpContinuationPolicyTest {

    private val h = 3_600_000L
    private val m = 60_000L
    private val t0 = 1_700_000_000_000L
    private val a = Programme(t0, t0 + h)
    private val b = Programme(t0 + h, t0 + 2 * h)
    private val now = t0 + 10 * h

    private fun decide(
        playing: Programme = a,
        next: Programme? = b,
        playhead: Long,
        windowEnd: Long = t0 + 4 * h,
        nowMs: Long = now,
    ) = CatchUpContinuationPolicy.decide(playing, next, playhead, windowEnd, nowMs)

    @Test
    fun `stays while the playhead is inside the playing programme`() {
        assertEquals(Decision.Stay, decide(playhead = t0))
        assertEquals(Decision.Stay, decide(playhead = a.endMs - 1))
    }

    @Test
    fun `moves to the next programme the moment the playhead reaches its start`() {
        assertEquals(Decision.MoveToNext(b), decide(playhead = a.endMs))
        assertEquals(Decision.MoveToNext(b), decide(playhead = a.endMs + 5 * m))
    }

    @Test
    fun `stays when there is no next programme`() {
        assertEquals(Decision.Stay, decide(next = null, playhead = a.endMs + m))
    }

    @Test
    fun `stays when the next programme is the one already playing`() {
        assertEquals(Decision.Stay, decide(next = a, playhead = a.endMs + m))
    }

    @Test
    fun `moves across a gap in the guide`() {
        val late = Programme(a.endMs + 10 * m, a.endMs + h)
        assertEquals(Decision.MoveToNext(late), decide(next = late, playhead = a.endMs + m))
    }

    @Test
    fun `starts a new replay where the window ran out`() {
        val windowEnd = a.endMs + 3 * h
        val at = windowEnd + 2 * m
        assertEquals(Decision.StartNewReplay(at), decide(next = Programme(windowEnd, windowEnd + h), playhead = at, windowEnd = windowEnd))
    }

    @Test
    fun `window ending inside the playing programme restarts rather than moves`() {
        val windowEnd = a.startMs + 30 * m
        assertEquals(
            Decision.StartNewReplay(windowEnd),
            decide(playhead = windowEnd, windowEnd = windowEnd),
        )
    }

    @Test
    fun `rejoins live instead of restarting at the live edge`() {
        val windowEnd = a.endMs
        // playhead within a minute of now: nothing left to replay
        assertEquals(Decision.GoLive, decide(playhead = now - 30_000, windowEnd = windowEnd, next = Programme(now - h, now + h)))
        assertEquals(Decision.GoLive, decide(playhead = now - 60_000 + 1, windowEnd = windowEnd, next = Programme(now - h, now + h)))
        assertEquals(Decision.GoLive, decide(playhead = now + 5_000, windowEnd = windowEnd, next = null))
        assertEquals(
            Decision.StartNewReplay(now - 60_000),
            decide(playhead = now - 60_000, windowEnd = windowEnd, next = Programme(now - h, now + h)),
        )
    }

    @Test
    fun `window end with no programme covering the playhead ends`() {
        assertEquals(Decision.Ended, decide(next = null, playhead = a.endMs + m, windowEnd = a.endMs))
    }

    @Test
    fun `replayWindowEnd continues up to three hours past the show`() {
        val show = Programme(t0, t0 + h)
        assertEquals(show.endMs + 3 * h, CatchUpContinuationPolicy.replayWindowEndMs(show, now = t0 + 20 * h))
    }

    @Test
    fun `replayWindowEnd is capped just before now`() {
        val show = Programme(t0, t0 + h)
        assertEquals(t0 + 2 * h - 30_000, CatchUpContinuationPolicy.replayWindowEndMs(show, now = t0 + 2 * h))
    }

    @Test
    fun `replayWindowEnd is never before the show end`() {
        val show = Programme(t0, t0 + h)
        assertEquals(show.endMs, CatchUpContinuationPolicy.replayWindowEndMs(show, now = show.endMs + 10_000))
    }

    @Test
    fun `window over in a gap far from live ends rather than waiting`() {
        val windowEnd = a.endMs + 3 * h
        assertEquals(Decision.Ended, decide(next = Programme(windowEnd + h, windowEnd + 2 * h), playhead = windowEnd + m, windowEnd = windowEnd))
    }

    @Test
    fun `a window that has not run out never goes live or ends`() {
        assertEquals(Decision.Stay, decide(playhead = a.startMs + m, windowEnd = a.endMs + 3 * h, nowMs = a.startMs + 2 * m))
    }
}
