package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.RecordingCapabilities
import com.nuvio.tv.core.recordings.api.RecordingCatchUp
import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.internal.FireDecision
import com.nuvio.tv.core.recordings.internal.RecordingSchedulePolicy as P
import com.nuvio.tv.core.recordings.internal.RetryDecision
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class RecordingSchedulePolicyTest {

    // --- modeFor ---------------------------------------------------------------------------

    @Test
    fun `a programme inside the archive window is saved from catch-up`() {
        val ch = channel(catchUp = xtream(3))
        assertEquals(CaptureMode.CATCH_UP, P.modeFor(ch, NOW - DAY, NOW - DAY + HOUR, NOW, RecordingCapabilities.ANDROID))
    }

    @Test
    fun `a future programme on a catch-up channel is still saved from catch-up`() {
        val ch = channel(catchUp = xtream(3))
        assertEquals(CaptureMode.CATCH_UP, P.modeFor(ch, NOW + HOUR, NOW + 2 * HOUR, NOW, RecordingCapabilities.ANDROID))
    }

    @Test
    fun `a channel without catch-up records live where the platform can`() {
        assertEquals(CaptureMode.LIVE, P.modeFor(channel(), NOW + HOUR, NOW + 2 * HOUR, NOW, RecordingCapabilities.ANDROID))
        assertEquals(CaptureMode.LIVE, P.modeFor(channel(), NOW + HOUR, NOW + 2 * HOUR, NOW, RecordingCapabilities.DESKTOP))
    }

    @Test
    fun `a programme older than the archive window cannot be caught up and has already ended`() {
        val ch = channel(catchUp = xtream(1))
        assertNull(P.modeFor(ch, NOW - 3 * DAY, NOW - 3 * DAY + HOUR, NOW, RecordingCapabilities.ANDROID))
    }

    @Test
    fun `an airing programme older than the window falls to live`() {
        val ch = channel(catchUp = xtream(1))
        // Started 25 h ago and still on (a long event): outside a 1-day window with the safety hour.
        assertEquals(CaptureMode.LIVE, P.modeFor(ch, NOW - 25 * HOUR, NOW + HOUR, NOW, RecordingCapabilities.ANDROID))
    }

    @Test
    fun `a programme that has ended without catch-up cannot be recorded`() {
        assertNull(P.modeFor(channel(), NOW - 2 * HOUR, NOW - HOUR, NOW, RecordingCapabilities.ANDROID))
    }

    @Test
    fun `iPhone has no live capture so a channel without catch-up is not recordable`() {
        assertNull(P.modeFor(channel(), NOW + HOUR, NOW + 2 * HOUR, NOW, RecordingCapabilities.IPHONE))
        val ch = channel(catchUp = xtream(3))
        assertEquals(CaptureMode.CATCH_UP, P.modeFor(ch, NOW + HOUR, NOW + 2 * HOUR, NOW, RecordingCapabilities.IPHONE))
    }

    @Test
    fun `Apple TV can record nothing`() {
        val ch = channel(catchUp = xtream(3))
        assertNull(P.modeFor(ch, NOW + HOUR, NOW + 2 * HOUR, NOW, RecordingCapabilities.APPLE_TV))
    }

    @Test
    fun `catch-up days of zero means no catch-up`() {
        val ch = channel(catchUp = xtream(0))
        assertEquals(CaptureMode.LIVE, P.modeFor(ch, NOW + HOUR, NOW + 2 * HOUR, NOW, RecordingCapabilities.ANDROID))
    }

    // --- times -----------------------------------------------------------------------------

    @Test
    fun `a live capture fires pre-roll before the start and stops post-roll after the end`() {
        val s = schedule(mode = CaptureMode.LIVE, start = NOW + HOUR, end = NOW + 2 * HOUR, pre = 3 * MIN, post = 7 * MIN)
        assertEquals(NOW + HOUR - 3 * MIN, P.fireTimeMs(s))
        assertEquals(NOW + 2 * HOUR + 7 * MIN, P.liveStopAtMs(s))
    }

    @Test
    fun `a catch-up save fires after the end plus post-roll plus the provider delay`() {
        val s = schedule(mode = CaptureMode.CATCH_UP, start = NOW, end = NOW + HOUR, post = 5 * MIN)
        assertEquals(NOW + HOUR + 5 * MIN + P.CATCH_UP_SAVE_DELAY_MS, P.fireTimeMs(s))
    }

    @Test
    fun `a retry time wins over the computed fire time`() {
        val s = schedule(mode = CaptureMode.CATCH_UP, nextTry = NOW + 99)
        assertEquals(NOW + 99, P.fireTimeMs(s))
    }

    @Test
    fun `the catch-up window ends at start plus the days minus an hour`() {
        val s = schedule(mode = CaptureMode.CATCH_UP, start = NOW, ch = channel(catchUp = xtream(2)))
        assertEquals(NOW + 2 * DAY - HOUR, P.catchUpWindowEndMs(s))
    }

    @Test
    fun `the defaults are 2 min before 5 min after 10 min delay 30 min retry 3 attempts`() {
        assertEquals(2 * MIN, P.DEFAULT_PRE_MS)
        assertEquals(5 * MIN, P.DEFAULT_POST_MS)
        assertEquals(10 * MIN, P.CATCH_UP_SAVE_DELAY_MS)
        assertEquals(30 * MIN, P.RETRY_MS)
        assertEquals(3, P.MAX_ATTEMPTS)
        assertEquals(60_000L, P.OVERDUE_GRACE_MS)
        assertEquals(60_000L, P.EARLY_FIRE_TOLERANCE_MS)
    }

    // --- onFire: every branch ---------------------------------------------------------------

    @Test
    fun `a live alarm on time starts the capture until the stop time`() {
        val s = schedule(mode = CaptureMode.LIVE, start = NOW + HOUR, end = NOW + 2 * HOUR)
        val d = P.onFire(s, P.fireTimeMs(s))
        assertEquals(FireDecision.StartLive(P.liveStopAtMs(s)), d)
    }

    @Test
    fun `a live alarm that fires late but inside the slot still starts`() {
        val s = schedule(mode = CaptureMode.LIVE, start = NOW, end = NOW + HOUR)
        assertEquals(FireDecision.StartLive(P.liveStopAtMs(s)), P.onFire(s, NOW + 30 * MIN))
    }

    @Test
    fun `a live alarm that comes early beyond the tolerance is re-armed`() {
        val s = schedule(mode = CaptureMode.LIVE, start = NOW + HOUR)
        val d = P.onFire(s, P.fireTimeMs(s) - 10 * MIN)
        assertEquals(FireDecision.Rearm(P.fireTimeMs(s)), d)
    }

    @Test
    fun `a live alarm just inside the early tolerance starts`() {
        val s = schedule(mode = CaptureMode.LIVE, start = NOW + HOUR)
        assertIs<FireDecision.StartLive>(P.onFire(s, P.fireTimeMs(s) - P.EARLY_FIRE_TOLERANCE_MS))
    }

    @Test
    fun `a live alarm at or after the stop time is missed`() {
        val s = schedule(mode = CaptureMode.LIVE, start = NOW, end = NOW + HOUR)
        assertEquals(FireDecision.Missed, P.onFire(s, P.liveStopAtMs(s)))
        assertEquals(FireDecision.Missed, P.onFire(s, P.liveStopAtMs(s) + DAY))
    }

    @Test
    fun `a catch-up alarm after the save delay saves pre-roll to post-roll`() {
        val s = schedule(mode = CaptureMode.CATCH_UP, start = NOW - 2 * HOUR, end = NOW - HOUR)
        val d = P.onFire(s, NOW)
        assertEquals(FireDecision.StartCatchUpSave(s.programmeStartMs - s.preMs, s.programmeEndMs + s.postMs), d)
    }

    @Test
    fun `a catch-up alarm that comes early is re-armed`() {
        val s = schedule(mode = CaptureMode.CATCH_UP, start = NOW, end = NOW + HOUR)
        assertEquals(FireDecision.Rearm(P.fireTimeMs(s)), P.onFire(s, NOW + 30 * MIN))
    }

    @Test
    fun `a catch-up alarm after the provider window is expired`() {
        val s = schedule(mode = CaptureMode.CATCH_UP, start = NOW - 5 * DAY, end = NOW - 5 * DAY + HOUR, ch = channel(catchUp = xtream(3)))
        assertEquals(FireDecision.Expired, P.onFire(s, NOW))
    }

    @Test
    fun `a catch-up retry alarm fires at its retry time`() {
        val s = schedule(mode = CaptureMode.CATCH_UP, start = NOW - 2 * HOUR, end = NOW - HOUR, nextTry = NOW + 30 * MIN)
        assertEquals("too early for the retry", FireDecision.Rearm(NOW + 30 * MIN), P.onFire(s, NOW))
        assertIs<FireDecision.StartCatchUpSave>(P.onFire(s, NOW + 30 * MIN))
    }

    @Test
    fun `a catch-up schedule whose channel lost its catch-up is expired`() {
        val s = schedule(mode = CaptureMode.CATCH_UP, start = NOW - 2 * HOUR, end = NOW - HOUR, ch = channel(catchUp = null))
        assertEquals(FireDecision.Expired, P.onFire(s, NOW))
    }

    // --- afterFailure -----------------------------------------------------------------------

    private val cu = schedule(mode = CaptureMode.CATCH_UP, start = NOW - 2 * HOUR, end = NOW - HOUR, ch = channel(catchUp = xtream(3)))

    @Test
    fun `a first catch-up failure retries in 30 minutes using one attempt`() {
        val d = P.afterFailure(cu, RecordingProblem.PROVIDER_REFUSED, NOW, offline = false)
        val retry = assertIs<RetryDecision.RetryAt>(d).schedule
        assertEquals(1, retry.attempts)
        assertEquals(NOW + P.RETRY_MS, retry.nextTryMs)
    }

    @Test
    fun `the third failed attempt gives up`() {
        val s2 = cu.copy(attempts = 2)
        assertEquals(RetryDecision.GiveUp(RecordingProblem.PROVIDER_REFUSED), P.afterFailure(s2, RecordingProblem.PROVIDER_REFUSED, NOW, offline = false))
        // The second failure still retries.
        assertIs<RetryDecision.RetryAt>(P.afterFailure(cu.copy(attempts = 1), RecordingProblem.PROVIDER_REFUSED, NOW, offline = false))
    }

    @Test
    fun `offline does not consume an attempt`() {
        val retry = assertIs<RetryDecision.RetryAt>(P.afterFailure(cu.copy(attempts = 2), RecordingProblem.NETWORK, NOW, offline = true)).schedule
        assertEquals(2, retry.attempts)
        assertEquals(NOW + P.RETRY_MS, retry.nextTryMs)
    }

    @Test
    fun `offline retries stop once the provider window is over`() {
        val end = P.catchUpWindowEndMs(cu)
        assertIs<RetryDecision.RetryAt>(P.afterFailure(cu, RecordingProblem.NETWORK, end - P.RETRY_MS, offline = true))
        assertEquals(RetryDecision.GiveUp(RecordingProblem.NETWORK), P.afterFailure(cu, RecordingProblem.NETWORK, end - P.RETRY_MS + 1, offline = true))
        assertEquals(RetryDecision.GiveUp(RecordingProblem.NETWORK), P.afterFailure(cu, RecordingProblem.NETWORK, end + DAY, offline = true))
    }

    @Test
    fun `an online failure near the end of the window gives up instead of retrying past it`() {
        val end = P.catchUpWindowEndMs(cu)
        assertIs<RetryDecision.GiveUp>(P.afterFailure(cu, RecordingProblem.PROVIDER_REFUSED, end - 1, offline = false))
    }

    @Test
    fun `no space no catch-up and unsupported format are never retried`() {
        for (p in listOf(RecordingProblem.NO_SPACE, RecordingProblem.NO_CATCH_UP, RecordingProblem.UNSUPPORTED_FORMAT)) {
            assertEquals("online $p", RetryDecision.GiveUp(p), P.afterFailure(cu, p, NOW, offline = false))
            assertEquals("offline $p", RetryDecision.GiveUp(p), P.afterFailure(cu, p, NOW, offline = true))
        }
    }

    @Test
    fun `a cancel by the user or a missed slot is never retried`() {
        assertIs<RetryDecision.GiveUp>(P.afterFailure(cu, RecordingProblem.CANCELLED_BY_USER, NOW, offline = false))
        assertIs<RetryDecision.GiveUp>(P.afterFailure(cu, RecordingProblem.MISSED, NOW, offline = false))
    }

    @Test
    fun `a live capture retries soon while the slot lasts`() {
        val live = schedule(mode = CaptureMode.LIVE, start = NOW, end = NOW + HOUR)
        val retry = assertIs<RetryDecision.RetryAt>(P.afterFailure(live, RecordingProblem.NETWORK, NOW + 10 * MIN, offline = false)).schedule
        assertEquals(NOW + 10 * MIN + P.LIVE_RETRY_MS, retry.nextTryMs)
        assertEquals(1, retry.attempts)
    }

    @Test
    fun `a live capture never retries after the slot ended`() {
        val live = schedule(mode = CaptureMode.LIVE, start = NOW, end = NOW + HOUR)
        val stop = P.liveStopAtMs(live)
        assertEquals(RetryDecision.GiveUp(RecordingProblem.NETWORK), P.afterFailure(live, RecordingProblem.NETWORK, stop, offline = false))
        assertEquals(RetryDecision.GiveUp(RecordingProblem.NETWORK), P.afterFailure(live, RecordingProblem.NETWORK, stop + MIN, offline = true))
        // A retry that would land after the stop is not scheduled either.
        assertIs<RetryDecision.GiveUp>(P.afterFailure(live, RecordingProblem.NETWORK, stop - 1, offline = false))
    }

    @Test
    fun `the retry keeps every other field of the schedule`() {
        val s = cu.copy(title = "Keep me", seriesRuleId = "r9")
        val retry = assertIs<RetryDecision.RetryAt>(P.afterFailure(s, RecordingProblem.UNKNOWN, NOW, offline = false)).schedule
        assertEquals(s.copy(attempts = 1, nextTryMs = NOW + P.RETRY_MS), retry)
    }

    // --- clashes and duplicates ---------------------------------------------------------------

    @Test
    fun `overlapping live captures on the same source clash`() {
        val a = schedule("a", start = NOW + HOUR, end = NOW + 2 * HOUR, ch = channel("c1", "srcA"))
        val b = schedule("b", start = NOW + 90 * MIN, end = NOW + 3 * HOUR, ch = channel("c2", "srcA"))
        assertEquals(listOf(a), P.clashes(b, listOf(a)))
    }

    @Test
    fun `live captures on different sources do not clash`() {
        val a = schedule("a", start = NOW + HOUR, end = NOW + 2 * HOUR, ch = channel("c1", "srcA"))
        val b = schedule("b", start = NOW + HOUR, end = NOW + 2 * HOUR, ch = channel("c2", "srcB"))
        assertTrue(P.clashes(b, listOf(a)).isEmpty())
    }

    @Test
    fun `back to back live captures clash only through the padding`() {
        val a = schedule("a", start = NOW + HOUR, end = NOW + 2 * HOUR, ch = channel("c1", "s"))
        val b = schedule("b", start = NOW + 2 * HOUR, end = NOW + 3 * HOUR, ch = channel("c2", "s"))
        assertEquals("the 5 min post-roll runs into the next pre-roll", listOf(a), P.clashes(b, listOf(a)))
        val c = schedule("c", start = NOW + 3 * HOUR, end = NOW + 4 * HOUR, ch = channel("c2", "s"))
        assertTrue("an hour apart", P.clashes(c, listOf(a)).isEmpty())
    }

    @Test
    fun `catch-up saves never clash and are never clashed with`() {
        val live = schedule("a", mode = CaptureMode.LIVE, start = NOW + HOUR, end = NOW + 2 * HOUR)
        val save = schedule("b", mode = CaptureMode.CATCH_UP, start = NOW + HOUR, end = NOW + 2 * HOUR)
        assertTrue(P.clashes(save, listOf(live)).isEmpty())
        assertTrue(P.clashes(live, listOf(save)).isEmpty())
    }

    @Test
    fun `a schedule does not clash with itself`() {
        val a = schedule("a")
        assertTrue(P.clashes(a, listOf(a)).isEmpty())
    }

    @Test
    fun `the same channel and programme start is a duplicate`() {
        val a = schedule("a", start = NOW + HOUR)
        assertTrue(P.isDuplicate(schedule("other-id", start = NOW + HOUR), listOf(a)))
        assertFalse(P.isDuplicate(schedule("b", start = NOW + 2 * HOUR), listOf(a)))
        assertFalse(P.isDuplicate(schedule("c", start = NOW + HOUR, ch = channel("different")), listOf(a)))
    }
}
