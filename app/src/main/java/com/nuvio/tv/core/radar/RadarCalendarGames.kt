package com.nuvio.tv.core.radar

import com.nuvio.tv.core.calendar.CalendarGameItem
import com.nuvio.tv.core.calendar.CalendarGameState
import com.nuvio.tv.core.calendar.CalendarSportsSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.delay

/**
 * Pure mapping from the radar state Sports already holds to the Calendar's neutral games: fixtures of followed
 * leagues and followed teams, with the live score row where the feed has one.
 */
object RadarCalendarGames {
    fun gameId(fixture: RadarFixture): String = fixture.id ?: "${fixture.leagueId}/${fixture.event}/${fixture.ts}"

    fun state(fixture: RadarFixture, live: Boolean): CalendarGameState {
        val status = fixture.status?.trim()?.lowercase().orEmpty()
        return when {
            fixture.postponed == "yes" || status.contains("postpone") || status.contains("cancel") ||
                status.contains("abandon") -> CalendarGameState.POSTPONED
            fixture.isFinishedOrOff -> CalendarGameState.FINAL
            live -> CalendarGameState.LIVE
            else -> CalendarGameState.SCHEDULED
        }
    }

    fun games(state: RadarUiState, nowMs: Long): List<CalendarGameItem> {
        val leagueFixtures = state.followedLeagueIds.flatMap { state.fixturesByLeague[it].orEmpty() }
        val teamFixtures = state.followedTeamIds.flatMap { state.fixturesByTeam[it].orEmpty() }
        return (leagueFixtures + teamFixtures)
            .distinctBy(::gameId)
            .mapNotNull { fixture -> item(fixture, state, nowMs) }
            .sortedBy { it.kickoffMs }
    }

    private fun side(
        view: ScoreView,
        live: RadarLiveScore?,
        fixtureScore: String?,
        gameState: CalendarGameState,
        pick: (RadarLiveScore) -> String?,
    ): String? {
        if (gameState != CalendarGameState.LIVE && gameState != CalendarGameState.FINAL) return null
        val raw = when (view) {
            ScoreView.ScoreHidden, ScoreView.ScorePending -> null
            is ScoreView.ScoreShown -> view.value?.let(pick) ?: if (view.value != null) null else live?.let(pick) ?: fixtureScore
        }
        return raw?.takeIf(String::isNotBlank)
    }

    fun item(fixture: RadarFixture, state: RadarUiState, nowMs: Long): CalendarGameItem? {
        val kickoff = fixture.startEpochMs ?: return null
        val gameState = state(fixture, state.isLive(fixture, nowMs))
        val live = fixture.id?.let { state.liveScores[it] }?.takeIf { gameState == CalendarGameState.LIVE }
        // Hide scores / delay: the one policy decides what a score slot may show right now.
        val view = if (gameState == CalendarGameState.LIVE || gameState == CalendarGameState.FINAL) {
            state.scoreView(fixture, nowMs)
        } else ScoreView.ScoreShown(null)
        val league = fixture.leagueId?.let(state::leagueById)
        return CalendarGameItem(
            id = gameId(fixture),
            sport = fixture.sport,
            league = fixture.league?.takeIf(String::isNotBlank) ?: league?.name,
            leagueBadge = fixture.leagueBadge?.takeIf(String::isNotBlank) ?: league?.badge,
            home = fixture.home?.takeIf(String::isNotBlank),
            away = fixture.away?.takeIf(String::isNotBlank),
            homeBadge = fixture.homeBadge,
            awayBadge = fixture.awayBadge,
            event = fixture.event,
            kickoffMs = kickoff,
            state = gameState,
            homeScore = side(view, live, fixture.homeScore, gameState) { it.homeScore },
            awayScore = side(view, live, fixture.awayScore, gameState) { it.awayScore },
            scorePending = view is ScoreView.ScorePending,
            progress = live?.progress?.takeIf(String::isNotBlank),
            channelHintId = fixture.id
        )
    }
}

/** A game the Calendar asked Sports to open in its channel-matching sheet; Sports consumes it once. */
@Singleton
class RadarPendingMatch @Inject constructor() {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun request(gameId: String) {
        _pending.value = gameId
    }

    fun consume(gameId: String) {
        if (_pending.value == gameId) _pending.value = null
    }
}

/**
 * The Calendar's [CalendarSportsSource], fed only from what Sports already holds: the repository's in-memory
 * state when Sports has loaded this session, else the radar disk cache. No request, no poll — the radar's own
 * refresh policy (RadarLiveRefreshPolicy, lifecycle-bound in the Sports screen) stays the only fetcher.
 */
@Singleton
class RadarCalendarSportsSource @Inject constructor(
    private val repository: RadarRepository,
    private val store: RadarStore,
    private val pendingMatch: RadarPendingMatch
) : CalendarSportsSource {
    override suspend fun games(): List<CalendarGameItem> {
        val nowMs = RadarTime.nowMs()
        val held = repository.uiState.value
        if (held.fixturesByLeague.isNotEmpty() || held.fixturesByTeam.isNotEmpty()) return RadarCalendarGames.games(held, nowMs)
        val local = runCatching { store.state.first() }.getOrNull() ?: return emptyList()
        if (local.follows.isEmpty() && local.teams.isEmpty()) return emptyList()
        val cached = store.loadFixtures() ?: return emptyList()
        val fromDisk = RadarUiState(
            follows = local.follows,
            teamFollows = local.teams,
            prefs = local.prefs, // hide-scores must hold on the cold path too
            fixturesByLeague = cached.fixtures,
            fixturesByTeam = cached.teamFixtures
        )
        return RadarCalendarGames.games(fromDisk, nowMs)
    }

    override val changes: Flow<Unit>
        get() = merge(
            repository.uiState
                .map { Triple(it.fixturesByLeague, it.fixturesByTeam, it.liveScores) to (it.follows to it.teamFollows to (it.spoiler to it.scoreRev)) }
                .distinctUntilChanged()
                .drop(1)
                .map { },
            // The delay's one-shot re-render, owned by whoever collects (the open Calendar): nothing runs otherwise.
            repository.uiState
                .map { it.scoreRev to it.spoiler }
                .distinctUntilChanged()
                .transformLatest {
                    ScoreDueLoop.run(
                        nowMs = RadarTime::nowMs,
                        nextDueAtMs = repository.uiState.value::nextScoreDueAtMs,
                        wait = { delay(it) },
                        onDue = { emit(Unit) },
                    )
                },
        )

    override fun requestWatch(gameId: String) = pendingMatch.request(gameId)
}
