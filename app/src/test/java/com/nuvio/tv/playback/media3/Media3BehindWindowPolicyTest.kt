package com.nuvio.tv.playback.media3

import androidx.media3.common.PlaybackException
import com.nuvio.tv.playback.media3.Media3BehindWindowPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Device finding (Onn, guide): resuming after ~10 s ended in BehindLiveWindowException and the host
 * silently reconnected to live. A time-shifted viewer is on the LOCAL buffer: falling behind its window
 * means "carry on from the window start", never "retune live", and never a silent loop.
 */
class Media3BehindWindowPolicyTest {
    private val behind = PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW

    @Test
    fun `behind the window on the local buffer resumes from the window start`() {
        assertEquals("first", Action.SEEK_TO_WINDOW_START, Media3BehindWindowPolicy.decide(behind, isLocalBuffer = true, recoveriesWithoutProgress = 0))
        assertEquals("third", Action.SEEK_TO_WINDOW_START, Media3BehindWindowPolicy.decide(behind, isLocalBuffer = true, recoveriesWithoutProgress = 2))
    }

    @Test
    fun `a buffer that keeps falling behind fails visibly instead of looping or retuning`() {
        assertEquals("4th", Action.REPORT, Media3BehindWindowPolicy.decide(behind, isLocalBuffer = true, recoveriesWithoutProgress = 3))
    }

    @Test
    fun `behind the window on a plain live source is reported so the session reconnects`() {
        assertEquals("live", Action.REPORT, Media3BehindWindowPolicy.decide(behind, isLocalBuffer = false, recoveriesWithoutProgress = 0))
    }

    @Test
    fun `other errors on the local buffer are reported`() {
        assertEquals("io", Action.REPORT, Media3BehindWindowPolicy.decide(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, isLocalBuffer = true, recoveriesWithoutProgress = 0))
    }

    @Test
    fun `the tracker counts recoveries and a READY resets the count`() {
        val tracker = Media3BehindWindowTracker()
        repeat(3) { assertEquals("recovery ${it + 1}", Action.SEEK_TO_WINDOW_START, tracker.onError(behind, true)) }
        assertEquals("4th without playing", Action.REPORT, tracker.onError(behind, true))
        tracker.onReady()
        assertEquals("after READY the budget is back", Action.SEEK_TO_WINDOW_START, tracker.onError(behind, true))
    }

    @Test
    fun `reported errors do not consume the recovery budget`() {
        val tracker = Media3BehindWindowTracker()
        repeat(5) { assertEquals(Action.REPORT, tracker.onError(behind, false)) }
        assertEquals("live-source errors did not count", Action.SEEK_TO_WINDOW_START, tracker.onError(behind, true))
    }
}
