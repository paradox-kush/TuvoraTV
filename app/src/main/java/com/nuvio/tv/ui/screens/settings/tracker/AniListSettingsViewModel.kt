package com.nuvio.tv.ui.screens.settings.tracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.AniListAuthDataStore
import com.nuvio.tv.data.local.AniListSettingsDataStore
import com.nuvio.tv.data.repository.AniListAuthService
import com.nuvio.tv.data.repository.TrackerPhoneLoginPoll
import com.nuvio.tv.domain.model.TrackerListStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/** AniList equivalent of [MalSettingsViewModel]. See its kdoc for rationale. */
@HiltViewModel
class AniListSettingsViewModel @Inject constructor(
    private val authService: AniListAuthService,
    private val authStore: AniListAuthDataStore,
    private val settings: AniListSettingsDataStore,
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        TrackerSettingsUiState(
            serviceName = "AniList",
            availableStatuses = TrackerListStatus.values().toList()
        )
    )
    val uiState: StateFlow<TrackerSettingsUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        combine(authStore.state, settings.settings) { auth, cfg ->
            _uiState.value.copy(
                connection = if (auth.isAuthenticated) {
                    TrackerSettingsUiState.Connection.Connected(
                        username = auth.username,
                        expiresAtEpochMs = auth.expiresAtEpochMs
                    )
                } else if (_uiState.value.isConnecting) {
                    _uiState.value.connection
                } else {
                    TrackerSettingsUiState.Connection.Disconnected
                },
                sendProgressEnabled = cfg.sendProgress,
                enabledStatuses = cfg.enabledStatuses
            )
        }.onEach { _uiState.value = it }.launchIn(viewModelScope)
    }

    fun onConnect() {
        if (_uiState.value.isConnecting) return
        viewModelScope.launch {
            authManager.ensureQrSessionAuthenticated().onFailure { err ->
                _uiState.value = _uiState.value.copy(errorMessage = err.message ?: "Supabase auth failed")
                return@launch
            }
            authService.startPhoneLogin().onSuccess { challenge ->
                _uiState.value = _uiState.value.copy(
                    connection = TrackerSettingsUiState.Connection.AwaitingPhone(
                        code = challenge.code,
                        webUrl = challenge.webUrl,
                        expiresAtEpochMs = challenge.expiresAtEpochMs
                    ),
                    errorMessage = null
                )
                startPolling(challenge.pollIntervalSeconds, challenge.expiresAtEpochMs)
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(errorMessage = err.message ?: "Failed to start login")
            }
        }
    }

    fun onCancelConnect() {
        pollJob?.cancel()
        pollJob = null
        _uiState.value = _uiState.value.copy(connection = TrackerSettingsUiState.Connection.Disconnected)
    }

    fun onDisconnect() {
        viewModelScope.launch {
            authService.revokeAndLogout()
            _uiState.value = _uiState.value.copy(transientMessage = "Signed out of AniList")
        }
    }

    fun onSendProgressToggled(enabled: Boolean) {
        viewModelScope.launch { settings.setSendProgress(enabled) }
    }

    fun onStatusToggled(status: TrackerListStatus, enabled: Boolean) {
        viewModelScope.launch { settings.setStatusEnabled(status, enabled) }
    }

    fun onDismissMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null, transientMessage = null)
    }

    // --- Debug-only local auth --- //

    fun debugBuildAuthorizeUrl(): String = authService.buildLocalDebugAuthorizeUrl()

    fun debugSaveToken(token: String) {
        viewModelScope.launch {
            authService.saveLocalDebugToken(token)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        transientMessage = "AniList token saved",
                        errorMessage = null
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(errorMessage = err.message ?: "Invalid token")
                }
        }
    }

    private fun startPolling(intervalSeconds: Int, expiresAtEpochMs: Long) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val interval = intervalSeconds.coerceAtLeast(2)
            while (System.currentTimeMillis() < expiresAtEpochMs) {
                delay(interval * 1000L)
                when (val result = authService.pollPhoneLogin()) {
                    is TrackerPhoneLoginPoll.Success -> {
                        _uiState.value = _uiState.value.copy(
                            transientMessage = "Signed in as ${result.username.orEmpty()}"
                        )
                        return@launch
                    }
                    is TrackerPhoneLoginPoll.Expired -> {
                        _uiState.value = _uiState.value.copy(
                            connection = TrackerSettingsUiState.Connection.Disconnected,
                            errorMessage = "Login code expired, try again"
                        )
                        return@launch
                    }
                    is TrackerPhoneLoginPoll.Error -> { /* keep polling */ }
                    TrackerPhoneLoginPoll.Pending -> { /* keep polling */ }
                }
                if (!authStore.state.first().isAuthenticated &&
                    _uiState.value.connection !is TrackerSettingsUiState.Connection.AwaitingPhone) {
                    return@launch
                }
            }
            _uiState.value = _uiState.value.copy(
                connection = TrackerSettingsUiState.Connection.Disconnected,
                errorMessage = "Login timed out"
            )
        }
    }
}
