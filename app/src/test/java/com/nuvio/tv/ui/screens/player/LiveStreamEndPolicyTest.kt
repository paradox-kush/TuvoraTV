package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.player.mpv.MpvEndFileReason
import com.nuvio.tv.ui.screens.player.LiveStreamEndPolicy.Action
import com.nuvio.tv.ui.screens.player.LiveStreamEndPolicy.Bookkeeping
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression (RCA 2026-10-10): a live channel whose stream ended before a single frame rendered
 * went into the live reconnect ladder instead of the startup-failure path. `onLiveStreamEnded`
 * armed the freeze watcher with nothing ever played, and since the live ladder retries
 * indefinitely — and each pre-render end re-armed it, resetting the attempt count and backoff —
 * a dead channel spun "Reconnecting…" forever and never showed its error + Retry.
 *
 * "Playback started" means a real first frame (or position advance — TV's `hasRenderedFirstFrame`),
 * never an end event and never a bare isPlaying flag.
 */
class LiveStreamEndPolicyTest {

    private fun decide(
        reason: MpvEndFileReason,
        isLiveFeed: Boolean = true,
        hasRenderedFirstFrame: Boolean = false,
        liveRecoveryInFlight: Boolean = false,
    ) = LiveStreamEndPolicy.onMpvEndFile(reason, isLiveFeed, hasRenderedFirstFrame, liveRecoveryInFlight)

    @Test
    fun `live EOF before anything played fails startup instead of reconnecting forever`() {
        assertEquals(
            "a channel that never rendered must surface its startup error, not enter the endless live ladder",
            Action.FAIL_PLAYBACK,
            decide(MpvEndFileReason.EOF, hasRenderedFirstFrame = false, liveRecoveryInFlight = false),
        )
    }

    @Test
    fun `live EOF after playback started reconnects`() {
        assertEquals(
            "a live feed that played and then dropped is a freeze to recover",
            Action.RECONNECT_LIVE,
            decide(MpvEndFileReason.EOF, hasRenderedFirstFrame = true),
        )
    }

    @Test
    fun `live EOF from a reconnect rebuild stays on the ladder`() {
        assertEquals(
            "the channel already played this dwell; its rebuilt player ending is a failed attempt, not a startup failure",
            Action.RECONNECT_LIVE,
            decide(MpvEndFileReason.EOF, hasRenderedFirstFrame = false, liveRecoveryInFlight = true),
        )
    }

    @Test
    fun `live ERROR keeps its first-frame gate`() {
        assertEquals(
            "pre-first-frame error goes to the startup path",
            Action.FAIL_PLAYBACK,
            decide(MpvEndFileReason.ERROR, hasRenderedFirstFrame = false),
        )
        assertEquals(
            "post-first-frame error reconnects",
            Action.RECONNECT_LIVE,
            decide(MpvEndFileReason.ERROR, hasRenderedFirstFrame = true),
        )
    }

    @Test
    fun `vod end-file behaviour is unchanged`() {
        for (rendered in listOf(true, false)) {
            assertEquals(
                "VOD EOF is owned by the progress loop",
                Action.IGNORE,
                decide(MpvEndFileReason.EOF, isLiveFeed = false, hasRenderedFirstFrame = rendered),
            )
            assertEquals(
                "VOD error takes the ordinary failure path",
                Action.FAIL_PLAYBACK,
                decide(MpvEndFileReason.ERROR, isLiveFeed = false, hasRenderedFirstFrame = rendered),
            )
        }
    }

    @Test
    fun `zap teardown and redirect ends are always ignored`() {
        val benign = listOf(
            MpvEndFileReason.STOP,
            MpvEndFileReason.QUIT,
            MpvEndFileReason.REDIRECT,
            MpvEndFileReason.UNKNOWN,
        )
        for (reason in benign) {
            for (live in listOf(true, false)) {
                for (rendered in listOf(true, false)) {
                    for (inFlight in listOf(true, false)) {
                        assertEquals(
                            "$reason live=$live rendered=$rendered inFlight=$inFlight",
                            Action.IGNORE,
                            decide(reason, live, rendered, inFlight),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `an end before the first frame never arms the freeze watcher`() {
        for (freezeOpen in listOf(true, false)) {
            assertEquals(
                "arming resets the attempt budget and backoff, so an unrendered end must spend an attempt instead",
                Bookkeeping.END_UNRENDERED_ATTEMPT,
                LiveStreamEndPolicy.bookkeepingOnLiveEnd(hasRenderedFirstFrame = false, isFreezeOpen = freezeOpen),
            )
        }
    }

    @Test
    fun `an end after playback started arms a new incident unless one is open`() {
        assertEquals(
            "fresh incident after healthy playback",
            Bookkeeping.ARM_WATCHER,
            LiveStreamEndPolicy.bookkeepingOnLiveEnd(hasRenderedFirstFrame = true, isFreezeOpen = false),
        )
        assertEquals(
            "an open freeze keeps its record and attempt count",
            Bookkeeping.KEEP_INCIDENT,
            LiveStreamEndPolicy.bookkeepingOnLiveEnd(hasRenderedFirstFrame = true, isFreezeOpen = true),
        )
    }
}
