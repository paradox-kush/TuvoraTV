package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.PlaylistEditVerifyPolicy
import com.nuvio.tv.core.iptv.PlaylistSaveError
import com.nuvio.tv.core.iptv.PlaylistSaveErrorPolicy
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamAccountInfo
import com.nuvio.tv.core.iptv.XtreamCategory
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.content.M3UFileStore
import com.nuvio.tv.core.iptv.isM3UBacked
import com.nuvio.tv.core.iptv.isM3UFile
import com.nuvio.tv.core.iptv.isXtream
import com.nuvio.tv.core.iptv.match.XtreamTmdbResolver
import com.nuvio.tv.core.iptv.m3uAccountFromFile
import com.nuvio.tv.core.iptv.m3uAccountFromUrl
import com.nuvio.tv.core.iptv.asEditOf
import com.nuvio.tv.core.iptv.newM3UFilePlaylistId
import com.nuvio.tv.core.iptv.parseXtreamAccount
import com.nuvio.tv.core.iptv.withPlaylistOptions
import com.nuvio.tv.core.iptv.xtreamAccountFromFields
import com.nuvio.tv.core.iptv.xtreamPanelInM3uUrl
import com.nuvio.tv.core.sync.XtreamAccountSyncService
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.XtreamLiveStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
    val accountStatus: Map<String, String> = emptyMap(),
    /** accountId -> the guide's EPG-source coverage line (mirror mapping + session tally; read-only). */
    val guideEpgCoverage: Map<String, String> = emptyMap(),
    /** accountId -> a note about a saved edit (B60: the provider check failed, but the edit was kept). */
    val saveWarnings: Map<String, String> = emptyMap(),
    /** F02: the open "Hidden channels & groups" list; null while it loads. */
    val hiddenItems: List<com.nuvio.tv.core.iptv.overlay.IptvHiddenItemsPolicy.HiddenItem>? = null,
)

