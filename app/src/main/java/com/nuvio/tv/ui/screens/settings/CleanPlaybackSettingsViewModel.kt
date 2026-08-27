package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.playback.settings.CleanPlaybackPreferences
import com.nuvio.tv.playback.settings.CleanPlaybackSettingField
import com.nuvio.tv.playback.settings.CleanPlaybackSettingsEditor
import com.nuvio.tv.playback.settings.CleanPlaybackSettingsPresentation
import com.nuvio.tv.playback.settings.CleanPlaybackSettingsPresenter
import com.nuvio.tv.playback.settings.PlaybackPreferenceGroup
import com.nuvio.tv.playback.settings.PlaybackPreferenceRepository
import com.nuvio.tv.playback.settings.PlaybackPreferenceSnapshot
import com.nuvio.tv.playback.settings.ResolvedPlaybackPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface CleanPlaybackSettingsResolutionSource {
    suspend fun resolve(profileId: String, requested: CleanPlaybackPreferences): ResolvedPlaybackPreferences
}

sealed interface CleanPlaybackSettingsUiState {
    data object Loading : CleanPlaybackSettingsUiState

    data class Content(
        val presentation: CleanPlaybackSettingsPresentation,
        val operationInProgress: Boolean = false,
        val notice: String? = null,
    ) : CleanPlaybackSettingsUiState

    data class Failed(
        val profileId: String?,
        val message: String,
    ) : CleanPlaybackSettingsUiState
}

/**
 * Profile-scoped settings state only. It persists requested intent and resolves presentation data;
 * it has no dependency on Media3, libmpv, PlaybackSession, or an engine factory.
 */
class CleanPlaybackSettingsViewModel(
    private val repository: PlaybackPreferenceRepository,
    activeProfileIds: Flow<Int>,
    private val resolutionSource: CleanPlaybackSettingsResolutionSource,
) : ViewModel() {
    private val operationMutex = Mutex()
    private val activeProfileId = MutableStateFlow<String?>(null)
    private val mutableUiState = MutableStateFlow<CleanPlaybackSettingsUiState>(CleanPlaybackSettingsUiState.Loading)

    val uiState: StateFlow<CleanPlaybackSettingsUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            activeProfileIds
                .map(Int::toString)
                .distinctUntilChanged()
                .collectLatest { profileId ->
                    activeProfileId.value = profileId
                    load(profileId)
                }
        }
    }

    fun retry() {
        val profileId = activeProfileId.value ?: return
        viewModelScope.launch { load(profileId) }
    }

    fun update(field: CleanPlaybackSettingField, encodedValue: String) {
        val content = mutableUiState.value as? CleanPlaybackSettingsUiState.Content ?: return
        val profileId = content.presentation.profileId
        if (content.presentation.readOnly) {
            mutableUiState.value = content.copy(notice = FUTURE_SCHEMA_NOTICE)
            return
        }
        viewModelScope.launch {
            runOperation(profileId) {
                val current = repository.load(profileId)
                val edit = CleanPlaybackSettingsEditor.apply(current.preferences, field, encodedValue)
                repository.updateGroup(profileId, edit.group) { edit.preferences }
            }
        }
    }

    fun reset(group: PlaybackPreferenceGroup) {
        val content = mutableUiState.value as? CleanPlaybackSettingsUiState.Content ?: return
        val profileId = content.presentation.profileId
        if (content.presentation.readOnly) {
            mutableUiState.value = content.copy(notice = FUTURE_SCHEMA_NOTICE)
            return
        }
        viewModelScope.launch {
            runOperation(profileId) { repository.resetGroup(profileId, group) }
        }
    }

    private suspend fun load(profileId: String) {
        operationMutex.withLock {
            if (activeProfileId.value != profileId) return
            mutableUiState.value = CleanPlaybackSettingsUiState.Loading
            publish(profileId, runCatching { repository.load(profileId) })
        }
    }

    private suspend fun runOperation(
        profileId: String,
        operation: suspend () -> PlaybackPreferenceSnapshot,
    ) {
        operationMutex.withLock {
            if (activeProfileId.value != profileId) return
            val previous = mutableUiState.value as? CleanPlaybackSettingsUiState.Content
            if (previous != null) mutableUiState.value = previous.copy(operationInProgress = true, notice = null)
            val result = runCatching { operation() }
            if (result.isFailure && previous != null && activeProfileId.value == profileId) {
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                mutableUiState.value = previous.copy(notice = error?.message ?: "Unable to update playback settings")
                return
            }
            publish(profileId, result)
        }
    }

    private suspend fun publish(
        profileId: String,
        snapshotResult: Result<PlaybackPreferenceSnapshot>,
    ) {
        if (activeProfileId.value != profileId) return
        try {
            val snapshot = snapshotResult.getOrThrow()
            val resolved = resolutionSource.resolve(profileId, snapshot.preferences)
            if (activeProfileId.value != profileId) return
            mutableUiState.value = CleanPlaybackSettingsUiState.Content(
                CleanPlaybackSettingsPresenter.present(profileId, snapshot, resolved),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (activeProfileId.value == profileId) {
                mutableUiState.value = CleanPlaybackSettingsUiState.Failed(
                    profileId = profileId,
                    message = error.message ?: "Unable to load playback settings",
                )
            }
        }
    }

    private companion object {
        const val FUTURE_SCHEMA_NOTICE = "These settings were created by a newer app version and are read-only."
    }
}
