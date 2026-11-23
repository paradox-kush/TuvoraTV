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
import com.nuvio.tv.core.radar.DelayedValueBuffer
import com.nuvio.tv.core.radar.RadarTime
import com.nuvio.tv.core.radar.ScoreSpoilerSettings
import com.nuvio.tv.core.sports.detail.screen.GameRender
import com.nuvio.tv.core.sports.detail.screen.GameScorePolicy
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import com.nuvio.tv.core.sports.detail.SportsDetailService
import com.nuvio.tv.core.sports.detail.screen.ChannelLookupPolicy
import com.nuvio.tv.core.sports.detail.screen.GameDetailEntry
import com.nuvio.tv.core.sports.detail.screen.GameDetailPoller
import com.nuvio.tv.core.sports.detail.screen.GameDetailUiReducer
import com.nuvio.tv.core.sports.detail.screen.GameMomentPolicy
import com.nuvio.tv.core.sports.detail.screen.MomentBatch
import com.nuvio.tv.core.sports.detail.screen.GameDetailUiState
import com.nuvio.tv.core.sports.detail.screen.PreviewFailure
import com.nuvio.tv.core.sports.detail.screen.PreviewResolver
import com.nuvio.tv.core.sports.detail.screen.PreviewSlot
import com.nuvio.tv.core.sports.detail.screen.PrimaryAction
import com.nuvio.tv.core.sports.detail.screen.WatchCandidate
import com.nuvio.tv.core.sports.detail.screen.WatchResolver
import com.nuvio.tv.core.sports.detail.screen.WatchUi
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.playback.core.PlaybackProfileId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

