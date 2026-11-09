package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.telemetry.ReportCategory
import com.nuvio.tv.core.telemetry.ReportResult
import com.nuvio.tv.core.telemetry.ReportSurface
import com.nuvio.tv.core.telemetry.SupportCodeGenerator
import com.nuvio.tv.core.telemetry.TelemetryPort
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** What the VOD player's "report a problem" action shows. Driven only by [ReportResult]. */
sealed interface PlaybackReportState {
    data object Idle : PlaybackReportState
    data object Sending : PlaybackReportState
    /** [displayCode] is already formatted with [SupportCodeGenerator.display]. */
    data class Sent(val displayCode: String) : PlaybackReportState
    data object ConsentOff : PlaybackReportState
    data class RateLimited(val retryInSec: Int) : PlaybackReportState
    data object Failed : PlaybackReportState
}

/** A tap is accepted unless a send is in flight or already succeeded. */
val PlaybackReportState.canSubmit: Boolean
    get() = this !is PlaybackReportState.Sending && this !is PlaybackReportState.Sent

/** Pure decision: which of the five report categories fits what the player is showing. */
internal object PlaybackReportCategoryPolicy {
    private val blackScreenHints = listOf("black", "no video", "video render", "surface")
    private val stallHints = listOf("stall", "timed out", "timeout", "buffering", "frozen", "freez")
    private val cannotPlayHints = listOf("cannot", "can't", "unable", "failed", "unsupported", "not supported", "error")

    fun categoryFor(error: String?, loadingStalled: Boolean): ReportCategory {
        val text = error?.trim()?.lowercase().orEmpty()
        return when {
            text.isEmpty() -> if (loadingStalled) ReportCategory.FREEZES else ReportCategory.OTHER
            blackScreenHints.any { it in text } -> ReportCategory.BLACK_SCREEN
            stallHints.any { it in text } -> ReportCategory.FREEZES
            cannotPlayHints.any { it in text } -> ReportCategory.WONT_PLAY
            else -> ReportCategory.OTHER
        }
    }
}

/**
 * One tap = one [TelemetryPort.sendUserReport]. A tap while Sending or after Sent does nothing;
 * a failed / consent-off / rate-limited result may be retried. Success is shown only for
 * [ReportResult.Sent]. The wait is bounded so Sending always ends in a terminal state.
 */
class PlaybackReportSubmitter(
    private val port: TelemetryPort,
    private val timeoutMs: Long = SEND_TIMEOUT_MS,
) {
    private val state = AtomicReference<PlaybackReportState>(PlaybackReportState.Idle)

    val current: PlaybackReportState get() = state.get()

    /** Suspends until the result; [onState] gets Sending first, then the terminal state. */
    suspend fun submit(
        category: ReportCategory,
        failureCode: String?,
        onState: (PlaybackReportState) -> Unit,
    ) {
        while (true) {
            val cur = state.get()
            if (cur is PlaybackReportState.Sending || cur is PlaybackReportState.Sent) return
            if (state.compareAndSet(cur, PlaybackReportState.Sending)) break
        }
        onState(PlaybackReportState.Sending)
        val result = try {
            withTimeoutOrNull(timeoutMs) {
                port.sendUserReport(category, ReportSurface.VOD, failureCode)
            } ?: ReportResult.Failed
        } catch (e: CancellationException) {
            state.set(PlaybackReportState.Idle)
            throw e
        } catch (_: Exception) {
            ReportResult.Failed
        }
        val next = map(result)
        state.set(next)
        onState(next)
    }

    /** New stream / Retry: forget the previous result. No-op while a send is in flight. */
    fun reset(): Boolean {
        val cur = state.get()
        if (cur is PlaybackReportState.Sending) return false
        state.set(PlaybackReportState.Idle)
        return true
    }

    private fun map(result: ReportResult): PlaybackReportState = when (result) {
        is ReportResult.Sent -> PlaybackReportState.Sent(SupportCodeGenerator.display(result.supportCode))
        ReportResult.ConsentOff -> PlaybackReportState.ConsentOff
        is ReportResult.RateLimited -> PlaybackReportState.RateLimited(result.retryInSec)
        ReportResult.Failed -> PlaybackReportState.Failed
    }

    companion object {
        const val SEND_TIMEOUT_MS = 20_000L
    }
}
