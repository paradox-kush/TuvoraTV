package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DebridFormatterConfigServer
import com.nuvio.tv.core.server.DebridFormatterSettings
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.domain.model.DebridSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebridSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DebridSettingsDataStore,
    private val torboxApi: TorboxApi,
    private val realDebridApi: RealDebridApi
) : ViewModel() {
    private var formatterServer: DebridFormatterConfigServer? = null

    private val _uiState = MutableStateFlow(DebridSettingsUiState())
    val uiState: StateFlow<DebridSettingsUiState> = _uiState.asStateFlow()

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    private val _validationError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val validationError: SharedFlow<String> = _validationError.asSharedFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update { it.fromSettings(settings) }
            }
        }
    }

    fun onEvent(event: DebridSettingsEvent) {
        when (event) {
            is DebridSettingsEvent.ToggleEnabled -> update { dataStore.setEnabled(event.enabled) }
        }
    }

    fun startFormatterQrMode() {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _uiState.update { it.copy(serverError = context.getString(R.string.error_network_required)) }
            return
        }
        stopFormatterServer()
        formatterServer = DebridFormatterConfigServer.startOnAvailablePort(
            currentSettingsProvider = {
                val state = _uiState.value
                DebridFormatterSettings(
                    nameTemplate = state.streamNameTemplate,
                    descriptionTemplate = state.streamDescriptionTemplate
                )
            },
            onSettingsChanged = { settings ->
                viewModelScope.launch {
                    dataStore.setStreamTemplates(
                        nameTemplate = settings.nameTemplate,
                        descriptionTemplate = settings.descriptionTemplate
                    )
                }
            }
        )
        val server = formatterServer
        if (server == null) {
            _uiState.update { it.copy(serverError = context.getString(R.string.error_server_ports_unavailable)) }
            return
        }
        val url = "http://$ip:${server.listeningPort}"
        _uiState.update {
            it.copy(
                isFormatterQrModeActive = true,
                formatterQrCodeBitmap = QrCodeGenerator.generate(url, 512),
                formatterServerUrl = url,
                serverError = null
            )
        }
    }

    fun stopFormatterQrMode() {
        stopFormatterServer()
        _uiState.update {
            it.copy(
                isFormatterQrModeActive = false,
                formatterQrCodeBitmap = null,
                formatterServerUrl = null
            )
        }
    }

    fun resetFormatterTemplates() {
        update { dataStore.resetStreamTemplates() }
    }

    fun validateAndSaveTorboxApiKey(value: String, onSuccess: () -> Unit) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            viewModelScope.launch { dataStore.setTorboxApiKey("") }
            onSuccess()
            return
        }
        viewModelScope.launch {
            _validating.value = true
            val valid = try {
                val response = torboxApi.getUser("Bearer $trimmed")
                response.body()?.close()
                response.errorBody()?.close()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
            _validating.value = false
            if (valid) {
                dataStore.setTorboxApiKey(trimmed)
                onSuccess()
            } else {
                _validationError.tryEmit(context.getString(R.string.debrid_invalid_torbox_api_key))
            }
        }
    }

    fun validateAndSaveRealDebridApiKey(value: String, onSuccess: () -> Unit) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            viewModelScope.launch { dataStore.setRealDebridApiKey("") }
            onSuccess()
            return
        }
        viewModelScope.launch {
            _validating.value = true
            val valid = try {
                val response = realDebridApi.getUser("Bearer $trimmed")
                response.body()?.close()
                response.errorBody()?.close()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
            _validating.value = false
            if (valid) {
                dataStore.setRealDebridApiKey(trimmed)
                onSuccess()
            } else {
                _validationError.tryEmit(context.getString(R.string.debrid_invalid_real_debrid_api_key))
            }
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }

    private fun stopFormatterServer() {
        formatterServer?.stop()
        formatterServer = null
    }

    override fun onCleared() {
        stopFormatterServer()
        super.onCleared()
    }
}

data class DebridSettingsUiState(
    val enabled: Boolean = false,
    val torboxApiKey: String = "",
    val realDebridApiKey: String = "",
    val streamNameTemplate: String = "",
    val streamDescriptionTemplate: String = "",
    val isFormatterQrModeActive: Boolean = false,
    val formatterQrCodeBitmap: Bitmap? = null,
    val formatterServerUrl: String? = null,
    val serverError: String? = null
) {
    fun fromSettings(settings: DebridSettings): DebridSettingsUiState = copy(
        enabled = settings.enabled,
        torboxApiKey = settings.torboxApiKey,
        realDebridApiKey = settings.realDebridApiKey,
        streamNameTemplate = settings.streamNameTemplate,
        streamDescriptionTemplate = settings.streamDescriptionTemplate
    )
}

sealed class DebridSettingsEvent {
    data class ToggleEnabled(val enabled: Boolean) : DebridSettingsEvent()
}
