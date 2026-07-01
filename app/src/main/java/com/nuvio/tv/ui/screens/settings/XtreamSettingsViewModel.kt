package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.iptv.parseXtreamAccount
import com.nuvio.tv.core.iptv.xtreamAccountFromFields
import com.nuvio.tv.core.sync.XtreamAccountSyncService
import com.nuvio.tv.data.local.XtreamAccountStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class XtreamSettingsUiState(
    val accounts: List<XtreamAccount> = emptyList(),
    val isValidating: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class XtreamSettingsViewModel @Inject constructor(
    private val store: XtreamAccountStore,
    private val client: XtreamClient,
    private val syncService: XtreamAccountSyncService
) : ViewModel() {

    private val _uiState = MutableStateFlow(XtreamSettingsUiState())
    val uiState: StateFlow<XtreamSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.accounts.collectLatest { accounts ->
                _uiState.update { it.copy(accounts = accounts) }
            }
        }
    }

    /** Parse a pasted portal/M3U URL, verify the credentials live, then persist. */
    fun addFromUrl(input: String, name: String?, onSuccess: () -> Unit) {
        verifyAndSave(parseXtreamAccount(input, name), "Couldn't read a username & password from that URL", onSuccess)
    }

    /** Add from manually-entered server URL + username + password. */
    fun addManual(serverUrl: String, username: String, password: String, name: String?, onSuccess: () -> Unit) {
        verifyAndSave(
            xtreamAccountFromFields(serverUrl, username, password, name),
            "Enter a server URL, username and password",
            onSuccess
        )
    }

    private fun verifyAndSave(account: XtreamAccount?, parseError: String, onSuccess: () -> Unit) {
        if (account == null) {
            _uiState.update { it.copy(error = parseError) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isValidating = true, error = null) }
            val result = client.verify(account)
            _uiState.update { it.copy(isValidating = false) }
            result.onSuccess {
                store.upsert(account)
                syncService.triggerRemoteSync()
                onSuccess()
            }.onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "Could not reach the panel") }
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { store.setEnabled(id, enabled); syncService.triggerRemoteSync() }
    }

    fun remove(id: String) {
        viewModelScope.launch { store.remove(id); syncService.triggerRemoteSync() }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
