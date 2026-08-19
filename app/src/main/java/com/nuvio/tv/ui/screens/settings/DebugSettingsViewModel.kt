package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.epg.EpgMirrorRepository
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.isM3UBacked
import com.nuvio.tv.core.iptv.isXtream
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.match.MatchKind
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.iptv.match.XtreamTmdbResolver
import com.nuvio.tv.data.local.DebugSettingsDataStore
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class DebugSettingsViewModel @Inject constructor(
    private val dataStore: DebugSettingsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val authManager: AuthManager,
    private val libraryPreferences: LibraryPreferences,
    private val accountStore: XtreamAccountStore,
    private val clientFactory: IptvClientFactory,
    private val resolver: XtreamTmdbResolver,
    private val matchIndex: XtreamMatchIndex,
    private val contentDb: IptvContentDb,
    private val epgMirror: EpgMirrorRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DebugSettingsUiState())
    val uiState: StateFlow<DebugSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.accountTabEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(accountTabEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            dataStore.syncCodeFeaturesEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(syncCodeFeaturesEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.composeHighlighterEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(composeHighlighterEnabled = enabled) }
            }
        }
        // Buffer logs state
        viewModelScope.launch {
            playerSettingsDataStore.playerSettings.collectLatest { settings ->
                _uiState.update { it.copy(bufferLogsEnabled = settings.enableBufferLogs) }
            }
        }
    }

    fun onEvent(event: DebugSettingsEvent) {
        when (event) {
            is DebugSettingsEvent.ToggleAccountTab -> {
                viewModelScope.launch { dataStore.setAccountTabEnabled(event.enabled) }
            }
            is DebugSettingsEvent.ToggleSyncCodeFeatures -> {
                viewModelScope.launch { dataStore.setSyncCodeFeaturesEnabled(event.enabled) }
            }
            is DebugSettingsEvent.ToggleComposeHighlighter -> {
                viewModelScope.launch { layoutPreferenceDataStore.setComposeHighlighterEnabled(event.enabled) }
            }
            is DebugSettingsEvent.ToggleBufferLogs -> {
                viewModelScope.launch { playerSettingsDataStore.setEnableBufferLogs(event.enabled) }
            }
            is DebugSettingsEvent.GenerateLibraryItems -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(generateLibraryLoading = true, generateLibraryResult = null) }
                    try {
                        generateRandomLibraryItems(event.count)
                        _uiState.update {
                            it.copy(
                                generateLibraryLoading = false,
                                generateLibraryResult = context.getString(
                                    R.string.debug_generate_library_result_added,
                                    event.count
                                )
                            )
                        }
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(
                                generateLibraryLoading = false,
                                generateLibraryResult = context.getString(R.string.debug_generate_result_failed, e.message ?: "")
                            )
                        }
                    }
                }
            }
            is DebugSettingsEvent.SignIn -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(signInLoading = true, signInResult = null) }
                    val result = authManager.signInWithEmail(event.email, event.password)
                    _uiState.update {
                        it.copy(
                            signInLoading = false,
                            signInResult = if (result.isSuccess) context.getString(R.string.debug_signin_success) else context.getString(R.string.debug_generate_result_failed, result.exceptionOrNull()?.message ?: "")
                        )
                    }
                }
            }
            DebugSettingsEvent.ForceEpgRedownload -> runReingest("EPG mirror re-download") {
                epgMirror.ensureFresh(force = true)
                "manifest + feeds refetched"
            }
            DebugSettingsEvent.ReindexXtream -> runReingest("Reindex Xtream") {
                val accounts = accountStore.accounts.first().filter { it.enabled && it.isXtream() }
                var items = 0
                for (acc in accounts) {
                    for (kind in listOf(MatchKind.MOVIE, MatchKind.SERIES, MatchKind.LIVE)) {
                        resolver.ensureIndexed(acc, kind, force = true)
                        items += matchIndex.indexedCount(acc.id, kind) ?: 0
                    }
                }
                "${accounts.size} provider(s), $items items indexed"
            }
            DebugSettingsEvent.ReindexM3U -> runReingest("Reindex M3U") {
                val accounts = accountStore.accounts.first().filter { it.enabled && it.isM3UBacked() }
                for (acc in accounts) clientFactory.m3u().ensureIngested(acc, force = true)
                "${accounts.size} playlist(s) re-parsed"
            }
            DebugSettingsEvent.ReindexStalker -> runReingest("Reindex Stalker") {
                val accounts = accountStore.accounts.first().filter { it.enabled && it.sourceType == XtreamAccount.SOURCE_STALKER }
                var channels = 0
                for (acc in accounts) {
                    contentDb.clear(acc.id)
                    clientFactory.stalker().evictCaches(acc.id)
                    channels += clientFactory.stalker().liveChannels(acc, null).getOrNull()?.size ?: 0
                }
                "${accounts.size} portal(s), $channels live channels"
            }
            DebugSettingsEvent.ReindexAll -> runReingest("Reindex ALL + EPG") {
                val accounts = accountStore.accounts.first().filter { it.enabled }
                for (acc in accounts) {
                    when {
                        acc.isM3UBacked() -> clientFactory.m3u().ensureIngested(acc, force = true)
                        acc.sourceType == XtreamAccount.SOURCE_STALKER -> {
                            contentDb.clear(acc.id)
                            clientFactory.stalker().liveChannels(acc, null)
                        }
                        acc.isXtream() -> for (kind in listOf(MatchKind.MOVIE, MatchKind.SERIES, MatchKind.LIVE)) {
                            resolver.ensureIndexed(acc, kind, force = true)
                        }
                    }
                }
                epgMirror.ensureFresh(force = true)
                "${accounts.size} account(s) reindexed + EPG"
            }
        }
    }

    /** Runs a force re-ingest with timing, surfacing the result on [DebugSettingsUiState.reingestResult]. */
    private fun runReingest(label: String, block: suspend () -> String) {
        viewModelScope.launch {
            _uiState.update { it.copy(reingestLoading = true, reingestResult = "$label…") }
            val started = System.currentTimeMillis()
            val outcome = try {
                val summary = block()
                "$label \u2713 " + "%.1f".format((System.currentTimeMillis() - started) / 1000.0) + "s — " + summary
            } catch (e: Exception) {
                "Failed: $label — " + (e.message ?: e::class.simpleName)
            }
            _uiState.update { it.copy(reingestLoading = false, reingestResult = outcome) }
        }
    }

    private suspend fun generateRandomLibraryItems(count: Int) {
        val movieTitles = listOf(
            "The Shawshank Redemption", "The Godfather", "The Dark Knight", "Pulp Fiction",
            "Forrest Gump", "Inception", "The Matrix", "Goodfellas", "Fight Club",
            "Interstellar", "The Silence of the Lambs", "Se7en", "The Green Mile",
            "Gladiator", "Saving Private Ryan", "Schindler's List", "The Departed",
            "Whiplash", "Django Unchained", "The Prestige", "Memento", "Alien",
            "Blade Runner", "Jurassic Park", "The Terminator", "Back to the Future",
            "Die Hard", "Mad Max: Fury Road", "Jaws", "Rocky"
        )
        val showTitles = listOf(
            "Breaking Bad", "Game of Thrones", "The Sopranos", "The Wire", "Stranger Things",
            "Chernobyl", "Band of Brothers", "True Detective", "Fargo", "The Office",
            "Friends", "Seinfeld", "Lost", "Dexter", "The Walking Dead", "Westworld",
            "Narcos", "Peaky Blinders", "Ozark", "Better Call Saul", "The Mandalorian",
            "Succession", "Dark", "The Expanse", "Black Mirror", "Mr. Robot",
            "Mindhunter", "The Crown", "Fleabag", "Sherlock"
        )
        val genres = listOf("Action", "Drama", "Comedy", "Thriller", "Sci-Fi", "Horror", "Romance", "Adventure", "Crime", "Fantasy")
        val years = (1990..2025).toList()

        for (i in 1..count) {
            val isMovie = Random.nextBoolean()
            val titles = if (isMovie) movieTitles else showTitles
            val title = "${titles.random()} ${Random.nextInt(1000, 9999)}"
            val type = if (isMovie) "movie" else "series"
            val item = SavedLibraryItem(
                id = "tt${Random.nextInt(1000000, 9999999)}",
                type = type,
                name = title,
                poster = null,
                posterShape = PosterShape.POSTER,
                background = null,
                description = context.getString(R.string.debug_generate_description, type, i),
                releaseInfo = years.random().toString(),
                imdbRating = (Math.round((Random.nextFloat() * 4f + 6f) * 10f) / 10f),
                genres = genres.shuffled().take(Random.nextInt(1, 4)),
                addonBaseUrl = null
            )
            libraryPreferences.addItem(item)
        }
    }
}

