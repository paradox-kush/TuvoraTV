package com.nuvio.tv.ui.screens.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.radar.RadarChannelMatcher
import com.nuvio.tv.core.radar.RadarFixture
import com.nuvio.tv.core.radar.RadarLeague
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

/**
 * Whether a matched channel's stream may be health-probed before playback, from the URL its
 * source listed it with. Xtream and M3U list a reusable URL — probing it costs nothing. Stalker
 * lists a blank one and mints a single-use link per play, so probing would consume the link the
 * player is about to use.
 */
/**
 * Results state for the "add a league" picker, from either route into it: the
 * sport -> country browse, or a free-text [query]. [isOpen] is what the screen shows the
 * results dialog on, since a text search has no sport/country to key off.
 */
data class LeagueSearchState(
    val sport: String = "",
    val country: String = "",
    val query: String = "",
    val loading: Boolean = false,
    val results: List<RadarLeague> = emptyList(),
) {
    val isOpen: Boolean get() = query.isNotBlank() || (sport.isNotBlank() && country.isNotBlank())
    val title: String get() = if (query.isNotBlank()) "\"$query\"" else "$sport · $country"
    val emptyText: String
        get() = if (query.isNotBlank()) "No leagues match \"$query\"."
        else "No leagues found for $sport in $country."
}

/**
 * Sports to offer, in TheSportsDB's own spelling — the discovery endpoint filters on these
 * strings, so they can't be prettified here. Ordered by how many people actually want them.
 */
val RADAR_LEAGUE_SPORTS: List<String> = listOf(
    "Soccer", "Basketball", "American Football", "Baseball", "Ice Hockey",
    "Cricket", "Rugby", "Motorsport", "Fighting", "Tennis", "Golf",
    "Cycling", "Australian Football", "Handball", "Volleyball", "Netball",
    "Darts", "Snooker", "Esports",
)

/** Shortest query worth a round trip — one or two letters match half the database. */
internal const val MIN_LEAGUE_QUERY = 3

/**
 * Countries worth offering on a remote. Deliberately a short, ordered list rather than every
 * country TheSportsDB knows — this is browsed with a d-pad, and the long tail is what the
 * phone's free-text search is for.
 */
val RADAR_LEAGUE_COUNTRIES: List<String> = listOf(
    "England", "Spain", "Italy", "Germany", "France", "Portugal", "Netherlands",
    "Mexico", "Argentina", "Brazil", "Colombia", "Chile", "United States", "Canada",
    "Scotland", "Turkey", "Greece", "Belgium", "Denmark", "Sweden", "Norway",
    "Saudi Arabia", "Egypt", "Morocco", "Japan", "South Korea", "China", "Australia",
    "India", "Pakistan", "South Africa", "Nigeria",
)

internal fun radarChannelNeedsHealthProbe(browseStreamUrl: String): Boolean =
    browseStreamUrl.isNotBlank()

@HiltViewModel
class SportsHubViewModel @Inject constructor(
    val repository: RadarRepository,
    private val matcher: RadarChannelMatcher,
    private val catalogClient: com.nuvio.tv.core.radar.RadarCatalogClient,
    private val epgMirror: com.nuvio.tv.core.epg.EpgMirrorRepository,
    accountStore: XtreamAccountStore,
) : ViewModel() {

    val uiState: StateFlow<RadarUiState> = repository.uiState

    val hasPlaylists: StateFlow<Boolean> = accountStore.accounts
        .map { list -> list.any { it.enabled } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _sheet = MutableStateFlow<MatchSheetState?>(null)
    val sheet: StateFlow<MatchSheetState?> = _sheet.asStateFlow()

    private var matchJob: Job? = null

    init {
        // The match sheet reads the mirror; refresh it (12h TTL, no-op when fresh) so the
        // EPG tier is warm by the time a fixture is opened.
        viewModelScope.launch { epgMirror.ensureFresh() }
    }

    fun ensureLoaded() = repository.ensureLoaded()

    // --- add a league ---------------------------------------------------------
    // Leagues we didn't curate. The user adds them to their OWN account, which is why the
    // published catalog can stay small instead of growing into everyone's Browse list.

    private val _leagueSearch = MutableStateFlow(LeagueSearchState())
    val leagueSearch: StateFlow<LeagueSearchState> = _leagueSearch.asStateFlow()

    private var searchJob: Job? = null

    fun searchLeaguesIn(sport: String, country: String) {
        searchJob?.cancel()
        _leagueSearch.value = LeagueSearchState(sport = sport, country = country, loading = true)
        searchJob = viewModelScope.launch {
            val results = catalogClient.searchLeagues(country = country, sport = sport)
            _leagueSearch.update { it.copy(loading = false, results = results) }
        }
    }

    /**
     * Free-text league search, for when the user knows the name but not which country
     * TheSportsDB files it under (Primeira Liga sits under Portugal, the Champions League
     * under no country at all).
     */
    fun searchLeaguesByName(query: String) {
        val text = query.trim()
        searchJob?.cancel()
        if (text.length < MIN_LEAGUE_QUERY) {
            _leagueSearch.value = LeagueSearchState()
            return
        }
        _leagueSearch.value = LeagueSearchState(query = text, loading = true)
        searchJob = viewModelScope.launch {
            val results = catalogClient.searchLeagues(text = text)
            _leagueSearch.update { if (it.query == text) it.copy(loading = false, results = results) else it }
        }
    }

    fun clearLeagueSearch() {
        searchJob?.cancel()
        _leagueSearch.value = LeagueSearchState()
    }

    fun toggleFollow(league: RadarLeague) = repository.toggleFollow(league)

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
            // Broadcaster listings are one cached edge-fn call; bounded so a slow network
            // can't hold the whole sheet hostage (matching proceeds without them).
            val stations = kotlinx.coroutines.withTimeoutOrNull(4_000) {
                repository.tvStations(fixture.id)
            } ?: emptyList()
            val result = matcher.match(fixture, league, stations, onPartial = { partial ->
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
                val url = runCatching { matcher.playbackUrlFor(candidate) }.getOrNull()
                if (url != null && isChannelPlayable(candidate, url)) {
                    matcher.ensurePlayable(candidate, url)
                    closeMatch()
                    onPlay(candidate.channel.name, url, candidate.channel.contentId)
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

    /**
     * A channel that lists WITH a URL (Xtream, M3U) gets health-probed. A channel that lists
     * without one (Stalker) must not be: [RadarChannelMatcher.playbackUrlFor] just minted a
     * single-use create_link, and reading a byte off it burns the very link the player is about
     * to open. Resolving at all is the liveness signal there, and a channel that dies between
     * resolve and play still hits the player's one-shot link refresh.
     */
    private suspend fun isChannelPlayable(
        candidate: RadarChannelMatcher.ChannelMatch,
        resolvedUrl: String,
    ): Boolean = !radarChannelNeedsHealthProbe(candidate.channel.streamUrl) || isStreamAlive(resolvedUrl)

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
