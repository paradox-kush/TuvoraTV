package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.parseXtreamAccount
import com.nuvio.tv.core.iptv.xtreamAccountFromFields
import com.nuvio.tv.core.sync.XtreamAccountSyncService
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.XtreamLiveStore
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
    private val syncService: XtreamAccountSyncService,
    private val registry: XtreamItemRegistry,
    private val libraryPreferences: LibraryPreferences,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val liveStore: XtreamLiveStore
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

    /** Re-verify + replace an existing account from a pasted portal/M3U URL (playlist edit). */
    fun editFromUrl(old: XtreamAccount, input: String, onSuccess: () -> Unit) {
        verifyAndReplace(old, parseXtreamAccount(input, old.name), "Couldn't read a username & password from that URL", onSuccess)
    }

    /** Re-verify + replace an existing account from manually-edited fields (playlist edit). */
    fun editManual(old: XtreamAccount, serverUrl: String, username: String, password: String, name: String?, onSuccess: () -> Unit) {
        verifyAndReplace(
            old,
            xtreamAccountFromFields(serverUrl, username, password, name),
            "Enter a server URL, username and password",
            onSuccess
        )
    }

    /**
     * Verifies the edited credentials live, then swaps the account in place (keeping its
     * position + enabled flag) and re-runs the discovery cycle. Saved items (library,
     * watch progress, watched marks, live favorites/recents) follow the account when it's
     * still the same playlist; a completely different playlist purges them instead.
     */
    private fun verifyAndReplace(old: XtreamAccount, candidate: XtreamAccount?, parseError: String, onSuccess: () -> Unit) {
        if (candidate == null) {
            _uiState.update { it.copy(error = parseError) }
            return
        }
        val account = candidate.copy(enabled = old.enabled)
        viewModelScope.launch {
            _uiState.update { it.copy(isValidating = true, error = null) }
            val result = client.verify(account)
            _uiState.update { it.copy(isValidating = false) }
            result.onSuccess {
                store.replace(old.id, account)
                if (account.id != old.id) migrateSavedData(old, account)
                // Cached stream URLs embed the old server/creds; rebuild lazily on demand.
                registry.clear()
                syncService.triggerRemoteSync()
                onSuccess()
            }.onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "Could not reach the panel") }
            }
        }
    }

    /**
     * Same playlist (same server or same username, e.g. a panel that moved domains or
     * rotated creds) -> rewrite saved xtream content ids to the new account id. A completely
     * different playlist -> the old ids point at content that no longer exists, so drop them.
     */
    private suspend fun migrateSavedData(old: XtreamAccount, new: XtreamAccount) {
        val samePlaylist = old.username == new.username || old.baseUrl == new.baseUrl
        val oldPrefix = XtreamItemRegistry.accountPrefix(old.id)
        val newPrefix = if (samePlaylist) XtreamItemRegistry.accountPrefix(new.id) else null
        libraryPreferences.migrateIdPrefix(oldPrefix, newPrefix)
        watchProgressPreferences.migrateIdPrefix(oldPrefix, newPrefix)
        watchedItemsPreferences.migrateIdPrefix(oldPrefix, newPrefix)
        liveStore.migrateAccount(
            oldPrefix,
            transform = if (newPrefix == null) null else { ref ->
                val streamId = ref.id.substringAfterLast(':').toIntOrNull()
                ref.copy(
                    id = newPrefix + ref.id.removePrefix(oldPrefix),
                    streamUrl = streamId?.let { client.buildStreamUrl(new, "live", it) } ?: ref.streamUrl
                )
            }
        )
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { store.setEnabled(id, enabled); syncService.triggerRemoteSync() }
    }

    fun remove(id: String) {
        viewModelScope.launch { store.remove(id); syncService.triggerRemoteSync() }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
