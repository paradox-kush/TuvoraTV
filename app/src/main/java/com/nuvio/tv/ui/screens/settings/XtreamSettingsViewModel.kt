package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamAccountInfo
import com.nuvio.tv.core.iptv.XtreamCategory
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.parseXtreamAccount
import com.nuvio.tv.core.iptv.withPlaylistOptions
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
    val error: String? = null,
    /** "accountId|type" -> that type's category list (for the Content & Categories checklist). */
    val categoryLists: Map<String, List<XtreamCategory>> = emptyMap(),
    /** accountId -> "Active · 0/1 connections · Expires 2027-01-11" (lazily fetched, silent on failure). */
    val accountStatus: Map<String, String> = emptyMap()
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

    /** The shared "Add Playlist" options collected by the form (EPG override, DNS, auto-refresh). */
    data class PlaylistOptions(
        val epgUrl: String? = null,
        val dnsProvider: String = XtreamAccount.DNS_SYSTEM,
        val autoRefreshHours: Int = XtreamAccount.DEFAULT_AUTO_REFRESH_HOURS
    )

    /** Copies the form's shared playlist options onto a parsed/built account before verify+save. */
    private fun XtreamAccount.withOptions(options: PlaylistOptions): XtreamAccount =
        withPlaylistOptions(options.epgUrl, options.dnsProvider, options.autoRefreshHours)

    /** The form's shared options as currently persisted on an account (to prefill an edit). */
    private fun XtreamAccount.toOptions(): PlaylistOptions =
        PlaylistOptions(epgUrl = epgUrl, dnsProvider = dnsProvider, autoRefreshHours = autoRefreshHours)

    /** Parse a pasted portal/M3U URL, verify the credentials live, then persist (with form options). */
    fun addFromUrl(input: String, name: String?, options: PlaylistOptions = PlaylistOptions(), onSuccess: () -> Unit) {
        verifyAndSave(
            parseXtreamAccount(input, name)?.withOptions(options),
            "Couldn't read a username & password from that URL",
            onSuccess
        )
    }

    /** Add from manually-entered server URL + username + password (with form options). */
    fun addManual(
        serverUrl: String,
        username: String,
        password: String,
        name: String?,
        options: PlaylistOptions = PlaylistOptions(),
        onSuccess: () -> Unit
    ) {
        verifyAndSave(
            xtreamAccountFromFields(serverUrl, username, password, name)?.withOptions(options),
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
    fun editFromUrl(old: XtreamAccount, input: String, options: PlaylistOptions = old.toOptions(), onSuccess: () -> Unit) {
        verifyAndReplace(old, parseXtreamAccount(input, old.name)?.withOptions(options), "Couldn't read a username & password from that URL", onSuccess)
    }

    /** Re-verify + replace an existing account from manually-edited fields (playlist edit). */
    fun editManual(
        old: XtreamAccount,
        serverUrl: String,
        username: String,
        password: String,
        name: String?,
        options: PlaylistOptions = old.toOptions(),
        onSuccess: () -> Unit
    ) {
        verifyAndReplace(
            old,
            xtreamAccountFromFields(serverUrl, username, password, name)?.withOptions(options),
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
        // Credential/URL edits keep the content selections (toggles, category picks) — those aren't
        // in this form. The shared options (epg/dns/refresh) already ride on `candidate` from the
        // form (withOptions), so DON'T overwrite them from `old`, or an edit couldn't change them.
        val account = candidate.copy(
            enabled = old.enabled,
            contentTypes = old.contentTypes,
            categorySelections = old.categorySelections
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isValidating = true, error = null) }
            val result = client.verify(account)
            _uiState.update { it.copy(isValidating = false) }
            result.onSuccess {
                store.replace(old.id, account)
                if (account.id != old.id) migrateSavedData(old, account)
                // Cached stream URLs embed the old server/creds; rebuild lazily on demand.
                registry.clear()
                // A renewed/edited account must not keep showing a stale "Expired" status or
                // category lists fetched under the old creds — evict both ids' caches.
                evictAccountCaches(old.id, account.id)
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

    // --- Content & Categories (playlist manager P1) --------------------------

    private val categoryRequests = mutableSetOf<String>()   // "accountId|type" in flight or done
    private val statusRequests = mutableSetOf<String>()     // accountId in flight or done

    /** Drop cached status lines + category lists for these account ids so they refetch. */
    private fun evictAccountCaches(vararg accountIds: String) {
        val ids = accountIds.toSet()
        val typeKeys = ids.flatMap { id ->
            listOf(XtreamAccount.TYPE_LIVE, XtreamAccount.TYPE_MOVIES, XtreamAccount.TYPE_SERIES).map { "$id|$it" }
        }.toSet()
        statusRequests.removeAll(ids)
        categoryRequests.removeAll(typeKeys)
        _uiState.update { it.copy(accountStatus = it.accountStatus - ids, categoryLists = it.categoryLists - typeKeys) }
    }

    /** Fetch the three category lists for the Content & Categories dialog (cached, silent on failure). */
    fun loadCategoryLists(account: XtreamAccount) {
        for (type in listOf(XtreamAccount.TYPE_LIVE, XtreamAccount.TYPE_MOVIES, XtreamAccount.TYPE_SERIES)) {
            val key = "${account.id}|$type"
            if (!categoryRequests.add(key)) continue
            viewModelScope.launch {
                val result = when (type) {
                    XtreamAccount.TYPE_LIVE -> client.liveCategories(account)
                    XtreamAccount.TYPE_MOVIES -> client.vodCategories(account)
                    else -> client.seriesCategories(account)
                }
                result
                    .onSuccess { cats -> _uiState.update { it.copy(categoryLists = it.categoryLists + (key to cats)) } }
                    .onFailure { categoryRequests.remove(key) }   // allow a retry on next dialog open
            }
        }
    }

    /** Toggle a content type on/off. Option-only edit: no credential re-verification. */
    fun setContentTypeEnabled(accountId: String, type: String, enabled: Boolean) {
        updateAccount(accountId) { acc ->
            acc.copy(contentTypes = if (enabled) acc.contentTypes + type else acc.contentTypes - type)
        }
    }

    /** Absolute selection write: Select All (null = all incl. future) / Deselect All (empty). */
    fun setCategorySelection(accountId: String, type: String, selection: List<String>?) {
        updateAccount(accountId) { acc ->
            acc.copy(categorySelections = acc.categorySelections.withType(type, selection))
        }
    }

    /**
     * Toggle ONE category. The dialog sends the operation (not a whole recomputed list from its
     * possibly-stale composed state); null -> full-list materialization happens inside the store
     * transform against the LATEST selection, using the cached category list for [type]. So a
     * toggle racing "Deselect All" yields exactly the toggled id, not a resurrected full list.
     */
    fun toggleCategory(accountId: String, type: String, categoryId: String, isChecked: Boolean) {
        val fullList = _uiState.value.categoryLists["$accountId|$type"].orEmpty().map { it.id }
        updateAccount(accountId) { acc ->
            val current = acc.categorySelections.forType(type) ?: fullList
            val next = if (isChecked) (current - categoryId) + categoryId else current - categoryId
            acc.copy(categorySelections = acc.categorySelections.withType(type, next))
        }
    }

    /** Field-level transform against the store's latest state (see XtreamAccountStore.update). */
    private fun updateAccount(accountId: String, transform: (XtreamAccount) -> XtreamAccount) {
        viewModelScope.launch {
            store.update(accountId, transform)
            syncService.triggerRemoteSync()
        }
    }

    /** Lazily fetch the account-status line for a settings row. Non-blocking, cached, silent on failure. */
    fun ensureAccountStatus(account: XtreamAccount) {
        if (!statusRequests.add(account.id)) return
        viewModelScope.launch {
            client.accountInfo(account)
                .onSuccess { info ->
                    info.toStatusLine()?.let { line ->
                        _uiState.update { it.copy(accountStatus = it.accountStatus + (account.id to line)) }
                    }
                }
                .onFailure { statusRequests.remove(account.id) }   // silent; retry on a later recomposition
        }
    }

    private fun XtreamAccountInfo.toStatusLine(): String? {
        val parts = buildList {
            status?.let { add(it) }
            if (activeConnections != null && maxConnections != null) add("$activeConnections/$maxConnections connections")
            expiresAtEpochSec?.let {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                add("Expires " + fmt.format(java.util.Date(it * 1000)))
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
