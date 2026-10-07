package com.nuvio.tv.ui.screens.mediaserver

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.mediaserver.MediaServerRuntime
import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.flow.AddServerController
import com.nuvio.tv.core.mediaserver.flow.MediaServerListController
import com.nuvio.tv.core.mediaserver.flow.ServerRowModel
import com.nuvio.tv.core.mediaserver.flow.ServerStatus
import com.nuvio.tv.core.mediaserver.source.MediaServerLibraries
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The server list (Settings -> Media servers). Health is asked once per visit, never on a timer. */
@HiltViewModel
internal class MediaServersViewModel @Inject constructor(
    private val runtime: MediaServerRuntime,
) : ViewModel() {
    private val list = MediaServerListController(runtime.services, viewModelScope)

    val rows: StateFlow<List<ServerRowModel>> = combine(
        runtime.entryStore.entries,
        runtime.services.credentialVersion,
        runtime.services.expiredSessions,
        list.healthState,
        list.checkingState,
    ) { entries, _, expired, health, checking ->
        list.rows(entries, expired, health, checking)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The screen became visible: load the entries of the active profile and ask each signed-in server once. */
    fun onShown() {
        runtime.entryStore.ensureLoaded()
        list.checkOnce(runtime.entryStore.current())
    }
}

/** One server's screen. */
@HiltViewModel
internal class MediaServerDetailsViewModel @Inject constructor(
    private val runtime: MediaServerRuntime,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val key: String = savedState.get<String>("entryKey").orEmpty()

    data class UiState(
        val entry: MediaServerEntry? = null,
        val status: ServerStatus = ServerStatus.NEEDS_SIGN_IN,
        val libraries: LibrariesState = LibrariesState.Loading,
        val busy: Boolean = false,
        val removed: Boolean = false,
    )

    sealed interface LibrariesState {
        data object Loading : LibrariesState
        data object Failed : LibrariesState
        data class Loaded(val libraries: List<MediaServerLibraries.Library>) : LibrariesState
    }

    private val librariesState = MutableStateFlow<LibrariesState>(LibrariesState.Loading)
    private val busy = MutableStateFlow(false)
    private val removed = MutableStateFlow(false)

    val uiState: StateFlow<UiState> = combine(
        runtime.entryStore.entries, runtime.services.credentialVersion, runtime.services.expiredSessions, librariesState, busy,
    ) { entries, _, expired, libraries, isBusy ->
        val entry = entries.firstOrNull { it.key == key }
        UiState(
            entry = entry,
            status = entry?.let { runtime.entryStatus(it, expired) } ?: ServerStatus.NEEDS_SIGN_IN,
            libraries = libraries,
            busy = isBusy,
            removed = removed.value || entry == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Loads the server's browsable libraries once per visit (only when signed in; one request, no polling). */
    fun loadLibraries() {
        val entry = runtime.entryStore.entryByKey(key) ?: return
        val client = runtime.services.clientFor(entry)
        if (client == null) {
            librariesState.value = LibrariesState.Loaded(emptyList())
            return
        }
        librariesState.value = LibrariesState.Loading
        viewModelScope.launch {
            librariesState.value = try {
                LibrariesState.Loaded(MediaServerLibraries.browsable(client.views()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaServerException.Http) {
                if (e.isUnauthorized) runtime.services.onUnauthorized(entry.serverKey)
                LibrariesState.Failed
            } catch (e: MediaServerException) {
                LibrariesState.Failed
            }
        }
    }

    fun rename(name: String) { runtime.entryStore.entryByKey(key)?.let { runtime.management.rename(it, name) } }
    fun setEnabled(enabled: Boolean) { runtime.entryStore.entryByKey(key)?.let { runtime.management.setEnabled(it, enabled) } }
    fun setSyncAddress(sync: Boolean) { runtime.entryStore.entryByKey(key)?.let { runtime.management.setSyncAddress(it, sync) } }
    fun setHomeRow(row: MediaServerHomeRow, on: Boolean) { runtime.entryStore.entryByKey(key)?.let { runtime.management.setHomeRow(it, row, on) } }
    fun setHomeLibrary(library: MediaServerLibraries.Library, on: Boolean) {
        runtime.entryStore.entryByKey(key)?.let { runtime.management.setHomeLibrary(it, library.id, library.name, on) }
    }

    fun signOut() {
        val entry = runtime.entryStore.entryByKey(key) ?: return
        viewModelScope.launch {
            busy.value = true
            try { runtime.management.signOut(entry) } finally { busy.value = false }
        }
    }

    fun remove(purgeSavedData: Boolean) {
        val entry = runtime.entryStore.entryByKey(key) ?: return
        viewModelScope.launch {
            busy.value = true
            try {
                if (runtime.management.remove(entry, purgeSavedData)) removed.value = true
            } finally {
                busy.value = false
            }
        }
    }
}

/** Add a server / sign an existing entry in on this device. The state machine itself is [AddServerController]. */
@HiltViewModel
internal class MediaServerAddViewModel @Inject constructor(
    private val runtime: MediaServerRuntime,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val existing: MediaServerEntry? = savedState.get<String>("entryKey")?.takeIf { it.isNotBlank() }?.let { runtime.entryStore.entryByKey(it) }

    val controller = AddServerController(
        services = runtime.services,
        accounts = runtime.accounts,
        trust = runtime.trust,
        scope = viewModelScope,
        existing = existing,
        onSignedIn = { entry -> runtime.notifyChanged(entry) },
    )

    data class Finish(val name: String, val recentlyAdded: Boolean)

    private val finishing = MutableStateFlow(false)
    val isFinishing: StateFlow<Boolean> = finishing.asStateFlow()

    /** The "name this server" step after a NEW server signed in: apply the chosen name and the Home offer. */
    fun finish(entry: MediaServerEntry, name: String, recentlyAddedOnHome: Boolean, onDone: () -> Unit) {
        if (finishing.value) return
        finishing.update { true }
        val current = runtime.entryStore.entryByKey(entry.key) ?: entry
        if (name.isNotBlank() && name.trim() != current.name) runtime.management.rename(current, name)
        if (recentlyAddedOnHome) runtime.entryStore.entryByKey(entry.key)?.let { runtime.management.setHomeRow(it, MediaServerHomeRow.RECENTLY_ADDED, true) }
        finishing.value = false
        onDone()
    }

    val signingInExisting: Boolean get() = existing != null

    override fun onCleared() {
        controller.cancel()
        super.onCleared()
    }
}