enum class GameScoreMode { SHOWN, HIDDEN, PENDING }

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

    private val _moments = MutableStateFlow(MomentBatch.None)
    /** What really changed in the latest update (score, win chance, newest play); the screen animates each batch once. */
    val moments: StateFlow<MomentBatch> = _moments.asStateFlow()
    @Volatile private var lastDetail: com.nuvio.tv.core.sports.detail.GameDetail? = null

    // Hide scores / score delay: the raw model of each tick is recorded where it lands (onLoad) and the screen
    // renders what GameScorePolicy allows right now. Pure policy; this class only wires it.
    private val scoreBuffer = DelayedValueBuffer<String, com.nuvio.tv.core.sports.detail.GameDetail>()
    private var latestRaw: com.nuvio.tv.core.sports.detail.GameDetail? = null
    private var finishedAtFirstSight: Boolean? = null
    private var renderMode: GameScoreMode? = null
    private var renderJob: Job? = null
    private val _scoreMode = MutableStateFlow(GameScoreMode.SHOWN)
    /** Shown / Hidden / Pending for the scoreboard (Hidden shows the "Scores hidden" chip, Pending shows "-"). */
    val scoreMode: StateFlow<GameScoreMode> = _scoreMode.asStateFlow()
    val spoilerSettings: StateFlow<ScoreSpoilerSettings> = repository.uiState
        .map { it.spoiler }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScoreSpoilerSettings())

    /** The chip beside the scoreboard flips the same global setting as the Sports header. */
    fun setScoresShown(shown: Boolean) = repository.setScoresShown(shown)

    private val _watch = MutableStateFlow(initialWatch())
    val watch: StateFlow<WatchUi> = _watch.asStateFlow()

    private val _preview = MutableStateFlow<PreviewSlot>(PreviewSlot.Hidden)
    val preview: StateFlow<PreviewSlot> = _preview.asStateFlow()
    @Volatile private var gameState: com.nuvio.tv.core.sports.detail.GameState? = null
    @Volatile private var previewFailure: PreviewFailure? = null

    /** The preview failed once (rejected host / failed stream): show "Preview unavailable" and never retry on a loop. */
    fun markPreviewFailed(failure: PreviewFailure) {
        if (previewFailure != null) return
        previewFailure = failure
        // Reason code only: never a URL, host, channel or team name.
        runCatching { com.posthog.PostHog.capture(event = "sports_preview_failed", properties = mapOf("code" to failure.code)) }
        android.util.Log.w("GameDetailVM", "preview failed: ${failure.code}")
        publishWatch()
    }

    private val pollerReady = CompletableDeferred<GameDetailPoller>()
    @Volatile private var fixture: RadarFixture? = null
    @Volatile private var matches: List<RadarChannelMatcher.ChannelMatch> = emptyList()
    @Volatile private var matching = true
    @Volatile private var matchingFailed = false
    @Volatile private var hasPlaylists = true
    @Volatile private var finished = false
    @Volatile private var replays: Map<String, RadarChannelMatcher.SportsReplay> = emptyMap()
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
        // A change of the hide/delay setting re-renders the held model at once (no fetch).
        viewModelScope.launch { spoilerSettings.drop(1).collect { publishRendered() } }
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
        finished = found.isFinishedOrOff
        val ref = GameDetailEntry.refFor(found, live = repository.uiState.value.isLive(found, RadarTime.nowMs()))
        pollerReady.complete(
            GameDetailPoller(
                source = sportsDetail.open(ref),
                startsAtMs = ref.startsAtMs,
                initialState = ref.seedState,
                config = sportsDetail.refreshConfig(),
                clock = RadarTime::nowMs,
                wantsUpcomingSummary = ref.sport.equals("Soccer", ignoreCase = true),
            ),
        )
        startMatching(found)
    }

    /** Runs while the screen is RESUMED (the caller wraps it in repeatOnLifecycle); returns when the game is idle. */
    suspend fun poll() {
        val poller = pollerReady.await()
        // The first snapshot after a resume is `prev = null`: nothing animates on app resume (spec 05 D2).
        lastDetail = null
        poller.run(::onLoad)
    }

    private fun onLoad(load: com.nuvio.tv.core.sports.detail.DetailLoad) {
        if (load is com.nuvio.tv.core.sports.detail.DetailLoad.Loaded) {
            synchronized(this) {
                if (finishedAtFirstSight == null) finishedAtFirstSight = load.detail.state == com.nuvio.tv.core.sports.detail.GameState.FINAL
                scoreBuffer.record(gameId, load.detail, RadarTime.nowMs())
                latestRaw = load.detail
            }
            publishRendered()
        } else {
            _ui.update { GameDetailUiReducer.onLoad(it, load, RadarTime.nowMs()) }
        }
    }

    /** Renders the held raw model under the current setting; schedules the one local re-render when a delayed snapshot comes due. */
    @Synchronized
    private fun publishRendered() {
        val raw = latestRaw ?: return
        val now = RadarTime.nowMs()
        val settings = spoilerSettings.value
        val render = GameScorePolicy.render(settings, gameId, scoreBuffer, raw, finishedAtFirstSight == true, now) ?: return
        val mode = when (render) {
            is GameRender.Shown -> GameScoreMode.SHOWN
            is GameRender.Hidden -> GameScoreMode.HIDDEN
            is GameRender.Pending -> GameScoreMode.PENDING
        }
        // A mode flip is not a score change: nothing animates on reveal.
        if (mode != renderMode) { lastDetail = null; renderMode = mode }
        _scoreMode.value = mode
        gameState = raw.state; publishWatch()
        val diff = GameMomentPolicy.diff(lastDetail, render.detail)
        lastDetail = render.detail
        if (diff.isNotEmpty()) _moments.update { prev -> MomentBatch(prev.seq + 1, diff) }
        _ui.update { GameDetailUiReducer.onLoad(it, com.nuvio.tv.core.sports.detail.DetailLoad.Loaded(render.detail), now) }
        renderJob?.cancel()
        val due = scoreBuffer.nextChangeAtMs(now, settings.delayMs) ?: return
        renderJob = viewModelScope.launch {
            kotlinx.coroutines.delay((due - now).coerceAtLeast(0L) + 50L)
            publishRendered()
        }
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
        matches = emptyList(); replays = emptyMap(); matching = true; matchingFailed = false; publishWatch()
        if (!hasPlaylists) { matching = false; publishWatch(); return }
        matchJob = viewModelScope.launch {
            try {
                val league = fx.leagueId?.let { repository.uiState.value.leagueById(it) }
                // Bounded: a slow provider ends as "couldn't check your channels" (Retry), never an endless spinner.
                // Partial matches found before the deadline are kept and stay playable.
                // (match() hops to its own background dispatcher internally.)
                val deadline = if (finished) WatchResolver.FINISHED_LOOKUP_DEADLINE_MS else WatchResolver.LOOKUP_DEADLINE_MS
                suspend fun lookup() = withTimeoutOrNull(deadline) {
                    matcher.match(
                        fx, league,
                        stationLookup = { repository.tvStations(fx.id) },
                        onPartial = { partial -> matches = partial; publishWatch() },
                    )
                }
                // The first lookup after a cold start can outlast the deadline while a playlist's lineup still downloads;
                // only then one quiet second attempt (the download keeps running and is ready for it). Any other timeout
                // stands: repeating the same slow work only doubled the wait before "Couldn't check" (ChannelLookupPolicy).
                val started = android.os.SystemClock.elapsedRealtime()
                var result = lookup()
                val first = android.os.SystemClock.elapsedRealtime() - started
                if (result == null && ChannelLookupPolicy.retryAfterTimeout(matches.size, matcher.inFlightLineupLoads())) result = lookup()
                android.util.Log.i("GameDetailVM", "channel lookup first=${first}ms ok=${result != null} matches=${matches.size}")
                if (result != null) matches = result else if (matches.isEmpty()) matchingFailed = true
                if (finished && result != null) {
                    // Which of the matched channels can replay this game from kick-off (the guide's own rule).
                    replays = buildMap {
                        result.forEach { m ->
                            val r = try { matcher.replayFor(m, fx) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                            if (r != null) put(m.channel.contentId, r)
                        }
                    }
                }
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
            WatchCandidate(
                it.channel.contentId, it.channel.name, it.confidence.ordinal, it.score,
                logo = it.channel.logo, singleUse = it.channel.streamUrl.isBlank(),
            )
        }
        _preview.value = PreviewResolver.resolve(
            gameState, watchingContentId, watchingName(), candidates, matching, matchingFailed, hasPlaylists, previewFailure,
        )
        _watch.value = WatchResolver.resolve(watchingContentId, watchingName(), candidates, matching, matchingFailed, hasPlaylists, finished, replays.keys)
    }

    /** Publishes the matched lineup (so channel up/down works) and hands the chosen channel to the player route. */
    fun playPrimary(onPlay: (contentId: String) -> Unit) {
        val channel = when (val p = _watch.value.primary) {
            is PrimaryAction.Play -> p.channel
            is PrimaryAction.Switch -> p.channel
            else -> return
        }
        if (!publishLineup(channel.contentId)) return
        onPlay(channel.contentId)
    }

    /**
     * Publishes the matched channels as the current live lineup (the playback layer resolves a channel only through it,
     * and channel up/down walks it). Used before the player opens AND before a preview attaches. False if [contentId] is not in it.
     */
    fun publishLineup(contentId: String): Boolean {
        val lineup = matches.distinctBy { it.channel.contentId }.mapNotNull {
            XtreamLiveChannelIdentity.from(contentId = it.channel.contentId, title = it.channel.name, logo = it.channel.logo)
        }
        if (lineup.none { it.contentId.value == contentId }) return false
        livePlaylist.set(PlaybackProfileId(profileManager.activeProfileId.value.toString()), lineup)
        return true
    }

    /** Starts the catch-up session for the replay shown on a finished game and hands the launch to the screen. */
    fun playReplay(onPlay: (url: String, title: String, contentId: String, startMs: Long, endMs: Long) -> Unit) {
        val channel = (_watch.value.primary as? PrimaryAction.Replay)?.channel ?: return
        val replay = replays[channel.contentId] ?: return
        viewModelScope.launch {
            val session = runCatching { matcher.beginReplay(replay) }.getOrNull() ?: return@launch
            onPlay(session.url, replay.title, session.contentId, replay.programmeStartMs, replay.programmeEndMs)
        }
    }

    /** "Find channels" / "Retry" on the Watch block. */
    fun retryMatching() {
        fixture?.let(::startMatching)
    }

    /** Asks Sports to open the existing channel match sheet for this game (also the no-data fallback). */
    fun requestMatchSheet() {
        pendingMatch.request(gameId)
    }

    private companion object {
        const val FIXTURE_WAIT_MS = 8_000L
    }
}
