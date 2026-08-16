package com.nuvio.tv.core.iptv.stalker

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StalkerPlaybackTrafficTest {

    @Before fun setUp() = StalkerPlaybackTraffic.resetForTests()
    @After fun tearDown() = StalkerPlaybackTraffic.resetForTests()

    @Test
    fun `nothing playing means browse goes straight through`() {
        assertFalse(StalkerPlaybackTraffic.shouldDefer(playbackActive = false, waitedMs = 0, isBootstrap = false))
    }

    @Test
    fun `browse waits while a stream is up`() {
        assertTrue(StalkerPlaybackTraffic.shouldDefer(playbackActive = true, waitedMs = 0, isBootstrap = false))
    }

    /**
     * The gate may only ever delay. A counter that somehow never returns to zero must not brick
     * browsing, so the wait is capped and the call then goes anyway.
     */
    @Test
    fun `waiting is capped so a stuck counter cannot brick browsing`() {
        assertTrue(
            StalkerPlaybackTraffic.shouldDefer(
                playbackActive = true,
                waitedMs = StalkerPlaybackTraffic.MAX_DEFER_MS - 1,
                isBootstrap = false
            )
        )
        assertFalse(
            StalkerPlaybackTraffic.shouldDefer(
                playbackActive = true,
                waitedMs = StalkerPlaybackTraffic.MAX_DEFER_MS,
                isBootstrap = false
            )
        )
    }

    /**
     * Playback needs an authenticated session, so deferring handshake/get_profile behind playback
     * would deadlock the very thing the gate protects.
     */
    @Test
    fun `bootstrap calls are never deferred`() {
        assertFalse(StalkerPlaybackTraffic.shouldDefer(playbackActive = true, waitedMs = 0, isBootstrap = true))
    }

    @Test
    fun `the flag follows playback`() {
        assertFalse(StalkerPlaybackTraffic.isPlaybackActive)
        StalkerPlaybackTraffic.onPlaybackStarted()
        assertTrue(StalkerPlaybackTraffic.isPlaybackActive)
        StalkerPlaybackTraffic.onPlaybackStopped()
        assertFalse(StalkerPlaybackTraffic.isPlaybackActive)
    }

    /**
     * Channel zapping re-reports "started" with no stop in between. A counter would climb and never
     * return; one stop must always be enough to release browse traffic.
     */
    @Test
    fun `repeated starts are released by a single stop`() {
        repeat(5) { StalkerPlaybackTraffic.onPlaybackStarted() }
        StalkerPlaybackTraffic.onPlaybackStopped()
        assertFalse(StalkerPlaybackTraffic.isPlaybackActive)
    }

    /** A stop with no start is normal (a surface torn down twice) and must be harmless. */
    @Test
    fun `an unmatched stop is harmless`() {
        StalkerPlaybackTraffic.onPlaybackStopped()
        StalkerPlaybackTraffic.onPlaybackStopped()
        assertFalse(StalkerPlaybackTraffic.isPlaybackActive)

        StalkerPlaybackTraffic.onPlaybackStarted()
        assertTrue(
            StalkerPlaybackTraffic.shouldDefer(
                StalkerPlaybackTraffic.isPlaybackActive, waitedMs = 0, isBootstrap = false
            )
        )
    }

    /**
     * Switching providers must abandon the OTHER provider's queued browse work.
     *
     * Field report (S24 mobile, 2026-08-16; same code shape here): scrolling a Stalker portal
     * queues dozens of 14-row get_ordered_list calls behind the 2-permit gate; switching to an
     * Xtream provider on the SAME host then hangs — the abandoned backlog keeps draining at the
     * throttled host's pace, ahead of everything the user is now looking at. A queued browse call
     * from before the switch must be dropped when its turn finally comes; playback-critical calls
     * are never dropped (a replay resolving mid-switch must not lose its create_link).
     */
    @Test
    fun `a provider switch abandons queued browse calls but never critical ones`() {
        val before = StalkerPlaybackTraffic.browseEpoch
        StalkerPlaybackTraffic.onProviderSwitched()
        val after = StalkerPlaybackTraffic.browseEpoch

        // A browse call enqueued before the switch is stale when its permit arrives.
        assertTrue(StalkerPlaybackTraffic.isAbandoned(requestEpoch = before, currentEpoch = after, isCritical = false))
        // One enqueued after the switch runs normally.
        assertFalse(StalkerPlaybackTraffic.isAbandoned(requestEpoch = after, currentEpoch = after, isCritical = false))
        // Playback-critical work survives any number of switches.
        assertFalse(StalkerPlaybackTraffic.isAbandoned(requestEpoch = before, currentEpoch = after, isCritical = true))
    }
}
