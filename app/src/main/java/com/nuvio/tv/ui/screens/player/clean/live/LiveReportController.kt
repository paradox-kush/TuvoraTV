package com.nuvio.tv.ui.screens.player.clean.live

import com.nuvio.tv.core.telemetry.ReportCategory
import com.nuvio.tv.core.telemetry.ReportResult
import com.nuvio.tv.core.telemetry.ReportSurface
import com.nuvio.tv.core.telemetry.TelemetryPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Telemetry v2: owns the live player's one-press report. Lives in the view model's scope, so a send
 * outlives no player and a press can't be dropped by recomposition.
 *
 *  - "Sending…" is set synchronously on the press, so it shows in the next frame;
 *  - a press while sending does nothing (a double OK can't double-send);
 *  - the send has a deadline, so it always ends in a result;
 *  - the result clears after [LiveReportPolicy.RESULT_VISIBLE_MS], or at once on [clear]
 *    (the error went away, or the viewer zapped), and a late answer can't bring it back.
 */
internal class LiveReportController(
    private val telemetry: TelemetryPort,
    private val scope: CoroutineScope,
    /** Off Main: the port flushes the analytics queue. */
    private val workDispatcher: CoroutineDispatcher,
    private val resultVisibleMs: Long = LiveReportPolicy.RESULT_VISIBLE_MS,
    private val sendDeadlineMs: Long = LiveReportPolicy.SEND_DEADLINE_MS,
) {
    private val mutableState = MutableStateFlow<LiveReportState>(LiveReportState.Idle)
    val state: StateFlow<LiveReportState> = mutableState.asStateFlow()

    private val lock = Any()
    private var job: Job? = null

    /** True when this press started a send; false when one is already in flight. */
    fun send(category: ReportCategory, failureCode: String?): Boolean {
        synchronized(lock) {
            if (mutableState.value == LiveReportState.Sending) return false
            job?.cancel()
            mutableState.value = LiveReportState.Sending
            job = scope.launch {
                val result = try {
                    withTimeoutOrNull(sendDeadlineMs) {
                        withContext(workDispatcher) {
                            telemetry.sendUserReport(category, ReportSurface.LIVE, failureCode)
                        }
                    } ?: ReportResult.Failed
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    ReportResult.Failed
                }
                // Under the lock: a clear() racing this answer wins, so a stale result never shows.
                synchronized(lock) {
                    ensureActive()
                    mutableState.value = LiveReportPolicy.stateFor(result)
                }
                delay(resultVisibleMs)
                synchronized(lock) {
                    ensureActive()
                    mutableState.value = LiveReportState.Idle
                }
            }
            return true
        }
    }

    /** The error cleared or the channel changed: nothing about the old report stays on screen. */
    fun clear() {
        synchronized(lock) {
            job?.cancel()
            job = null
            mutableState.value = LiveReportState.Idle
        }
    }
}
