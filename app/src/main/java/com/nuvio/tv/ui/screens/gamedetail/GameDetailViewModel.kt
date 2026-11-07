package com.nuvio.tv.ui.screens.gamedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.XtreamLiveChannelIdentity
import com.nuvio.tv.core.iptv.XtreamLivePlaylist
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.radar.RadarChannelMatcher
import com.nuvio.tv.core.radar.RadarFixture
import com.nuvio.tv.core.radar.RadarPendingMatch
import com.nuvio.tv.core.radar.RadarRepository
import com.nuvio.tv.core.radar.RadarTime
import com.nuvio.tv.core.sports.detail.SportsDetailService
import com.nuvio.tv.core.sports.detail.screen.GameDetailEntry
import com.nuvio.tv.core.sports.detail.screen.GameDetailPoller
import com.nuvio.tv.core.sports.detail.screen.GameDetailUiReducer
import com.nuvio.tv.core.sports.detail.screen.GameDetailUiState
import com.nuvio.tv.core.sports.detail.screen.PrimaryAction
import com.nuvio.tv.core.sports.detail.screen.WatchCandidate
import com.nuvio.tv.core.sports.detail.screen.WatchResolver
import com.nuvio.tv.core.sports.detail.screen.WatchUi
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.playback.core.PlaybackProfileId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * One open game screen: resolves the fixture the Sports hub holds, runs the game-detail core through a
 * [GameDetailPoller] (driven by the screen's RESUMED lifecycle), and reuses the existing sports channel
 * matcher for the Watch block. All decisions live in pure, unit-tested objects; this class only wires them.
 */
@HiltViewModel
class GameDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: RadarRepository,
    private val matcher: RadarChannelMatcher,
    private val sportsDetail: SportsDetailService,
    private val livePlaylist: XtreamLivePlaylist,
    private val profileManager: ProfileManager,
    accountStore: XtreamAccountStore,
    private val pendingMatch: RadarPendingMatch,
) : ViewModel() {
    private val gameId: String = savedState.get<String>("gameId").orEmpty()
    private val watchingContentId: String? = savedState.get<String>("watching")?.takeIf { it.isNotBlank() }

    private val _ui = MutableStateFlow<GameDetailUiState>(GameDetailUiReducer.initial(RadarTime.nowMs()))
    val ui: StateFlow<GameDetailUiState> = _ui.asStateFlow()

    private val _watch = MutableStateFlow(initialWatch())
    val watch: StateFlow<WatchUi> = _watch.asStateFlow()

    private val pollerReady = CompletableDeferred<GameDetailPoller>()
    @Volatile private var fixture: RadarFixture? = null
    @Volatile private var matches: List<RadarChannelMatcher.ChannelMatch> = emptyList()
    @Volatile private var matching = true
    @Volatile private var matchingFailed = false
    @Volatile private var hasPlaylists = true
    private var matchJob: Job? = null

    init {
        viewModelScope.launch {
            accountStore.accounts.collect { list ->
                val now = list.any { it.enabled }
                val changed = now != hasPlaylists
                hasPlaylists = now
                val fx = fixture
                // A playlist added (or removed) while the screen is open re-runs the channel match.
                if (changed && fx != null) startMatching(fx) else publishWatch()
            }
        }
        // Kill switches and mapping fixes reach TV through the remote mapping: refresh it (a 304 when unchanged).
        viewModelScope.launch { runCatching { sportsDetail.syncMapping() } }
        viewModelScope.launch { bootstrap() }
    }

    private fun initialWatch() = WatchResolver.resolve(watchingContentId, watchingName(), emptyList(), true, false, true)

    private fun watchingName(): String? = watchingContentId?.let { id ->
        runCatching { livePlaylist.presentationFor(PlaybackProfileId(profileManager.activeProfileId.value.toString()), id)?.title }.getOrNull()
    }

    private suspend fun bootstrap() {
        repository.ensureLoaded()
        val found = withTimeoutOrNull(FIXTURE_WAIT_MS) {
            repository.uiState.map { s ->
                GameDetailEntry.findFixture((s.fixturesByLeague.values.asSequence() + s.fixturesByTeam.values.asSequence()).flatten().toList(), gameId)
            }.filterNotNull().first()
        }
        if (found == null) {
            _ui.value = GameDetailUiState.Failed(timedOut = true)
            return
        }
        fixture = found
        val ref = GameDetailEntry.refFor(found, live = repository.uiState.value.isLive(found, RadarTime.nowMs()))
        pollerReady.complete(
            GameDetailPoller(
                source = sportsDetail.open(ref),
                startsAtMs = ref.startsAtMs,
                initialState = ref.seedState,
                config = sportsDetail.refreshConfig(),
                clock = RadarTime::nowMs,
            ),
        )
        startMatching(found)
    }

    /** Runs while the screen is RESUMED (the caller wraps it in repeatOnLifecycle); returns when the game is idle. */
    suspend fun poll() {
        val poller = pollerReady.await()
        poller.run(::onLoad)
    }

    private fun onLoad(load: com.nuvio.tv.core.sports.detail.DetailLoad) {
        _ui.update { GameDetailUiReducer.onLoad(it, load, RadarTime.nowMs()) }
    }

    fun checkDeadline() {
        _ui.update { GameDetailUiReducer.onDeadline(it, RadarTime.nowMs()) }
    }

    fun retry() {
        _ui.value = GameDetailUiReducer.retry(RadarTime.nowMs())
        viewModelScope.launch {
            val poller = withTimeoutOrNull(FIXTURE_WAIT_MS) { pollerReady.await() }
            if (poller == null) { bootstrap(); return@launch }
            poller.retry(::onLoad)
        }
        if (matchingFailed) fixture?.let(::startMatching)
    }

    private fun startMatching(fx: RadarFixture) {
        matchJob?.cancel()
        matches = emptyList(); matching = true; matchingFailed = false; publishWatch()
        if (!hasPlaylists) { matching = false; publishWatch(); return }
        matchJob = viewModelScope.launch {
            try {
                val league = fx.leagueId?.let { repository.uiState.value.leagueById(it) }
                val result = withContext(Dispatchers.IO) {
                    matcher.match(
                        fx, league,
                        stationLookup = { repository.tvStations(fx.id) },
                        onPartial = { partial -> matches = partial; publishWatch() },
                    )
                }
                matches = result
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                matchingFailed = true
            } finally {
                matching = false
                publishWatch()
            }
        }
    }

    private fun publishWatch() {
        val candidates = matches.map {
            WatchCandidate(it.channel.contentId, it.channel.name, it.confidence.ordinal, it.score)
        }
        _watch.value = WatchResolver.resolve(watchingContentId, watchingName(), candidates, matching, matchingFailed, hasPlaylists)
    }

    /** Publishes the matched lineup (so channel up/down works) and hands the chosen channel to the player route. */
    fun playPrimary(onPlay: (contentId: String) -> Unit) {
        val channel = when (val p = _watch.value.primary) {
            is PrimaryAction.Play -> p.channel
            is PrimaryAction.Switch -> p.channel
            else -> return
        }
        val lineup = matches.distinctBy { it.channel.contentId }.mapNotNull {
            XtreamLiveChannelIdentity.from(contentId = it.channel.contentId, title = it.channel.name, logo = it.channel.logo)
        }
        if (lineup.none { it.contentId.value == channel.contentId }) return
        livePlaylist.set(PlaybackProfileId(profileManager.activeProfileId.value.toString()), lineup)
        onPlay(channel.contentId)
    }

    /** Asks Sports to open the existing channel match sheet for this game (also the no-data fallback). */
    fun requestMatchSheet() {
        pendingMatch.request(gameId)
    }

    private companion object {
        const val FIXTURE_WAIT_MS = 8_000L
    }
}
