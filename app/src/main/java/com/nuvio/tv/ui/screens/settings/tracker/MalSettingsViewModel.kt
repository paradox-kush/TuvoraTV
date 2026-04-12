package com.nuvio.tv.ui.screens.settings.tracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.MalAuthDataStore
import com.nuvio.tv.data.local.MalSettingsDataStore
import com.nuvio.tv.data.repository.MalAuthService
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

/**
 * ViewModel for the MyAnimeList subscreen. Bridges [MalAuthService] /
 * [MalAuthDataStore] / [MalSettingsDataStore] into a single
 * [TrackerSettingsUiState] consumed by [TrackerSettingsContent].
 *
 * The three tracker ViewModels (MAL / AniList / Kitsu) deliberately share
 * the same shape so the subscreens differ only in their injections —
 * forking them would tempt drift on every future UI tweak.
 */
@HiltViewModel
class MalSettingsViewModel @Inject constructor(
    private val authService: MalAuthService,
    private val authStore: MalAuthDataStore,
    private val settings: MalSettingsDataStore,
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        TrackerSettingsUiState(
            serviceName = "MyAnimeList",
            availableStatuses = TrackerListStatus.values()
                .filter { it != TrackerListStatus.REWATCHING }
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
            // QR login RPCs require a Supabase session (anonymous is fine).
            authManager.ensureQrSessionAuthenticated().onFailure { err ->
                _uiState.value = _uiState.value.copy(errorMessage = err.message ?: "Supabase auth failed")
                return@launch
            }
            val result = authService.startPhoneLogin()
            result.onSuccess { challenge ->
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
            _uiState.value = _uiState.value.copy(transientMessage = "Signed out of MyAnimeList")
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

    /** Authorize URL to open in a browser (step 1 of local auth). */
    fun debugBuildAuthorizeUrl(): String =
        authService.beginLocalDebugAuth(MAL_DEBUG_REDIRECT_URI)

    /** Called after user pastes the `?code=…` value from the redirected URL. */
    fun debugCompleteAuth(code: String) {
        viewModelScope.launch {
            authService.completeLocalDebugAuth(code, MAL_DEBUG_REDIRECT_URI)
                .onSuccess { username ->
                    _uiState.value = _uiState.value.copy(
                        transientMessage = username?.let { "Signed in as $it" } ?: "Signed in to MyAnimeList",
                        errorMessage = null
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(errorMessage = err.message ?: "Token exchange failed")
                }
        }
    }

    companion object {
        /** Use a stable HTTPS URL MAL will accept — the page failing to load is fine. */
        const val MAL_DEBUG_REDIRECT_URI = "https://localhost/oauth/mal"
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
                    is TrackerPhoneLoginPoll.Error -> {
                        // Transient errors — keep polling until session expires.
                    }
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