data class DebugSettingsUiState(
    val accountTabEnabled: Boolean = false,
    val syncCodeFeaturesEnabled: Boolean = false,
    val composeHighlighterEnabled: Boolean = false,
    val bufferLogsEnabled: Boolean = false,
    val generateLibraryLoading: Boolean = false,
    val generateLibraryResult: String? = null,
    val signInLoading: Boolean = false,
    val signInResult: String? = null,
    val reingestLoading: Boolean = false,
    val reingestResult: String? = null
)

sealed class DebugSettingsEvent {
    data class ToggleAccountTab(val enabled: Boolean) : DebugSettingsEvent()
    data class ToggleSyncCodeFeatures(val enabled: Boolean) : DebugSettingsEvent()
    data class ToggleComposeHighlighter(val enabled: Boolean) : DebugSettingsEvent()
    data class ToggleBufferLogs(val enabled: Boolean) : DebugSettingsEvent()
    data class GenerateLibraryItems(val count: Int) : DebugSettingsEvent()
    data class SignIn(val email: String, val password: String) : DebugSettingsEvent()
    object ForceEpgRedownload : DebugSettingsEvent()
    object ReindexXtream : DebugSettingsEvent()
    object ReindexM3U : DebugSettingsEvent()
    object ReindexStalker : DebugSettingsEvent()
    object ReindexAll : DebugSettingsEvent()
}