@HiltViewModel
class XtreamSettingsViewModel @Inject constructor(
    private val store: XtreamAccountStore,
    private val catchUpWinners: com.nuvio.tv.core.iptv.CatchUpWinnerStore,
    private val client: XtreamClient,
    private val clientFactory: IptvClientFactory,
    private val syncService: XtreamAccountSyncService,
    private val registry: XtreamItemRegistry,
    private val libraryPreferences: LibraryPreferences,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val liveStore: XtreamLiveStore,
    private val fileStore: M3UFileStore,
    private val refreshStore: com.nuvio.tv.core.iptv.refresh.IptvRefreshStore,
    private val resolver: XtreamTmdbResolver,
    private val purge: com.nuvio.tv.core.iptv.IptvAccountPurge,
    private val searchIndex: com.nuvio.tv.core.iptv.XtreamSearchIndex,
    private val contentDb: com.nuvio.tv.core.iptv.content.IptvContentDb,
    private val matchIndex: com.nuvio.tv.core.iptv.match.XtreamMatchIndex,
    private val epgMirror: com.nuvio.tv.core.epg.EpgMirrorRepository,
    private val overlayRepository: com.nuvio.tv.core.iptv.overlay.IptvOverlayRepository,
    private val authManager: com.nuvio.tv.core.auth.AuthManager,
    private val serverFailover: com.nuvio.tv.core.iptv.PlaylistServerFailover,
) : ViewModel() {

    /** UX74: whether this TV is signed in — a remove only reaches other devices when it syncs. */
    val signedIn: StateFlow<Boolean> = authManager.authState
        .map { it is com.nuvio.tv.domain.model.AuthState.FullAccount }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), authManager.isAuthenticated)

    private val _uiState = MutableStateFlow(XtreamSettingsUiState())
    val uiState: StateFlow<XtreamSettingsUiState> = _uiState.asStateFlow()

    /** Account ids whose catalog index is building — shown as "Preparing catalog…" on the rows. */
    val indexingAccounts: StateFlow<Set<String>> = resolver.indexing

    /** Live per-account row counts for the same status line — a build runs for minutes here. */
    val indexProgress: StateFlow<Map<String, com.nuvio.tv.core.iptv.match.IndexBuildProgress>> =
        matchIndex.buildProgress

    /**
     * The guide-region picker's state. The mirror indexes every region it carries, but a
     * household uses a fraction (2,035 of 15,397 channels on a measured panel), and unselected
     * regions are never stored — so this trims the on-device index, not just the display.
     */
    private val _epgRegions = MutableStateFlow<List<com.nuvio.tv.core.epg.EpgRegion>>(emptyList())
    val epgRegions: StateFlow<List<com.nuvio.tv.core.epg.EpgRegion>> = _epgRegions.asStateFlow()

    private val _selectedEpgRegions = MutableStateFlow<Set<String>>(emptySet())
    val selectedEpgRegions: StateFlow<Set<String>> = _selectedEpgRegions.asStateFlow()

    fun refreshEpgRegions() {
        viewModelScope.launch {
            _epgRegions.value = runCatching { epgMirror.availableRegions() }.getOrDefault(emptyList())
            _selectedEpgRegions.value = runCatching { epgMirror.selectedRegions() }.getOrDefault(emptySet())
        }
    }

    /** Applies a selection; the repository rebuilds the index on its own scope. */
    fun setEpgRegions(regions: Set<String>) {
        viewModelScope.launch {
            runCatching { epgMirror.setSelectedRegions(regions) }
            _selectedEpgRegions.value = regions
        }
    }

    /**
     * Step 0.3: playlist id -> active backup index, for playlists NOT on their main server (drives the
     * "Using backup server N" row note). Re-read when the accounts or any failover state change.
     */
    val activeServers: StateFlow<Map<String, Int>> =
        combine(store.accounts, serverFailover.version) { accounts, _ -> serverFailover.activeIndexes(accounts) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch {
            store.accounts.collectLatest { accounts ->
                _uiState.update { it.copy(accounts = accounts) }
            }
        }
        refreshEpgRegions()
    }

    /** The shared "Add Playlist" options collected by the form (EPG override, DNS, auto-refresh). */
    data class PlaylistOptions(
        val epgUrl: String? = null,
        val dnsProvider: String = XtreamAccount.DNS_SYSTEM,
        val autoRefreshHours: Int = XtreamAccount.DEFAULT_AUTO_REFRESH_HOURS,
        /** Optional per-playlist stream UA for Xtream/Stalker (M3U keeps its UA in username). */
        val userAgent: String? = null,
        /**
         * Step 0.3: the form's backup-server rows as typed, in priority order — validated and
         * normalized by [com.nuvio.tv.core.iptv.BackupServerValidation] when the account is built.
         * Null = the form did not show the list (an edit then keeps the playlist's current one).
         */
        val backupUrls: List<String>? = null,
    )

    /** Stalker portal form fields collected by the Add/Edit Playlist dialog. */
    data class StalkerFields(
        val portalUrl: String,
        val macAddress: String,
        val username: String = "",
        val password: String = "",
        val serialNumber: String = "",
        val deviceId: String = "",
        val sendDeviceId: Boolean = true
    )

    /** Build a Stalker XtreamAccount from the form fields (id = the shared Step 0 key stalker|portal|MAC). */
    private fun stalkerAccountFrom(fields: StalkerFields, name: String?): XtreamAccount? {
        val portal = fields.portalUrl.trim().let { if (it.startsWith("http")) it else "http://$it" }
        val mac = fields.macAddress.trim()
        if (portal.length <= "http://".length || mac.isBlank()) return null
        val host = runCatching { java.net.URI(portal).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: portal
        return XtreamAccount(
            id = com.nuvio.tv.core.iptv.PlaylistKey.stalker(portal, mac) ?: return null,
            name = name?.takeIf { it.isNotBlank() } ?: host,
            baseUrl = portal,
            username = "",
            password = "",
            sourceType = XtreamAccount.SOURCE_STALKER,
            portalUrl = portal,
            macAddress = mac,
            stalkerUsername = fields.username.trim(),
            stalkerPassword = fields.password.trim(),
            serialNumber = fields.serialNumber.trim(),
            deviceId = fields.deviceId.trim(),
            sendDeviceId = fields.sendDeviceId
        )
    }

    /** Add a Stalker portal playlist: persist + sync. Content loads live via the portal session on browse. */
    fun addStalker(fields: StalkerFields, name: String?, options: PlaylistOptions = PlaylistOptions(), onSuccess: () -> Unit) {
        val account = stalkerAccountFrom(fields, name)?.withOptions(options)?.withBackups(options)
        if (account == null) {
            _uiState.update { it.copy(error = "Enter a portal URL and a MAC address") }
            return
        }
        viewModelScope.launch {
            if (persistOrError { store.upsert(account) }) {
                syncService.triggerRemoteSync()
                onSuccess()
            }
        }
    }

    /** Re-save an edited Stalker playlist: swap in place, preserve enable/content/category selections. */
    fun editStalker(old: XtreamAccount, fields: StalkerFields, name: String?, options: PlaylistOptions = old.toOptions(), onSuccess: () -> Unit) {
        val candidate = stalkerAccountFrom(fields, name ?: old.name)?.withOptions(options)
        if (candidate == null) {
            _uiState.update { it.copy(error = "Enter a portal URL and a MAC address") }
            return
        }
        val account = candidate.asEditOf(old).withBackups(options)   // Step 0: an edit never changes the playlist id
        // Step 0.3: an edited server list (portal or backups) starts over on the main portal.
        serverFailover.onPlaylistEdited(old, account)
        viewModelScope.launch {
            if (persistOrError { store.replace(old.id, account) }) {
                registry.clear()
                evictAccountCaches(old.id)
                syncService.triggerRemoteSync()
                onSuccess()
            }
        }
    }

    /** Copies the form's shared playlist options onto a parsed/built account before verify+save. */
    private fun XtreamAccount.withOptions(options: PlaylistOptions): XtreamAccount =
        withPlaylistOptions(options.epgUrl, options.dnsProvider, options.autoRefreshHours)
            // M3U keeps its UA in username, so options.userAgent is null there and this is a no-op;
            // for Xtream/Stalker it applies the form's per-playlist stream UA.
            .copy(userAgent = options.userAgent?.trim()?.takeIf { it.isNotEmpty() })

    /** The form's shared options as currently persisted on an account (to prefill an edit). */
    private fun XtreamAccount.toOptions(): PlaylistOptions =
        PlaylistOptions(epgUrl = epgUrl, dnsProvider = dnsProvider, autoRefreshHours = autoRefreshHours, userAgent = userAgent)

    /**
     * Step 0.3: the form's backup rows, validated + normalized against THIS account's source type and
     * main server (rows with a problem are dropped — the form refuses to save while any has one). A
     * pasted Xtream panel saved as Xtream therefore reduces its rows to those hosts' base URLs.
     * No rows shown ([PlaylistOptions.backupUrls] null) keeps the account's current list.
     */
    private fun XtreamAccount.withBackups(options: PlaylistOptions): XtreamAccount {
        val rows = options.backupUrls ?: return this
        val urls = com.nuvio.tv.core.iptv.BackupServerValidation
            .validate(sourceType, com.nuvio.tv.core.iptv.PlaylistServerFailover.mainServer(this), rows).urls
        return copy(backupUrls = urls.takeIf { it.isNotEmpty() })
    }

    /** Parse a pasted portal/M3U URL, verify the credentials live, then persist (with form options). */
    fun addFromUrl(input: String, name: String?, options: PlaylistOptions = PlaylistOptions(), onSuccess: () -> Unit) {
        verifyAndSave(
            parseXtreamAccount(input, name)?.withOptions(options)?.withBackups(options),
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
            xtreamAccountFromFields(serverUrl, username, password, name)?.withOptions(options)?.withBackups(options),
            manualFormError(serverUrl, username, password),
            onSuccess
        )
    }

    /**
     * Add an M3U URL playlist. There's no Xtream API to verify against — the playlist URL IS the
     * source — so we persist immediately, then kick off the ingest (fetch + stream-parse into the
     * content DB) in the background. The hub/search show the catalog once the ingest completes;
     * a first hub/search access also triggers ingest if it hasn't run yet.
     */
    fun addM3UUrl(playlistUrl: String, userAgent: String?, name: String?, options: PlaylistOptions = PlaylistOptions(), onSuccess: () -> Unit) {
        // An Xtream get.php / player_api.php paste is saved as the panel it is (catch-up, VOD
        // metadata, search index) — at ADD time only: m3uAccountFromUrl itself is untouched, so
        // edit, pairing and sync keep every existing `m3u:…` id. If the panel API refuses the
        // credentials the paste still works as a plain M3U playlist, so fall back to that lane.
        val panel = xtreamPanelInM3uUrl(playlistUrl, userAgent, name)?.let { p -> p.withOptions(options).copy(userAgent = p.userAgent).withBackups(options) }
        if (panel != null) {
            viewModelScope.launch {
                _uiState.update { it.copy(isValidating = true, error = null) }
                val verified = client.verify(panel).isSuccess
                _uiState.update { it.copy(isValidating = false) }
                if (verified) {
                    if (persistOrError { store.upsert(panel) }) {
                        resolver.warmUp(listOf(panel))
                        syncService.triggerRemoteSync()
                        onSuccess()
                    }
                } else {
                    saveM3UUrl(m3uAccountFromUrl(playlistUrl, userAgent, name)?.withOptions(options)?.withBackups(options), onSuccess)
                }
            }
            return
        }
        saveM3UUrl(m3uAccountFromUrl(playlistUrl, userAgent, name)?.withOptions(options)?.withBackups(options), onSuccess)
    }

    /** Persist a built M3U URL account and kick off its ingest (see [addM3UUrl]). */
    private fun saveM3UUrl(account: XtreamAccount?, onSuccess: () -> Unit) {
        if (account == null) {
            _uiState.update { it.copy(error = "Enter a valid M3U playlist URL") }
            return
        }
        viewModelScope.launch {
            if (persistOrError { store.upsert(account) }) {
                syncService.triggerRemoteSync()
                onSuccess()
                // Ingest in the background (M3UClient is single-flight + self-scoped, survives this scope).
                runCatching { clientFactory.m3u().ensureIngested(account, force = true) }
            }
        }
    }

    /** Re-save an edited M3U URL playlist: swap in place (same id — Step 0), force a re-ingest. */
    fun editM3UUrl(old: XtreamAccount, playlistUrl: String, userAgent: String?, options: PlaylistOptions = old.toOptions(), onSuccess: () -> Unit) {
        val candidate = m3uAccountFromUrl(playlistUrl, userAgent, old.name)?.withOptions(options)
        if (candidate == null) {
            _uiState.update { it.copy(error = "Enter a valid M3U playlist URL") }
            return
        }
        val account = candidate.asEditOf(old).withBackups(options)   // Step 0: an edit never changes the playlist id
        // Step 0.3: an edited server list (URL or backups) starts over on the main URL.
        serverFailover.onPlaylistEdited(old, account)
        viewModelScope.launch {
            if (persistOrError { store.replace(old.id, account) }) {
                registry.clear()
                evictAccountCaches(old.id)
                syncService.triggerRemoteSync()
                onSuccess()
                // Same id, possibly another URL: the forced re-ingest replaces the old catalog rows.
                runCatching { clientFactory.m3u().ensureIngested(account, force = true) }
            }
        }
    }

    /**
     * Add an M3U FILE playlist: copy the picked document into app storage (so the source can
     * disappear), persist a SOURCE_FILE account, then ingest the LOCAL copy through the same M3U
     * pipeline. File contents are NOT synced (spec §3.2) — only the account row, which is filtered
     * out of the current sync, so this stays local. [uri] is the SAF document uri; [fileName] is its
     * display name; [reimportFor] (non-null) re-imports a file playlist that lost its local copy,
     * keeping its id + saved content.
     */
    fun addM3UFile(uri: Uri, fileName: String, name: String?, options: PlaylistOptions = PlaylistOptions(), reimportFor: XtreamAccount? = null, onSuccess: () -> Unit) {
        val playlistId = reimportFor?.id ?: newM3UFilePlaylistId(fileName)
        val displayName = reimportFor?.name ?: name
        val account = m3uAccountFromFile(playlistId, fileName, displayName).withOptions(
            // A re-import keeps the existing account's options; a fresh add takes the form's.
            reimportFor?.toOptions() ?: options
        ).let { built ->
            reimportFor?.let { old ->
                built.copy(enabled = old.enabled, contentTypes = old.contentTypes, categorySelections = old.categorySelections)
            } ?: built
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isValidating = true, error = null) }
            val copied = runCatching { fileStore.importFrom(playlistId, uri) }
            _uiState.update { it.copy(isValidating = false) }
            copied.onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "Couldn't read the selected file") }
                return@launch
            }
            // upsert also covers the re-import case (same id -> replace).
            if (persistOrError { store.upsert(account) }) {
                // File playlists aren't synced (contents can't travel), but push keeps the account
                // list consistent; the sync filters non-xtream rows out anyway.
                syncService.triggerRemoteSync()
                onSuccess()
                runCatching { clientFactory.m3u().ensureIngested(account, force = true) }
            }
        }
    }

    /** True when a file playlist has NO local copy on this device (synced from elsewhere / cleared)
     *  and must be re-imported before it can browse. Always false for non-file playlists. */
    fun needsReimport(account: XtreamAccount): Boolean =
        account.isM3UFile() && !fileStore.exists(account.id)

    /**
     * B24 §3 — the durable-write boundary for a verified add/edit. Runs the store [write] and returns
     * whether it committed. A storage failure surfaces a save error and returns false, so the caller
     * skips the sync push and the success callback (no false success, no push from an unsaved state).
     * A CancellationException propagates (structured concurrency) instead of being reported as a save
     * failure. The TV store write is a suspend DataStore edit{}, which is transactional — a failure
     * leaves the persisted bytes (and therefore the sync authority derived from them) unchanged.
     */
    private suspend fun persistOrError(write: suspend () -> Unit): Boolean {
        try {
            write()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            Log.e("XtreamSettingsVM", "playlist save failed", e)
            _uiState.update {
                it.copy(isValidating = false, error = "Couldn't save the playlist on this device. Free up space and try again.")
            }
            return false
        }
        return true
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
                if (persistOrError { store.upsert(account) }) {
                    // Start the catalog index now, not on first play — minutes on budget boxes.
                    resolver.warmUp(listOf(account))
                    syncService.triggerRemoteSync()
                    onSuccess()
                }
            }.onFailure { e ->
                // UX20: a mapped sentence (unreachable vs wrong credentials vs TLS), never e.message.
                _uiState.update { it.copy(error = PlaylistSaveErrorPolicy.messageFor(e)) }
            }
        }
    }

    /** Re-verify + replace an existing account from a pasted portal/M3U URL (playlist edit). */
    fun editFromUrl(old: XtreamAccount, input: String, options: PlaylistOptions = old.toOptions(), onSuccess: () -> Unit) {
        verifyAndReplace(old, parseXtreamAccount(input, old.name)?.withOptions(options), options, "Couldn't read a username & password from that URL", onSuccess)
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
            options,
            manualFormError(serverUrl, username, password),
            onSuccess
        )
    }

    /**
     * UX21: the sentence for a manual Xtream form that didn't build an account — empty fields keep
     * "Enter a server URL…", a filled form with an unparseable address (bad port) says so instead.
     */
    private fun manualFormError(serverUrl: String, username: String, password: String): String =
        PlaylistSaveErrorPolicy.message(
            PlaylistSaveErrorPolicy.formError(serverUrl, username, password) ?: PlaylistSaveError.INVALID_ADDRESS
        )

    /**
     * Verifies the edited credentials live, then swaps the account in place (keeping its
     * position + enabled flag) and re-runs the discovery cycle.
     *
     * Step 0: the playlist KEEPS ITS ID whatever was edited — the id is the permanent key the user's
     * library, progress, watched marks, live favourites/recents and the overlay's hashed hidden/pinned
     * channel keys all hang off. Re-deriving it from the new address (the old behaviour) orphaned all
     * of it when a provider moved domains.
     */
    private fun verifyAndReplace(
        old: XtreamAccount,
        candidate: XtreamAccount?,
        options: PlaylistOptions,
        parseError: String,
        onSuccess: () -> Unit,
    ) {
        if (candidate == null) {
            _uiState.update { it.copy(error = parseError) }
            return
        }
        // Credential/URL edits keep the content selections (toggles, category picks) — those aren't
        // in this form. The shared options (epg/dns/refresh) already ride on `candidate` from the
        // form (withOptions), so DON'T overwrite them from `old`, or an edit couldn't change them.
        val account = candidate.asEditOf(old).withBackups(options)   // Step 0: an edit never changes the playlist id
        // Step 0.3: an edited server list (main or backups) starts over on the main server — the old
        // active index may now name a different server or none. Before the verify below, which
        // itself may legitimately land on a backup.
        serverFailover.onPlaylistEdited(old, account)
        viewModelScope.launch {
            _uiState.update { it.copy(isValidating = true, error = null) }
            // Options-only edit (name/EPG/DNS/refresh) — nothing about how we reach the provider
            // changed, so it is not checked at all. See sameConnectionAs.
            val result =
                if (!PlaylistEditVerifyPolicy.needsVerify(old, account)) Result.success(Unit) else client.verify(account)
            _uiState.update { it.copy(isValidating = false) }
            // B60 decision (2026-09-27): a failed check saves anyway and shows the reason on the row —
            // the edit that fails a check is usually a provider moving domains, the one users must keep.
            val outcome = PlaylistEditVerifyPolicy.outcome(result)
            if (persistOrError { store.replace(old.id, account) }) {
                // Live favourites/recents store a display stream URL built with the old server/creds.
                if (!old.sameConnectionAs(account)) refreshLiveStreamUrls(account)
                // Cached stream URLs embed the old server/creds; rebuild lazily on demand.
                registry.clear()
                // A renewed/edited account must not keep showing a stale "Expired" status or
                // category lists fetched under the old creds — evict its caches.
                evictAccountCaches(old.id)
                _uiState.update { st ->
                    st.copy(
                        saveWarnings = (st.saveWarnings - old.id) +
                            (outcome.warning?.let { mapOf(account.id to it) } ?: emptyMap())
                    )
                }
                resolver.warmUp(listOf(account))
                syncService.triggerRemoteSync()
                onSuccess()
            }
        }
    }

    /**
     * Rebuilds the display stream URL of every live favourite/recent under this playlist for its new
     * server/creds (same id, same content ids). Only Xtream URLs are formula-derivable; M3U refs keep
     * theirs (the forced re-ingest refreshes the catalog) and Stalker refs resolve via create_link.
     * Playback resolves from the content id either way — this only keeps the stored ref honest.
     */
    private suspend fun refreshLiveStreamUrls(account: XtreamAccount) {
        if (!account.isXtream()) return
        val prefix = XtreamItemRegistry.accountPrefix(account.id)
        liveStore.migrateAccount(prefix) { ref ->
            val streamId = ref.id.substringAfterLast(':').toIntOrNull()
            ref.copy(streamUrl = streamId?.let { client.buildStreamUrl(account, "live", it) } ?: ref.streamUrl)
        }
    }

    /** Distrust every "not on this provider" verdict for this playlist (see XtreamMatchIndex). */
    fun rematchCatalog(id: String) {
        viewModelScope.launch { runCatching { matchIndex.distrustNegativeMappings(id) } }
    }

    /**
     * Flips the per-playlist catch-up container preference.
     *
     * Clearing the remembered dialect is the load-bearing half. The walk puts a proven winner at the
     * head of the ladder, so on an account that already learned a TS dialect the flipped preference
     * would never get a turn and the toggle would silently do nothing — on exactly the accounts the
     * viewer has used the most. ([CatchUpWinnerStore] also voids it by stamp, which covers a
     * preference that arrives from another device; this is the same fix from the settings end.)
     */
    fun setPreferM3u8CatchUp(id: String, prefer: Boolean) {
        viewModelScope.launch {
            store.update(id) { it.copy(preferM3u8CatchUp = prefer) }
            catchUpWinners.forget(id)
        }
    }

    /** Manual catch-up time correction, for panels that lie about their own clock. */
    fun setCatchUpCorrectionMinutes(id: String, minutes: Int) {
        viewModelScope.launch {
            store.update(id) {
                it.copy(
                    catchUpCorrectionMinutes = minutes.coerceIn(
                        XtreamAccount.CATCHUP_CORRECTION_MIN_MINUTES,
                        XtreamAccount.CATCHUP_CORRECTION_MAX_MINUTES,
                    )
                )
            }
        }
    }

    /**
     * Manual guide EPG offset (0 = auto-detect the wall-clock-epoch lie per response).
     *
     * Opening the fetch stamps is the load-bearing half, same shape as [setPreferM3u8CatchUp]
     * clearing the winner store: stored guide rows were corrected under the OLD offset and the
     * six-hour gate would keep showing them — the setting would look dead on exactly the guide the
     * user is staring at. Stale rows stay readable until each channel's next focus refetches.
     */
    fun setGuideEpgCorrectionMinutes(id: String, minutes: Int) {
        viewModelScope.launch {
            store.update(id) {
                it.copy(
                    guideEpgCorrectionMinutes = minutes.coerceIn(
                        XtreamAccount.CATCHUP_CORRECTION_MIN_MINUTES,
                        XtreamAccount.CATCHUP_CORRECTION_MAX_MINUTES,
                    )
                )
            }
            runCatching { contentDb.resetEpgFetchStamps(id) }
            // Sources measured under the old offset are stale too: a channel that fell to the
            // mirror because its rows looked skewed deserves a fresh panel ask under the new one.
            com.nuvio.tv.core.iptv.EpgSourceLadder.sessionMemory.forgetAccount(id)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            store.setEnabled(id, enabled)
            if (enabled) store.accounts.first().firstOrNull { it.id == id }?.let { resolver.warmUp(listOf(it)) }
            syncService.triggerRemoteSync()
        }
    }

    fun remove(id: String) {
        _uiState.update { it.copy(saveWarnings = it.saveWarnings - id) }
        viewModelScope.launch {
            store.remove(id)
            // Everything keyed by this id — caches/indexes that would leak on disk forever, and the
            // saved refs that would be dead ids (phantom favorites / continue-watching rows). What
            // goes is decided by PlaylistRemovalCleanup; IptvAccountPurge executes it.
            purge.purge(id, com.nuvio.tv.core.iptv.PlaylistRemovalOrigin.UserDelete)
            syncService.triggerRemoteSync()
        }
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
        coverageRequests.removeAll(ids)
        // A creds edit keeps the same id when only the password changed — the search
        // cache's stream URLs embed the OLD password, so drop it alongside the VM caches.
        ids.forEach { searchIndex.evict(it) }
        _uiState.update {
            it.copy(
                accountStatus = it.accountStatus - ids,
                categoryLists = it.categoryLists - typeKeys,
                guideEpgCoverage = it.guideEpgCoverage - ids,
            )
        }
    }

    /** Fetch the three category lists for the Content & Categories dialog (cached, silent on failure). */
    fun loadCategoryLists(account: XtreamAccount) {
        for (type in listOf(XtreamAccount.TYPE_LIVE, XtreamAccount.TYPE_MOVIES, XtreamAccount.TYPE_SERIES)) {
            val key = "${account.id}|$type"
            if (!categoryRequests.add(key)) continue
            viewModelScope.launch {
                // clientFor dispatches per source type — M3U reads the ingested catalog's
                // categories, Stalker asks the portal; raw XtreamClient would 404 on both.
                val sourceClient = clientFactory.clientFor(account)
                val result = when (type) {
                    XtreamAccount.TYPE_LIVE -> sourceClient.liveCategories(account)
                    XtreamAccount.TYPE_MOVIES -> sourceClient.vodCategories(account)
                    else -> sourceClient.seriesCategories(account)
                }
                result
                    .onSuccess { cats -> _uiState.update { it.copy(categoryLists = it.categoryLists + (key to cats)) } }
                    .onFailure { categoryRequests.remove(key) }   // allow a retry on next dialog open
            }
        }
    }

    /**
     * F02: everything hidden in [account] (on any device or the website), resolved to names. The overlay
     * stores only hashed keys, so the playlist's channels are read only when some channel is hidden,
     * and its categories only when some category is.
     */
    fun loadHiddenItems(account: XtreamAccount) {
        _uiState.update { it.copy(hiddenItems = null) }
        viewModelScope.launch {
            val items = runCatching {
                val overlay = overlayRepository.freshSnapshot()
                val source = clientFactory.clientFor(account)
                val channels = if (overlay.channels.values.any { it.hidden }) {
                    source.liveChannels(account).getOrDefault(emptyList()).map {
                        com.nuvio.tv.core.iptv.overlay.IptvHiddenItemsPolicy.CatalogChannel(
                            com.nuvio.tv.core.iptv.identity.IptvIdentity.entityId(account.id, it.name, it.epgChannelId), it.name,
                        )
                    }
                } else emptyList()
                val categories = if (overlay.categories.values.any { it.hidden }) {
                    listOf(XtreamAccount.TYPE_LIVE, XtreamAccount.TYPE_MOVIES, XtreamAccount.TYPE_SERIES).flatMap { type ->
                        when (type) {
                            XtreamAccount.TYPE_LIVE -> source.liveCategories(account)
                            XtreamAccount.TYPE_MOVIES -> source.vodCategories(account)
                            else -> source.seriesCategories(account)
                        }.getOrDefault(emptyList()).map {
                            com.nuvio.tv.core.iptv.overlay.IptvHiddenItemsPolicy.CatalogCategory(
                                type, com.nuvio.tv.core.iptv.identity.IptvIdentity.categoryKey(account.id, type, it.name), it.name,
                            )
                        }
                    }
                } else emptyList()
                com.nuvio.tv.core.iptv.overlay.IptvHiddenItemsPolicy.hiddenItems(channels, categories, overlay)
            }.getOrDefault(emptyList())
            _uiState.update { it.copy(hiddenItems = items) }
        }
    }

    /** F02: undo one hide; the row leaves the list at once and the change syncs. */
    fun unhide(account: XtreamAccount, item: com.nuvio.tv.core.iptv.overlay.IptvHiddenItemsPolicy.HiddenItem) {
        when (item.kind) {
            com.nuvio.tv.core.iptv.overlay.IptvHiddenItemsPolicy.HiddenKind.GROUP ->
                overlayRepository.setCategoryHidden(account.id, item.contentType, item.key, hidden = false)
            com.nuvio.tv.core.iptv.overlay.IptvHiddenItemsPolicy.HiddenKind.CHANNEL ->
                overlayRepository.setChannelHidden(item.key, account.id, hidden = false)
        }
        _uiState.update { st -> st.copy(hiddenItems = st.hiddenItems?.minus(item)) }
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

    private val coverageRequests = mutableSetOf<String>()   // accountId in flight or done

    /**
     * Lazily compute the guide's EPG-source coverage line for a settings row (read-only, cached
     * per VM). Cheap by construction: one mirror mapping read (the streamId→epgId table for this
     * playlist) + the stored ingest count where one exists (M3U/Stalker — Xtream lineups aren't
     * ingested, so their line shows the mapped figure alone rather than paying a panel call to
     * count) + the in-memory session tally of which ladder rung fed each browsed channel. Never
     * a lineup scan — coverage must not cost what it reports on.
     */
    fun ensureGuideEpgCoverage(account: XtreamAccount) {
        if (!coverageRequests.add(account.id)) return
        viewModelScope.launch {
            val mapped = runCatching { epgMirror.mappingFor(account.id).size }.getOrDefault(0)
            val total = runCatching { contentDb.liveCount(account.id) }.getOrDefault(0).takeIf { it > 0 }
            val coverage = when {
                mapped <= 0 -> "Backup guide (EPG mirror): no channels matched yet"
                total != null -> "Backup guide (EPG mirror): $mapped of $total channels matched"
                else -> "Backup guide (EPG mirror): $mapped channels matched"
            }
            val tally = com.nuvio.tv.core.iptv.EpgSourceLadder.sessionMemory.tally(account.id)
            val line = if (tally.total == 0) coverage else {
                val parts = buildList {
                    if (tally.manual > 0) add("manual ${tally.manual}")
                    if (tally.provider > 0) add("provider ${tally.provider}")
                    if (tally.mirror > 0) add("backup ${tally.mirror}")
                    if (tally.none > 0) add("none ${tally.none}")
                }
                coverage + " · guide sources this session: " + parts.joinToString(" · ")
            }
            _uiState.update { it.copy(guideEpgCoverage = it.guideEpgCoverage + (account.id to line)) }
        }
    }

    /** Lazily fetch the account-status line for a settings row. Non-blocking, cached, silent on failure. */
    fun ensureAccountStatus(account: XtreamAccount) {
        if (!statusRequests.add(account.id)) return
        viewModelScope.launch {
            // Catalog counts from LOCAL data only — zero API calls (item 8; how TiviMate shows
            // "Movies: 60000"). Prepended so even an unreachable panel still shows its sizes.
            val counts = runCatching { localCatalogCounts(account) }.getOrDefault(emptyList())
            if (counts.isNotEmpty()) {
                _uiState.update { it.copy(accountStatus = it.accountStatus + (account.id to counts.joinToString(" · "))) }
            }
            // M3U playlists have no account endpoint at all — don't burn a doomed request per row.
            if (account.isM3UBacked()) return@launch
            clientFactory.clientFor(account).accountInfo(account)
                .onSuccess { info ->
                    info.toStatusLine()?.let { line ->
                        val full = (counts + line).joinToString(" · ")
                        _uiState.update { it.copy(accountStatus = it.accountStatus + (account.id to full)) }
                    }
                }
                .onFailure { if (counts.isEmpty()) statusRequests.remove(account.id) }   // silent; retry later
        }
    }

    /** "12,000 channels" / "Movies 60000" style parts, from the local stores only. */
    private suspend fun localCatalogCounts(account: XtreamAccount): List<String> = when {
        account.isM3UBacked() -> buildList {
            val live = contentDb.liveCount(account.id)
            if (live > 0) add("$live channels")
        }
        account.sourceType == XtreamAccount.SOURCE_STALKER -> buildList {
            val live = contentDb.liveCount(account.id)
            if (live > 0) add("$live channels")
        }
        else -> buildList {
            matchIndex.indexedCount(account.id, com.nuvio.tv.core.iptv.match.MatchKind.MOVIE)?.let { add("$it movies") }
            matchIndex.indexedCount(account.id, com.nuvio.tv.core.iptv.match.MatchKind.SERIES)?.let { add("$it series") }
        }
    }

    private fun XtreamAccountInfo.toStatusLine(): String? {
        val parts = buildList {
            status?.let { add(it) }
            if (activeConnections != null && maxConnections != null) add("$activeConnections/$maxConnections connections")
            when {
                // Stalker gives free-text expiry ("February 20, 2027"); surface it verbatim.
                expiresText != null -> add("Expires $expiresText")
                expiresAtEpochSec != null -> {
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    add("Expires " + fmt.format(java.util.Date(expiresAtEpochSec * 1000)))
                }
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
