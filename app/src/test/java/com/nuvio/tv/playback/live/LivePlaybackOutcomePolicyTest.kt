package com.nuvio.tv.playback.live

import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.live.LivePlaybackOutcomePolicy.Outcome
import com.nuvio.tv.playback.live.LivePlaybackOutcomePolicy.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackOutcomePolicyTest {

    @Test
    fun `every playback state has a phase and a release on the way to failed is still a wait`() {
        for (state in PlaybackState.entries) LivePlaybackOutcomePolicy.phaseOf(state)
        assertEquals("reconnecting", Phase.WAITING, LivePlaybackOutcomePolicy.phaseOf(PlaybackState.LIVE_RECONNECTING))
        assertEquals("release barrier", Phase.WAITING, LivePlaybackOutcomePolicy.phaseOf(PlaybackState.RELEASING))
        assertEquals("failed", Phase.FAILED, LivePlaybackOutcomePolicy.phaseOf(PlaybackState.FAILED))
        assertEquals("degraded still shows a picture", Phase.PLAYING, LivePlaybackOutcomePolicy.phaseOf(PlaybackState.DEGRADED))
    }

    /** B256: played, provider died, one reconnect, release FAILURE, FAILED. One report, timed from the drop. */
    @Test
    fun `a mid-play provider death reports one failed outcome timed from the drop`() {
        val tracker = LivePlaybackOutcomePolicy.Tracker()
        assertNull("playing", tracker.observe(1, Phase.PLAYING, null, 1_000))
        assertNull("reconnecting", tracker.observe(1, Phase.WAITING, null, 5_000))
        assertNull("release barrier", tracker.observe(1, Phase.WAITING, null, 6_000))
        val report = tracker.observe(1, Phase.FAILED, FailureCode.NETWORK_UNREACHABLE, 7_500)
        assertEquals("outcome", Outcome.FAILED, report?.outcome)
        assertEquals("from the drop, not from the tune", 2_500L, report?.durationMs)
        assertEquals("reason", FailureCode.NETWORK_UNREACHABLE, report?.failureCode)
        assertNull("the same failure again is not a second outcome", tracker.observe(1, Phase.FAILED, FailureCode.NETWORK_UNREACHABLE, 9_000))
        assertNull("nothing left to wait for", tracker.deadlineAtMs)
    }

    @Test
    fun `a retry or zap is a new generation and may report again`() {
        val tracker = LivePlaybackOutcomePolicy.Tracker()
        tracker.observe(1, Phase.WAITING, null, 0)
        assertEquals(Outcome.FAILED, tracker.observe(1, Phase.FAILED, FailureCode.UNKNOWN, 100)?.outcome)
        tracker.observe(2, Phase.WAITING, null, 200)
        assertEquals("retry failed again", Outcome.FAILED, tracker.observe(2, Phase.FAILED, FailureCode.UNKNOWN, 300)?.outcome)
    }

    @Test
    fun `a recovered picture closes the incident`() {
        val tracker = LivePlaybackOutcomePolicy.Tracker()
        tracker.observe(1, Phase.WAITING, null, 0)
        tracker.observe(1, Phase.PLAYING, null, 1_000)
        assertNull("no wait open", tracker.deadlineAtMs)
        tracker.observe(1, Phase.WAITING, null, 50_000)
        assertEquals("a later drop is timed on its own", 1_000L,
            tracker.observe(1, Phase.FAILED, FailureCode.NETWORK_TIMEOUT, 51_000)?.durationMs)
    }

    /** Bounded-load rule 3: the screen ends the wait at its own deadline even if the session never answers. */
    @Test
    fun `a wait the session never ends times out once at the backstop`() {
        val tracker = LivePlaybackOutcomePolicy.Tracker()
        tracker.observe(1, Phase.PLAYING, null, 0)
        tracker.observe(1, Phase.WAITING, null, 10_000)
        val deadline = 10_000 + LivePlaybackOutcomePolicy.WAIT_BACKSTOP_MS
        assertEquals("deadline", deadline, tracker.deadlineAtMs)
        assertNull("not yet", tracker.expire(deadline - 1))
        assertFalse(tracker.stalled)
        val report = tracker.expire(deadline)
        assertEquals("timed out", Outcome.TIMED_OUT, report?.outcome)
        assertEquals("duration", LivePlaybackOutcomePolicy.WAIT_BACKSTOP_MS, report?.durationMs)
        assertTrue("the screen shows it as failed", tracker.stalled)
        assertNull("reported once", tracker.expire(deadline + 1_000))
        assertNull("a late FAILED is the same outcome", tracker.observe(1, Phase.FAILED, FailureCode.UNKNOWN, deadline + 2_000))
        assertFalse("the real failure now speaks for itself", tracker.stalled)
    }

    @Test
    fun `the backstop outlasts every session bound`() {
        assertTrue(LivePlaybackOutcomePolicy.WAIT_BACKSTOP_MS > LiveTuneDeadlinePolicy.DEADLINE_MS * 3)
    }
}
