package com.nuvio.tv.ui.screens.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.radar.RadarChannelMatcher
import com.nuvio.tv.core.radar.RadarFixture
import com.nuvio.tv.core.radar.RadarRepository
import com.nuvio.tv.core.radar.RadarUiState
import com.nuvio.tv.data.local.XtreamAccountStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
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
    /** Channel currently being health-probed before playback (shows "Checking…"). */
    val probingContentId: String? = null,
    /** Channels that failed the health probe this session (shown as Offline, skipped on fallback). */
    val deadContentIds: Set<String> = emptySet(),
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
        probeJob?.cancel()
        _sheet.value = null
    }

    /**
     * Probes the chosen channel before playback and falls through to the other matched
     * channels when it's dead — IPTV panels routinely keep offline channels listed, and a
     * dead one answers with an empty body that the player can only report as a generic
     * format error. First healthy candidate wins; dead ones are marked Offline in the sheet.
     */
    fun playMatch(
        match: RadarChannelMatcher.ChannelMatch,
        onPlay: (title: String, streamUrl: String, contentId: String) -> Unit,
    ) {
        val current = _sheet.value ?: return
        val queue = (listOf(match) + current.matches.filterNot { it.channel.contentId == match.channel.contentId })
            .filterNot { it.channel.contentId in current.deadContentIds }
            .take(PROBE_CAP)
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            for (candidate in queue) {
                _sheet.update { it?.copy(probingContentId = candidate.channel.contentId) }
                if (isStreamAlive(candidate.channel.streamUrl)) {
                    matcher.ensurePlayable(candidate)
                    closeMatch()
                    onPlay(candidate.channel.name, candidate.channel.streamUrl, candidate.channel.contentId)
                    return@launch
                }
                _sheet.update {
                    it?.copy(
                        probingContentId = null,
                        deadContentIds = it.deadContentIds + candidate.channel.contentId,
                    )
                }
            }
            _sheet.update { it?.copy(probingContentId = null) }
        }
    }

    /** True when the URL streams at least one byte of non-HTML content within the timeout. */
    private suspend fun isStreamAlive(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching false
                if (connection.contentType.orEmpty().startsWith("text/html")) return@runCatching false
                connection.inputStream.use { it.read() != -1 }
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    private var probeJob: Job? = null

    private companion object {
        const val PROBE_CAP = 6
        const val PROBE_TIMEOUT_MS = 2_500
    }
}
