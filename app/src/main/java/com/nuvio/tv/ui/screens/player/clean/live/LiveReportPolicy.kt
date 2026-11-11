package com.nuvio.tv.ui.screens.player.clean.live

import com.nuvio.tv.core.contracts.LivePauseProblem
import com.nuvio.tv.core.telemetry.ReportCategory
import com.nuvio.tv.core.telemetry.ReportResult
import com.nuvio.tv.core.telemetry.SupportCodeGenerator
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.ui.LivePlaybackUiErrorCode

/**
 * Telemetry v2, live player "Send report": every decision without Compose or the network.
 *
 * One press sends. The error already on screen names the problem, so the viewer is never asked to
 * pick a category here (the picker lives in Settings, where there is no context).
 */
internal object LiveReportPolicy {

    /** How long a result stays in the overlay before it clears by itself. */
    const val RESULT_VISIBLE_MS = 8_000L

    /** The send's own deadline: a report that never answers ends as Failed, never "Sending…" forever. */
    const val SEND_DEADLINE_MS = 15_000L

    fun category(error: LivePlaybackUiErrorCode?, pauseProblem: LivePauseProblem? = null): ReportCategory = when (error) {
        is LivePlaybackUiErrorCode.PlaybackFailed -> when (error.reasonCode) {
            FailureCode.SURFACE_LOST,
            FailureCode.SURFACE_HOST_UNAVAILABLE,
            -> ReportCategory.BLACK_SCREEN
            FailureCode.NO_PROGRESS,
            FailureCode.LIVE_RECONNECT_EXHAUSTED,
            -> ReportCategory.FREEZES
            else -> ReportCategory.WONT_PLAY
        }
        is LivePlaybackUiErrorCode.StreamUnavailable,
        is LivePlaybackUiErrorCode.PreviewUnavailable,
        -> ReportCategory.WONT_PLAY
        null -> when (pauseProblem) {
            LivePauseProblem.STALLED -> ReportCategory.FREEZES
            else -> ReportCategory.OTHER
        }
    }

    /** An identifier (`playback_failed:NO_PROGRESS`), never a message, host or URL. */
    fun failureCode(error: LivePlaybackUiErrorCode?, pauseProblem: LivePauseProblem? = null): String? = when (error) {
        is LivePlaybackUiErrorCode.PlaybackFailed -> "playback_failed:${error.reasonCode.name}"
        is LivePlaybackUiErrorCode.StreamUnavailable -> "stream_unavailable:${error.reasonCode.name}"
        is LivePlaybackUiErrorCode.PreviewUnavailable -> "preview_unavailable:${error.reasonCode.name}"
        null -> pauseProblem?.let { "live_pause:${it.name}" }
    }

    /** Only [ReportResult.Sent] becomes a success; every other outcome says what happened. */
    fun stateFor(result: ReportResult): LiveReportState = when (result) {
        is ReportResult.Sent -> LiveReportState.Sent(SupportCodeGenerator.display(result.supportCode))
        ReportResult.ConsentOff -> LiveReportState.ConsentOff
        is ReportResult.RateLimited -> LiveReportState.RateLimited(result.retryInSec)
        ReportResult.Failed -> LiveReportState.Failed
    }
}

/** What the overlay shows next to "Send report". */
internal sealed interface LiveReportState {
    data object Idle : LiveReportState
    data object Sending : LiveReportState
    /** [displayCode] is already `XXXX-XXXX`. */
    data class Sent(val displayCode: String) : LiveReportState
    data object ConsentOff : LiveReportState
    data class RateLimited(val retryInSec: Int) : LiveReportState
    data object Failed : LiveReportState
}
