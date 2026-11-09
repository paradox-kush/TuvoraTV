package com.nuvio.tv.ui.screens.player.clean.live

import com.nuvio.tv.core.contracts.LivePauseProblem
import com.nuvio.tv.core.telemetry.ReportCategory
import com.nuvio.tv.core.telemetry.ReportResult
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.PreviewUnavailableReason
import com.nuvio.tv.playback.core.StreamUnavailableReason
import com.nuvio.tv.playback.ui.LivePlaybackUiErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Telemetry v2: the live "Send report" names the problem from the error on screen; no picker. */
class LiveReportPolicyTest {

    private fun failed(code: FailureCode) = LivePlaybackUiErrorCode.PlaybackFailed(code)

    @Test
    fun `a playback failure is won't play`() {
        assertEquals("network", ReportCategory.WONT_PLAY, LiveReportPolicy.category(failed(FailureCode.NETWORK_TIMEOUT)))
        assertEquals("decoder", ReportCategory.WONT_PLAY, LiveReportPolicy.category(failed(FailureCode.VIDEO_DECODER_FAILED)))
        assertEquals("unknown", ReportCategory.WONT_PLAY, LiveReportPolicy.category(failed(FailureCode.UNKNOWN)))
        assertEquals(
            "stream unavailable", ReportCategory.WONT_PLAY,
            LiveReportPolicy.category(LivePlaybackUiErrorCode.StreamUnavailable(StreamUnavailableReason.REMOVED_OR_EXPIRED)),
        )
        assertEquals(
            "preview unavailable", ReportCategory.WONT_PLAY,
            LiveReportPolicy.category(LivePlaybackUiErrorCode.PreviewUnavailable(PreviewUnavailableReason.ALL_PREVIEW_GRAPHS_FAILED)),
        )
    }

    @Test
    fun `a lost surface is a black screen`() {
        assertEquals("surface lost", ReportCategory.BLACK_SCREEN, LiveReportPolicy.category(failed(FailureCode.SURFACE_LOST)))
    }

    @Test
    fun `no progress and a stalled buffer are freezes`() {
        assertEquals("no progress", ReportCategory.FREEZES, LiveReportPolicy.category(failed(FailureCode.NO_PROGRESS)))
        assertEquals("reconnects exhausted", ReportCategory.FREEZES, LiveReportPolicy.category(failed(FailureCode.LIVE_RECONNECT_EXHAUSTED)))
        assertEquals("live pause stalled", ReportCategory.FREEZES, LiveReportPolicy.category(null, LivePauseProblem.STALLED))
    }

    @Test
    fun `anything else is other`() {
        assertEquals("no error", ReportCategory.OTHER, LiveReportPolicy.category(null))
        assertEquals("live pause out of space", ReportCategory.OTHER, LiveReportPolicy.category(null, LivePauseProblem.NO_SPACE))
    }

    @Test
    fun `the failure code is an identifier, never a message`() {
        assertEquals("playback", "playback_failed:NO_PROGRESS", LiveReportPolicy.failureCode(failed(FailureCode.NO_PROGRESS)))
        assertEquals(
            "stream", "stream_unavailable:AUTHORIZATION",
            LiveReportPolicy.failureCode(LivePlaybackUiErrorCode.StreamUnavailable(StreamUnavailableReason.AUTHORIZATION)),
        )
        assertEquals("live pause", "live_pause:TIMED_OUT", LiveReportPolicy.failureCode(null, LivePauseProblem.TIMED_OUT))
        assertNull("nothing on screen", LiveReportPolicy.failureCode(null))
        val pattern = Regex("^[A-Za-z0-9_:-]+$")
        FailureCode.entries.forEach { code ->
            assertEquals("$code passes the trail's identifier rule", true, pattern.matches(LiveReportPolicy.failureCode(failed(code))!!))
        }
    }

    @Test
    fun `only a sent result is a success`() {
        assertEquals("sent shows the code in two chunks", LiveReportState.Sent("K7Q2-9MPX"), LiveReportPolicy.stateFor(ReportResult.Sent("K7Q29MPX")))
        assertEquals("consent off", LiveReportState.ConsentOff, LiveReportPolicy.stateFor(ReportResult.ConsentOff))
        assertEquals("rate limited", LiveReportState.RateLimited(90), LiveReportPolicy.stateFor(ReportResult.RateLimited(90)))
        assertEquals("failed", LiveReportState.Failed, LiveReportPolicy.stateFor(ReportResult.Failed))
    }
}
