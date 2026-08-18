package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for a live channel freezing in the guide's fullscreen preview with no recovery.
 *
 * Reproduced on an Onn 4K, 2026-08-18: after ~17 minutes the provider closed the socket, ExoPlayer
 * went to STATE_ENDED, AudioTrack stopped, and nothing else happened — no error was raised (so the
 * preview's only listener never fired), no freeze was reported, no re-tune was attempted. The
 * viewer was left on a frozen frame.
 *
 * The first test below is the one that regressed: the old preview had no notion of ENDED at all.
 *
 * NOTE: JUnit argument order is assertEquals(message, expected, actual) here — the opposite of
 * kotlin.test in the mobile/desktop twins. Do not regex-port between them.
 */
class GuidePreviewFreezePolicyTest {

    @Test
    fun `a live feed reaching ENDED is a dropped stream and must be re-tuned`() {
        assertTrue(
            "a live channel has no end — ENDED means the provider dropped us",
            GuidePreviewFreezePolicy.shouldRetune(
                playbackState = GuidePreviewFreezePolicy.STATE_ENDED,
                isLiveFeed = true,
                attemptsUsed = 0,
            )
        )
    }

    @Test
    fun `a live feed going IDLE is also a dropped stream`() {
        assertTrue(
            "IDLE without an error is the same silent death as ENDED",
            GuidePreviewFreezePolicy.shouldRetune(
                playbackState = GuidePreviewFreezePolicy.STATE_IDLE,
                isLiveFeed = true,
                attemptsUsed = 0,
            )
        )
    }

    @Test
    fun `healthy states never re-tune`() {
        assertFalse(
            "READY is playback working",
            GuidePreviewFreezePolicy.shouldRetune(
                GuidePreviewFreezePolicy.STATE_READY, isLiveFeed = true, attemptsUsed = 0
            )
        )
        assertFalse(
            "BUFFERING is a stream still arriving, not a dead one",
            GuidePreviewFreezePolicy.shouldRetune(
                GuidePreviewFreezePolicy.STATE_BUFFERING, isLiveFeed = true, attemptsUsed = 0
            )
        )
    }

    @Test
    fun `a recording is allowed to end`() {
        assertFalse(
            "catch-up really does finish — re-tuning it would restart the recording",
            GuidePreviewFreezePolicy.shouldRetune(
                GuidePreviewFreezePolicy.STATE_ENDED, isLiveFeed = false, attemptsUsed = 0
            )
        )
    }

    @Test
    fun `recovery is bounded so a dead channel cannot hammer the portal`() {
        assertTrue(
            "the second attempt is still allowed",
            GuidePreviewFreezePolicy.shouldRetune(
                GuidePreviewFreezePolicy.STATE_ENDED, isLiveFeed = true, attemptsUsed = 1
            )
        )
        assertFalse(
            "panels cap concurrent connections — stop after the budget is spent",
            GuidePreviewFreezePolicy.shouldRetune(
                GuidePreviewFreezePolicy.STATE_ENDED,
                isLiveFeed = true,
                attemptsUsed = GuidePreviewFreezePolicy.MAX_RECOVERY_ATTEMPTS,
            )
        )
    }

    @Test
    fun `the viewer is told once the automatic attempts are spent`() {
        assertFalse(
            "while recovery can still run, say nothing — it usually self-heals",
            GuidePreviewFreezePolicy.shouldSurfaceError(
                GuidePreviewFreezePolicy.STATE_ENDED, isLiveFeed = true, attemptsUsed = 0
            )
        )
        assertTrue(
            "a frozen frame with no message is the bug we are fixing",
            GuidePreviewFreezePolicy.shouldSurfaceError(
                GuidePreviewFreezePolicy.STATE_ENDED,
                isLiveFeed = true,
                attemptsUsed = GuidePreviewFreezePolicy.MAX_RECOVERY_ATTEMPTS,
            )
        )
    }
    @Test
    fun `a recovered channel gets its budget back`() {
        assertEquals(
            "a rendered frame proves recovery worked — the next death is a new incident",
            0,
            GuidePreviewFreezePolicy.attemptsAfterSuccess()
        )
        assertTrue(
            "so a channel that died, recovered, played and died again can recover again",
            GuidePreviewFreezePolicy.shouldRetune(
                GuidePreviewFreezePolicy.STATE_ENDED,
                isLiveFeed = true,
                attemptsUsed = GuidePreviewFreezePolicy.attemptsAfterSuccess(),
            )
        )
    }

    @Test
    fun `the reason names the signal, the duration and the recovery`() {
        val ended = GuidePreviewFreezePolicy.freezeReason(
            playbackState = GuidePreviewFreezePolicy.STATE_ENDED,
            playedMs = 200_000L,
            attemptsUsed = 2,
        )
        assertTrue("names the provider as the source: $ended", ended.contains("provider ended the stream"))
        assertTrue("says how long it lasted: $ended", ended.contains("3m 20s"))
        assertTrue("says what recovery was tried: $ended", ended.contains("reconnected 2 times"))

        val dropped = GuidePreviewFreezePolicy.freezeReason(
            playbackState = GuidePreviewFreezePolicy.STATE_IDLE,
            playedMs = 45_000L,
            attemptsUsed = 0,
        )
        assertTrue("IDLE is a dropped connection: $dropped", dropped.contains("connection dropped"))
        assertTrue("short durations stay in seconds: $dropped", dropped.contains("45s"))
    }

    @Test
    fun `a provider refusal during recovery is quoted verbatim`() {
        val reason = GuidePreviewFreezePolicy.freezeReason(
            playbackState = GuidePreviewFreezePolicy.STATE_ENDED,
            playedMs = 10_000L,
            attemptsUsed = 1,
            resolveError = "HTTP 429",
        )
        assertTrue(
            "the provider's own words are the most useful thing in a bug report: $reason",
            reason.contains("HTTP 429")
        )
    }

    @Test
    fun `durations read naturally`() {
        assertEquals("zero is not blank", "0s", GuidePreviewFreezePolicy.formatDuration(0L))
        assertEquals("sub-minute", "45s", GuidePreviewFreezePolicy.formatDuration(45_000L))
        assertEquals("exact minute keeps the seconds", "3m 0s", GuidePreviewFreezePolicy.formatDuration(180_000L))
    }

    @Test
    fun `the technical line never leaks the account credentials`() {
        // A real Xtream stream URL carries the account user and password in the PATH.
        val credentialed = "http://panel.example.com:8080/live/kush_user/s3cr3tP4ss/12345.ts"
        val host = GuidePreviewFreezePolicy.hostOf(credentialed)
        val line = GuidePreviewFreezePolicy.technicalDetail(
            container = "ts",
            host = host,
            playbackState = GuidePreviewFreezePolicy.STATE_ENDED,
            attemptsUsed = 1,
            appVersion = "0.8.1-beta",
        )
        assertFalse("username must never reach the screen: $line", line.contains("kush_user"))
        assertFalse("password must never reach the screen: $line", line.contains("s3cr3tP4ss"))
        assertFalse("the raw path must not be echoed: $line", line.contains("/live/"))
        assertTrue("but the panel host is what we need to triage: $line", line.contains("panel.example.com"))
    }

    @Test
    fun `userinfo in the authority is stripped too`() {
        assertEquals(
            "user:pass@host must reduce to the host",
            "panel.example.com",
            GuidePreviewFreezePolicy.hostOf("http://kush:hunter2@panel.example.com/live/1.ts")
        )
        assertEquals("no scheme is unparseable, not a guess", null, GuidePreviewFreezePolicy.hostOf("not a url"))
        assertEquals("blank is null", null, GuidePreviewFreezePolicy.hostOf(""))
    }

    @Test
    fun `the technical line names what we would go and look at`() {
        val line = GuidePreviewFreezePolicy.technicalDetail(
            container = "m3u8",
            host = "panel.example.com",
            playbackState = GuidePreviewFreezePolicy.STATE_IDLE,
            attemptsUsed = 2,
        )
        assertTrue("container identifies the lane: $line", line.contains("m3u8"))
        assertTrue("state code is unambiguous: $line", line.contains("IDLE(1)"))
        assertTrue("says whether recovery ran: $line", line.contains("retry 2/2"))
        assertTrue("names the engine: $line", line.contains("ExoPlayer"))
    }

}

/**
 * The policy names ExoPlayer's states as its own constants so it stays pure and framework-free.
 * That is only safe while the numbers actually agree — this pins them to media3 itself, so a
 * library change that renumbered them fails here instead of silently disabling freeze recovery.
 */
class GuidePreviewFreezePolicyStateConstantsTest {

    @Test
    fun `policy state constants match media3`() {
        assertEquals(
            "STATE_IDLE must match media3",
            androidx.media3.common.Player.STATE_IDLE,
            GuidePreviewFreezePolicy.STATE_IDLE
        )
        assertEquals(
            "STATE_BUFFERING must match media3",
            androidx.media3.common.Player.STATE_BUFFERING,
            GuidePreviewFreezePolicy.STATE_BUFFERING
        )
        assertEquals(
            "STATE_READY must match media3",
            androidx.media3.common.Player.STATE_READY,
            GuidePreviewFreezePolicy.STATE_READY
        )
        assertEquals(
            "STATE_ENDED must match media3",
            androidx.media3.common.Player.STATE_ENDED,
            GuidePreviewFreezePolicy.STATE_ENDED
        )
    }
}
