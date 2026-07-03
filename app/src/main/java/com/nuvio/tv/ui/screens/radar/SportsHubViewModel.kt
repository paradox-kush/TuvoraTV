package com.nuvio.tv.ui.screens.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.radar.RadarChannelMatcher
import com.nuvio.tv.core.radar.RadarFixture
import com.nuvio.tv.core.radar.RadarRepository
import com.nuvio.tv.core.radar.RadarUiState
import com.nuvio.tv.data.local.XtreamAccountStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The channel-matching overlay's state for one fixture. */
data class MatchSheetState(
    val fixture: RadarFixture,
    val matches: List<RadarChannelMatcher.ChannelMatch> = emptyList(),
    /** Provider VOD recordings of this fixture (started/finished matches). */
    val recordings: List<RadarChannelMatcher.RecordingHit> = emptyList(),
    /** channel contentId -> (replayContentId, timeshiftUrl, title) for archived channels. */
    val replays: Map<String, Triple<String, String, String>> = emptyMap(),
    val matching: Boolean = true,
    val hasPlaylists: Boolean = true,
)

@HiltViewModel
class SportsHubViewModel @Inject constructor(
    val repository: RadarRepository,
    private val matcher: RadarChannelMatcher,
    accountStore: XtreamAccountStore,
) : ViewModel() {

    val uiState: StateFlow<RadarUiState> = repository.uiState

    val hasPlaylists: StateFlow<Boolean> = accountStore.accounts
        .map { list -> list.any { it.enabled } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _sheet = MutableStateFlow<MatchSheetState?>(null)
    val sheet: StateFlow<MatchSheetState?> = _sheet.asStateFlow()

    private var matchJob: Job? = null

    fun ensureLoaded() = repository.ensureLoaded()

    fun openMatch(fixture: RadarFixture) {
        matchJob?.cancel()
        _sheet.value = MatchSheetState(fixture = fixture, hasPlaylists = hasPlaylists.value)
        if (!hasPlaylists.value) {
            _sheet.update { it?.copy(matching = false) }
            return
        }
        matchJob = viewModelScope.launch {
            val league = fixture.leagueId?.let { repository.uiState.value.leagueById(it) }
            launch {
                val recordings = runCatching { matcher.findRecordings(fixture) }.getOrDefault(emptyList())
                _sheet.update { s -> if (s?.fixture === fixture) s.copy(recordings = recordings) else s }
            }
            val result = matcher.match(fixture, league, onPartial = { partial ->
                _sheet.update { s -> if (s?.fixture === fixture) s.copy(matches = partial) else s }
            })
            val replays = buildMap {
                result.forEach { m ->
                    runCatching { matcher.replayFor(m, fixture) }.getOrNull()
                        ?.let { put(m.channel.contentId, it) }
                }
            }
            _sheet.update { s ->
                if (s?.fixture === fixture) s.copy(matches = result, replays = replays, matching = false) else s
            }
        }
    }

    fun closeMatch() {
        matchJob?.cancel()
        _sheet.value = null
    }

    /** Registers the channel so the live player route can resolve it, then hands back play args. */
    fun preparePlay(match: RadarChannelMatcher.ChannelMatch): Triple<String, String, String> {
        matcher.ensurePlayable(match)
        return Triple(match.channel.name, match.channel.streamUrl, match.channel.contentId)
    }
}
