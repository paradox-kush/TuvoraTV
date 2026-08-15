package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.CatchUpPlaybackPolicy.Scrub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catch-up is a recording, not a live feed, and the player currently disagrees: the replay id keeps
 * the `:live:` segment, so every live-only behaviour fires on it. These pin the flag that turns
 * each of them off — and, just as importantly, that ordinary live keeps them.
 */
class CatchUpPlaybackPolicyTest {

    private val hour = 60 * 60_000L
    private val now = 1_710_000_000_000L

    /** Up/down must not zap to another channel in the middle of a recording. */
    @Test
    fun `channel zapping is off during catch-up and on for live`() {
        assertFalse(
            "replay must not zap",
            CatchUpPlaybackPolicy.allowsChannelZap(isLive = true, isCatchUpPlayback = true),
        )
        assertTrue(
            "live still zaps",
            CatchUpPlaybackPolicy.allowsChannelZap(isLive = true, isCatchUpPlayback = false),
        )
        assertFalse(
            "a movie never zapped",
            CatchUpPlaybackPolicy.allowsChannelZap(isLive = false, isCatchUpPlayback = false),
        )
    }

    /** Backgrounding a recording and coming back must resume where you were, not at the live edge. */
    @Test
    fun `live-edge resume is off during catch-up and on for live`() {
        assertFalse(
            "replay resumes in place",
            CatchUpPlaybackPolicy.allowsLiveEdgeResume(isLive = true, isCatchUpPlayback = true),
        )
        assertTrue(
            "live rejoins the edge",
            CatchUpPlaybackPolicy.allowsLiveEdgeResume(isLive = true, isCatchUpPlayback = false),
        )
    }

    /**
     * The freeze watchdog exists to catch a live stream that stopped advancing. A recording that
     * reaches its end has not frozen — arming it there reports phantom freezes and, worse, its
     * recovery ladder reloads the stream from the live edge.
     */
    @Test
    fun `the freeze watchdog is disarmed during catch-up and armed for live`() {
        assertFalse(
            "replay cannot freeze",
            CatchUpPlaybackPolicy.armsFreezeWatchdog(isLive = true, isCatchUpPlayback = true),
        )
        assertTrue(
            "live arms it",
            CatchUpPlaybackPolicy.armsFreezeWatchdog(isLive = true, isCatchUpPlayback = false),
        )
        assertFalse(
            "a movie never armed it",
            CatchUpPlaybackPolicy.armsFreezeWatchdog(isLive = false, isCatchUpPlayback = false),
        )
    }

    /**
     * Whether the viewer gets a draggable bar is the provider's choice, not ours: `.m3u8` sends a
     * playlist with every segment's duration and scrubs, `.ts` sends a progressive stream and does
     * not. Show a bar with no handle rather than a handle that ignores drags.
     */
    @Test
    fun `scrubbing needs both a duration and a seekable stream`() {
        assertEquals(
            "hls with a duration",
            Scrub.SEEKABLE,
            CatchUpPlaybackPolicy.scrubFor(durationMs = 60 * hour / 60, playerSaysSeekable = true),
        )
        assertEquals(
            "ts reports no duration",
            Scrub.NO_SCRUB,
            CatchUpPlaybackPolicy.scrubFor(durationMs = 0L, playerSaysSeekable = true),
        )
        assertEquals(
            "media3 unset duration is negative",
            Scrub.NO_SCRUB,
            CatchUpPlaybackPolicy.scrubFor(durationMs = -9_223_372_036_854_775_807L, playerSaysSeekable = true),
        )
        assertEquals(
            "a duration the player still refuses to seek",
            Scrub.NO_SCRUB,
            CatchUpPlaybackPolicy.scrubFor(durationMs = hour, playerSaysSeekable = false),
        )
    }

    /**
     * Start-over plays a programme that has not finished airing. Seeking to its nominal end asks the
     * panel for a fragment it has not archived yet, so the right edge stops short of live.
     */
    @Test
    fun `the seek ceiling stops short of the live edge while the programme is still airing`() {
        val start = now - 40 * 60_000L
        val end = now + 20 * 60_000L
        assertEquals(
            "elapsed minus the guard",
            40 * 60_000L - CatchUpPlaybackPolicy.LIVE_EDGE_GUARD_MS,
            CatchUpPlaybackPolicy.maxSeekMs(start, end, now),
        )
    }

    /** A finished programme is entirely in the past, so the whole thing is seekable. */
    @Test
    fun `a finished programme seeks to its end`() {
        val start = now - 3 * hour
        val end = now - 2 * hour
        assertEquals("full duration", hour, CatchUpPlaybackPolicy.maxSeekMs(start, end, now))
    }

    /** A programme that only just started has nothing to seek into — never a negative ceiling. */
    @Test
    fun `a just-started programme has a zero ceiling rather than a negative one`() {
        val start = now - 30_000L
        assertEquals(
            "clamped at zero",
            0L,
            CatchUpPlaybackPolicy.maxSeekMs(start, now + hour, now),
        )
    }

    @Test
    fun `a seek past the ceiling is clamped to it`() {
        val start = now - 40 * 60_000L
        val end = now + 20 * 60_000L
        val ceiling = CatchUpPlaybackPolicy.maxSeekMs(start, end, now)
        assertEquals(
            "clamped forward",
            ceiling,
            CatchUpPlaybackPolicy.clampSeekMs(59 * 60_000L, start, end, now),
        )
        assertEquals(
            "clamped back",
            0L,
            CatchUpPlaybackPolicy.clampSeekMs(-5_000L, start, end, now),
        )
        assertEquals(
            "left alone inside the range",
            10 * 60_000L,
            CatchUpPlaybackPolicy.clampSeekMs(10 * 60_000L, start, end, now),
        )
    }
}
