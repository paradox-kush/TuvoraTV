package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.telemetry.ReportCategory
import com.nuvio.tv.core.telemetry.ReportResult
import com.nuvio.tv.core.telemetry.ReportSurface
import com.nuvio.tv.core.telemetry.SupportCodeGenerator
import com.nuvio.tv.core.telemetry.TelemetryPort
import com.nuvio.tv.core.telemetry.TelemetryService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the Settings "Send a report" area shows. Every state has a focus target in the UI. */
sealed interface SettingsReportUiState {
    data object Idle : SettingsReportUiState
    /** The five-category chooser is open. */
    data object Choosing : SettingsReportUiState
    data object Sending : SettingsReportUiState
    data class Sent(val displayCode: String) : SettingsReportUiState
    data object ConsentOff : SettingsReportUiState
    data class RateLimited(val retryInSec: Int) : SettingsReportUiState
    data object Failed : SettingsReportUiState
}

/**
 * Pure state holder for the Settings report flow. One pick = one [TelemetryPort.sendUserReport]
 * call; a pick while not choosing (sending, showing a result) does nothing, so a double OK cannot
 * double-send. The wait is bounded so "Sending..." always ends in a terminal state.
 */
class SettingsReportFlow(
    private val port: TelemetryPort,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = SEND_TIMEOUT_MS,
) {
    private val _state = MutableStateFlow<SettingsReportUiState>(SettingsReportUiState.Idle)
    val state: StateFlow<SettingsReportUiState> = _state.asStateFlow()

    val displaySupportCode: String get() = SupportCodeGenerator.display(port.supportCode)

    /** Opens the chooser. Ignored while a send is in flight. */
    fun open() {
        if (_state.value !is SettingsReportUiState.Sending) _state.value = SettingsReportUiState.Choosing
    }

    /** Back / dismiss. Ignored while sending (the result is coming). */
    fun dismiss() {
        if (_state.value !is SettingsReportUiState.Sending) _state.value = SettingsReportUiState.Idle
    }

    fun pick(category: ReportCategory) {
        // Atomic Choosing -> Sending: a second pick sees Sending and returns.
        if (!_state.compareAndSet(SettingsReportUiState.Choosing, SettingsReportUiState.Sending)) return
        scope.launch {
            val result = try {
                withTimeoutOrNull(timeoutMs) {
                    port.sendUserReport(category, ReportSurface.SETTINGS, null)
                } ?: ReportResult.Failed
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ReportResult.Failed
            }
            _state.value = map(result)
        }
    }

    private fun map(result: ReportResult): SettingsReportUiState = when (result) {
        is ReportResult.Sent -> SettingsReportUiState.Sent(SupportCodeGenerator.display(result.supportCode))
        ReportResult.ConsentOff -> SettingsReportUiState.ConsentOff
        is ReportResult.RateLimited -> SettingsReportUiState.RateLimited(result.retryInSec)
        ReportResult.Failed -> SettingsReportUiState.Failed
    }

    companion object {
        const val SEND_TIMEOUT_MS = 20_000L
    }
}

/** About screen: read-only support code. */
@HiltViewModel
class SupportCodeViewModel @Inject constructor(
    telemetry: TelemetryService,
) : ViewModel() {
    val displayCode: String = SupportCodeGenerator.display(telemetry.supportCode)
}
