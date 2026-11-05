package com.nuvio.tv.ui.screens.iptv.recordings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.recordings.api.RecordingRow
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.api.RecordingsLoadAttempt
import com.nuvio.tv.core.recordings.api.RecordingsPageModel
import com.nuvio.tv.core.recordings.api.RecordingsPagePolicy
import com.nuvio.tv.core.recordings.api.RecordingsPort
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecordingsUiState(
    /** False until the persisted state has been read: never drawn as "no recordings". */
    val loaded: Boolean = false,
    val attempt: RecordingsLoadAttempt = RecordingsLoadAttempt(startedAtMs = 0L),
    val page: RecordingsPageModel = RecordingsPageModel(emptyMap()),
    /** recording id -> `file://` URI, for DONE rows whose file is on disk. */
    val playable: Map<String, String> = emptyMap(),
    val exactAllowed: Boolean = true,
)

sealed interface RecordingsEvent {
    data class Play(val uri: String, val title: String) : RecordingsEvent

    /** An action threw; the row is still there. No detail (an exception message or host) reaches the screen. */
    data object ActionFailed : RecordingsEvent
}

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val port: RecordingsPort,
) : ViewModel() {

    private val attempt = MutableStateFlow(RecordingsLoadAttempt(startedAtMs = System.currentTimeMillis()))

    // File checks (size > 0) run off the main thread, and only when the state itself changed.
    private val playable: Flow<Map<String, String>> = port.state
        .map { state ->
            state.recordings.filter { it.status == RecordingStatus.DONE }
                .mapNotNull { r -> port.playableUri(r)?.let { r.id to it } }
                .toMap()
        }
        .distinctUntilChanged()

    // Bytes grow many times a second while a capture runs; the page only needs a steady tick.
    @OptIn(FlowPreview::class)
    private val progress = port.progress.sample(PROGRESS_TICK_MS).onStart { emit(port.progress.value) }

    val uiState: StateFlow<RecordingsUiState> = combine(port.state, progress, playable, attempt) { state, progress, playable, attempt ->
        RecordingsUiState(
            loaded = state.loaded,
            attempt = attempt,
            page = RecordingsPagePolicy.build(state, progress),
            playable = playable,
            exactAllowed = state.exactAllowed,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingsUiState(attempt = attempt.value))

    private val eventChannel = Channel<RecordingsEvent>(Channel.BUFFERED)
    val events: Flow<RecordingsEvent> = eventChannel.receiveAsFlow()

    init { load() }

    /** Reads the persisted recordings. Also what Retry runs; the screen ends the wait at the attempt's deadline. */
    fun load() {
        val now = System.currentTimeMillis()
        attempt.value = RecordingsLoadAttempt(startedAtMs = now)
        viewModelScope.launch {
            try {
                port.start(now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recordings load failed", e)
                attempt.value = attempt.value.copy(failed = true)
            }
        }
    }

    fun play(row: RecordingRow) {
        val uri = row.recordingId?.let { uiState.value.playable[it] } ?: return
        eventChannel.trySend(RecordingsEvent.Play(uri, listOfNotNull(row.title, row.episode).joinToString(" ")))
    }

    fun cancel(scheduleId: String) = act { port.cancel(scheduleId) }
    fun delete(recordingId: String) = act { port.deleteRecording(recordingId) }
    fun retry(recordingId: String) = act { port.retry(recordingId, System.currentTimeMillis()) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recordings action failed", e)
                eventChannel.trySend(RecordingsEvent.ActionFailed)
            }
        }
    }

    private companion object {
        const val TAG = "Recordings"
        const val PROGRESS_TICK_MS = 1_000L
    }
}
