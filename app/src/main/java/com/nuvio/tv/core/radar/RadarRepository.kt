package com.nuvio.tv.core.radar

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val TAG = "RadarRepository"
private const val RADAR_PRE_KICKOFF_GRACE_MS = 10L * 60 * 1000

data class RadarUiState(
    val catalog: RadarCatalog = RadarCatalog(),
    val follows: List<RadarFollow> = emptyList(),
    val prefs: RadarPrefs = RadarPrefs(),
    val fixturesByLeague: Map<String, List<RadarFixture>> = emptyMap(),
    val liveEventIds: Set<String> = emptySet(),
    /** eventId -> latest livescore row (in-progress score + minute) for covered sports. */
    val liveScores: Map<String, RadarLiveScore> = emptyMap(),
    /** Sports the last FRESH fetch returned livescore data for — the feed is authoritative
     *  for these (empty after a cold-start disk load: stale feed data must not suppress
     *  the time-window inference). */
    val livescoreSports: Set<String> = emptySet(),
    val loadingFixtures: Boolean = false,
    /** Followed clubs (always user-added — there is no published team catalog). */
    val teamFollows: List<RadarTeamFollow> = emptyList(),
    /** teamId -> that club's own schedule, from the team lane of radar-fixtures. */
    val fixturesByTeam: Map<String, List<RadarFixture>> = emptyMap(),
    /** Hide-scores / score-delay bookkeeping, shared with the repository (recording happens there). */
    val scores: ScoreObservations = ScoreObservations(),
    /** Bumped whenever [scores] changed (a recording, or a delayed snapshot became due) so collectors re-render. */
    val scoreRev: Int = 0,
) {
    val spoiler: ScoreSpoilerSettings get() = prefs.spoiler

    /** May this fixture's score be shown right now? (hidden / delayed / shown) */
    fun scoreView(fixture: RadarFixture, nowMs: Long): ScoreView =
        scores.scoreView(spoiler, fixture, fixture.id?.let { liveScores[it] }, nowMs)

    /** When the next buffered score becomes due (a local re-render, no fetch); null when nothing is pending. */
    fun nextScoreDueAtMs(nowMs: Long): Long? =
        spoiler.takeIf { it.shown }?.let { scores.nextChangeAtMs(nowMs, it.delayMs) }

    val followedLeagueIds: Set<String> get() = follows.map { it.leagueId }.toSet()
    val followedTeamIds: Set<String> get() = teamFollows.map { it.teamId }.toSet()

    /** Followed clubs in follow order, as the picker/search shape. */
    val followedTeams: List<RadarTeam> get() = teamFollows.sortedBy { it.sortOrder }.map { it.asTeam() }

    /** Fixtures of the given clubs that are live or upcoming, soonest first. */
    fun upcomingForTeams(teamIds: Collection<String>, nowMs: Long, cap: Int = 20): List<RadarFixture> =
        teamIds.asSequence()
            .flatMap { fixturesByTeam[it].orEmpty() }
            .distinctBy { it.id ?: "${it.leagueId}/${it.event}/${it.ts}" }
            .filter { fx ->
                fx.isUpcomingWindow(nowMs) || isLive(fx, nowMs)
            }
            .sortedBy { it.startEpochMs }
            .take(cap)
            .toList()

    /**
     * Catalog first, then the user's own follows — a league someone added themselves isn't in
     * the catalog, and everything downstream (fixture rows, the match sheet, channel matching)
     * resolves names and badges through here.
     */
    fun leagueById(id: String): RadarLeague? =
        catalog.categories.asSequence().flatMap { it.leagues }.firstOrNull { it.id == id }
            ?: follows.firstOrNull { it.leagueId == id }?.asLeague()

    /** Leagues the user added that aren't in the published catalog, in follow order. */
    val customLeagues: List<RadarLeague>
        get() = follows.sortedBy { it.sortOrder }.mapNotNull { it.asLeague() }

    fun activeFeatured(nowMs: Long): List<RadarFeaturedEvent> =
        catalog.featured.filter { it.isActive(nowMs) }

    fun isLive(fixture: RadarFixture, nowMs: Long): Boolean {
        // A finished / postponed / cancelled fixture is never live, however stale the feed's live
        // set is — the strStatus the badge logic used to ignore (inventory T1/BK1: a Sunday NFL
        // game reading LIVE on Monday because its id lingered in the stale-served live set).
        if (fixture.isFinishedOrOff) return false
        // Hard plausibility cap: even a feed-covered sport cannot read LIVE past a sport-typical
        // window plus grace — the backstop for a stale live-set entry whose finished status we
        // never received.
        fixture.startEpochMs?.let { start ->
            // Before kickoff a fixture is never live, even when the livescore feed already lists
            // its id — feeds pre-populate the live set for imminent games. Symmetric to the
            // max-window cap; the grace absorbs feed/clock skew. B49 (unstarted games badged LIVE).
            if (nowMs < start - RADAR_PRE_KICKOFF_GRACE_MS) return false
            if (nowMs >= start + fixture.maxLiveWindowMs()) return false
        }
        val feedConfirmed = fixture.id?.let { it in liveEventIds } == true
        val sport = fixture.sport?.lowercase()
        // Fresh feed coverage for this sport -> the feed decides; otherwise infer from kick-off.
        return if (sport != null && sport in livescoreSports) feedConfirmed
        else feedConfirmed || fixture.inferredLive(nowMs)
    }

    /** Finished/started fixtures of one league, most recent first (scores when the API has them). */
    fun recent(leagueId: String, nowMs: Long, cap: Int = 15): List<RadarFixture> =
        fixturesByLeague[leagueId].orEmpty()
            .distinctBy { it.id ?: "${it.leagueId}/${it.event}/${it.ts}" }
            .filter { fx ->
                val start = fx.startEpochMs ?: return@filter false
                start < nowMs && !isLive(fx, nowMs) && !(fx.isDateOnly && fx.isUpcomingWindow(nowMs))
            }
            .sortedByDescending { it.startEpochMs }
            .take(cap)

    fun upcoming(leagueIds: Collection<String>, nowMs: Long, cap: Int = 20): List<RadarFixture> =
        leagueIds.asSequence()
            .flatMap { fixturesByLeague[it].orEmpty() }
            .distinctBy { it.id ?: "${it.leagueId}/${it.event}/${it.ts}" }
            .filter { fx ->
                fx.isUpcomingWindow(nowMs) || isLive(fx, nowMs)
            }
            .sortedBy { it.startEpochMs }
            .take(cap)
            .toList()
}

/**
 * Sports Centre state on TV: curated catalog + per-profile follows/prefs (RadarStore, synced)
 * + throttled fixtures via the radar-fixtures edge function. The store flow is profile-reactive
 * (flatMapLatest on activeProfileId) so profile switches propagate automatically; the in-memory
 * fixtures reset alongside via the state collector.
 */
@Singleton
class RadarRepository @Inject constructor(
    private val store: RadarStore,
    private val fixturesClient: RadarFixturesClient,
    private val catalogClient: RadarCatalogClient,
    private val syncService: RadarSyncService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val scoreObservations = ScoreObservations()
    private val _uiState = MutableStateFlow(RadarUiState(scores = scoreObservations))
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    private var lastFetchMark: TimeMark? = null
    private val fetchTtl = 15.minutes
    // Leagues change on the order of weeks; this only needs to be faster than a release.
    private val catalogTtl = 6.hours
    private var started = false

    fun ensureLoaded() {
        if (!started) {
            started = true
            // Bundled copy first so the tab is never empty and never waits on the network.
            // The cached and remote catalogs layer on top, in that order.
            val catalog = runCatching { json.decodeFromString<RadarCatalog>(RadarCatalogData.JSON) }
                .getOrDefault(RadarCatalog())
            _uiState.update { it.copy(catalog = catalog) }
            scope.launch {
                store.loadCatalog()?.let { cached ->
                    if (cached.catalog.isUsable()) {
                        _uiState.update { it.copy(catalog = cached.catalog) }
                    }
                }
                refreshCatalog()
                store.loadFixtures()?.let { cached ->
                    // Disk cache = old news: first-sight bookkeeping only, nothing is buffered.
                    scoreObservations.noteCached(
                        cached.fixtures.values.flatten() + cached.teamFixtures.values.flatten(),
                        cached.livescore.values.flatten(),
                        RadarTime.nowMs(),
                    )
                    _uiState.update {
                        it.copy(
                            fixturesByLeague = cached.fixtures,
                            fixturesByTeam = cached.teamFixtures,
                            liveEventIds = liveIds(cached),
                            liveScores = scoresById(cached),
                        )
                    }
                }
                // Profile-reactive: any follows/prefs change (local edit, sync pull, profile
                // switch) lands here and re-evaluates what to fetch. Force past the throttle
                // when a followed league has NO cached fixtures yet (new follow / profile
                // switch to different follows) so it doesn't sit empty for a TTL.
                store.state.collect { local ->
                    _uiState.update {
                        it.copy(follows = local.follows, prefs = local.prefs, teamFollows = local.teams, scoreRev = it.scoreRev + 1)
                    }
                    val uncovered = local.follows.any { it.leagueId !in _uiState.value.fixturesByLeague } ||
                        local.teams.any { it.teamId !in _uiState.value.fixturesByTeam }
                    refreshFixtures(force = uncovered)
                }
            }
        } else {
            refreshFixtures()
        }
    }

    /**
     * Pulls the published catalog when the cached copy is older than [catalogTtl].
     *
     * Deliberately quiet: a failed fetch, an unpublished catalog (payload null), or a
     * document that doesn't validate all leave whatever is already loaded in place. The
     * only way this changes what the user sees is a well-formed publish.
     */
    private suspend fun refreshCatalog() {
        val cached = store.loadCatalog()
        val ageMs = cached?.let { RadarTime.nowMs() - it.fetchedAtMs } ?: Long.MAX_VALUE
        if (ageMs < catalogTtl.inWholeMilliseconds) return

        val envelope = catalogClient.fetch() ?: return
        val remote = envelope.payload ?: return
        if (!remote.isUsable()) {
            Log.w(TAG, "published catalog v${envelope.version} failed validation; keeping current")
            return
        }
        store.saveCatalog(RadarCachedCatalog(envelope.version, remote, RadarTime.nowMs()))
        _uiState.update { it.copy(catalog = remote) }
        Log.i(TAG, "adopted published catalog v${envelope.version} (${remote.categories.sumOf { c -> c.leagues.size }} leagues)")
    }

    fun refreshFixtures(force: Boolean = false) {
        val mark = lastFetchMark
        if (!force && mark != null && mark.elapsedNow() < fetchTtl) return
        val nowMs = RadarTime.nowMs()
        val state = _uiState.value
        val leagues = state.followedLeagueIds + state.activeFeatured(nowMs).map { it.leagueId }
        val teams = state.followedTeamIds
        if (leagues.isEmpty() && teams.isEmpty()) return
        lastFetchMark = TimeSource.Monotonic.markNow()
        val sports = (
            leagues.mapNotNull { id -> state.leagueById(id)?.sport?.lowercase() } +
                // A followed club may be the only reason a sport is on screen at all.
                state.teamFollows.map { it.sport.lowercase() }
            ).filter { it in RADAR_LIVESCORE_SPORTS }.toSet()
        _uiState.update { it.copy(loadingFixtures = true) }
        scope.launch {
            val response = fixturesClient.fetch(leagues, sports, teams)
            if (response == null) {
                _uiState.update { it.copy(loadingFixtures = false) }
                lastFetchMark = null // failed: retry on next entry instead of waiting out the TTL
                return@launch
            }
            recordScores(response)
            _uiState.update {
                it.copy(
                    fixturesByLeague = it.fixturesByLeague + response.fixtures,
                    fixturesByTeam = it.fixturesByTeam + response.teamFixtures,
                    liveEventIds = liveIds(response),
                    liveScores = scoresById(response),
                    // An empty upstream lane is not authoritative coverage. Treating it as such
                    // suppresses kickoff-time inference (notably for NFL games where the live feed
                    // can be empty while the fixture endpoint already reports Q1 and a score).
                    livescoreSports = coveredLivescoreSports(response),
                    loadingFixtures = false,
                )
            }
            // Persist the MERGED map — persisting only the raw response would drop leagues
            // this (possibly partial) response omitted from the offline cache.
            store.saveFixtures(
                response.copy(
                    fixtures = _uiState.value.fixturesByLeague,
                    teamFixtures = _uiState.value.fixturesByTeam,
                ),
            )
        }
    }

    /**
     * Fast score tick for the Sports screen: refresh ONLY followed leagues/teams that have a
     * live-or-imminent fixture ([RadarLiveRefreshPolicy]), so the 2-minute poll transfers a live
     * subset — often nothing — instead of the whole followed set every time. Merges results the
     * same way [ensureLeagueLoaded] does (the livescore feed is per-sport and authoritative for the
     * sports it returns; stale live ids are already defended by [RadarUiState.isLive]). Leaves the
     * full-refresh throttle untouched: schedule discovery stays with [refreshFixtures].
     */
    fun refreshLiveFixtures() {
        val nowMs = RadarTime.nowMs()
        val state = _uiState.value
        val candidateLeagues = state.followedLeagueIds + state.activeFeatured(nowMs).map { it.leagueId }
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = candidateLeagues,
            candidateTeamIds = state.followedTeamIds,
            fixturesByLeague = state.fixturesByLeague,
            fixturesByTeam = state.fixturesByTeam,
            nowMs = nowMs,
        )
        if (targets.isEmpty) return // nothing live/imminent → no network call at all
        val sports = (
            targets.leagueIds.mapNotNull { id -> state.leagueById(id)?.sport?.lowercase() } +
                state.teamFollows.filter { it.teamId in targets.teamIds }.map { it.sport.lowercase() }
            ).filter { it in RADAR_LIVESCORE_SPORTS }.toSet()
        scope.launch {
            val response = fixturesClient.fetch(targets.leagueIds, sports, targets.teamIds) ?: return@launch
            recordScores(response)
            _uiState.update {
                it.copy(
                    fixturesByLeague = it.fixturesByLeague + response.fixtures,
                    fixturesByTeam = it.fixturesByTeam + response.teamFixtures,
                    liveEventIds = it.liveEventIds + liveIds(response),
                    liveScores = it.liveScores + scoresById(response),
                    livescoreSports = it.livescoreSports + coveredLivescoreSports(response),
                )
            }
            store.saveFixtures(
                response.copy(
                    fixtures = _uiState.value.fixturesByLeague,
                    teamFixtures = _uiState.value.fixturesByTeam,
                ),
            )
        }
    }

    fun toggleFollow(league: RadarLeague) {
        scope.launch {
            // Read the STORE, not _uiState — the ui mirror updates async via the collector,
            // so rapid consecutive toggles would silently drop earlier writes.
            val current = store.state.first()
            val without = current.follows.filterNot { it.leagueId == league.id }
            // A league that isn't in the published catalog carries its own metadata on the
            // follow — nothing else would be able to name or draw it later.
            val inCatalog = _uiState.value.catalog.categories
                .any { category -> category.leagues.any { it.id == league.id } }
            val follows = if (without.size == current.follows.size) {
                without + RadarFollow(
                    leagueId = league.id,
                    sport = league.sport ?: "",
                    sortOrder = without.size,
                    name = league.name.takeUnless { inCatalog },
                    badge = league.badge.takeUnless { inCatalog },
                    banner = league.banner.takeUnless { inCatalog },
                    keywords = if (inCatalog) emptyList() else league.keywords,
                    custom = !inCatalog,
                )
            } else {
                without
            }
            store.saveState(current.copy(follows = follows))
            syncService.triggerRemoteSync()
            refreshFixtures(force = true)
        }
    }

    /**
     * Follow/unfollow a club. Unlike a league there is no catalog to fall back on, so the
     * whole team travels onto the follow row — dropping it would leave nothing to name,
     * draw or channel-match the club with later.
     */
    fun toggleFollowTeam(team: RadarTeam) {
        scope.launch {
            // Read the STORE, not _uiState — same reason as toggleFollow.
            val current = store.state.first()
            val without = current.teams.filterNot { it.teamId == team.id }
            val teams = if (without.size == current.teams.size) {
                without + team.asFollow(sortOrder = without.size)
            } else {
                without
            }
            store.saveState(current.copy(teams = teams))
            syncService.triggerRemoteSync()
            refreshFixtures(force = true)
        }
    }

    fun setOptIn(featuredEventId: String, accepted: Boolean) {
        scope.launch {
            val current = store.state.first()
            val prefs = current.prefs.copy(
                featuredEventId = featuredEventId,
                optInState = if (accepted) RadarOptIn.ACCEPTED else RadarOptIn.DECLINED,
            )
            store.saveState(current.copy(prefs = prefs))
            syncService.triggerRemoteSync()
        }
    }

    /** Hide scores everywhere (false) or show them again. One global per-profile setting; synced. */
    fun setScoresShown(shown: Boolean) {
        scope.launch {
            // Value and dirty flag land in ONE store transaction: a pull can never see one without the other.
            store.updateState { it.copy(prefs = SpoilerSync.markChanged(it.prefs, shown = shown)) }
            syncService.triggerRemoteSync()
        }
    }

    /** Score delay in seconds; anything outside [ScoreSpoilerSettings.ALLOWED_DELAYS] is stored as 0. */
    fun setScoreDelaySeconds(seconds: Int) {
        scope.launch {
            store.updateState { it.copy(prefs = SpoilerSync.markChanged(it.prefs, delayS = seconds)) }
            syncService.triggerRemoteSync()
        }
    }

    /** Record what a fetch brought into the delay buffer (fixtures first: first-sight bookkeeping). */
    private fun recordScores(response: RadarFixturesResponse) {
        scoreObservations.note(
            fixtures = response.fixtures.values.flatten() + response.teamFixtures.values.flatten(),
            liveRows = response.livescore.values.flatten(),
            nowMs = RadarTime.nowMs(),
        )
        bumpScoreRev()
    }

    /**
     * Asks collectors for one local re-render (a delayed snapshot became due). The TIMER lives with the
     * visible screen ([RadarUiState.nextScoreDueAtMs] + repeatOnLifecycle), never here: nothing runs
     * while no screen shows scores, and there is no shared job to race on.
     */
    fun bumpScoreRev() {
        _uiState.update { it.copy(scoreRev = it.scoreRev + 1) }
    }

    /**
     * On-demand fetch for a league the user is BROWSING (league/event page) — followed
     * leagues load via [refreshFixtures]; discovery must not depend on following.
     */
    fun ensureLeagueLoaded(leagueId: String) {
        if (_uiState.value.fixturesByLeague.containsKey(leagueId)) return
        val sport = _uiState.value.leagueById(leagueId)?.sport?.lowercase()
        val sports = if (sport != null && sport in RADAR_LIVESCORE_SPORTS) setOf(sport) else emptySet()
        scope.launch {
            val response = fixturesClient.fetch(listOf(leagueId), sports) ?: return@launch
            recordScores(response)
            _uiState.update {
                it.copy(
                    fixturesByLeague = it.fixturesByLeague + response.fixtures,
                    liveEventIds = it.liveEventIds + liveIds(response),
                    liveScores = it.liveScores + scoresById(response),
                    livescoreSports = it.livescoreSports + coveredLivescoreSports(response),
                )
            }
        }
    }

    private fun liveIds(response: RadarFixturesResponse): Set<String> =
        response.livescore.values.asSequence().flatten().mapNotNull { it.eventId }.toSet()

    private fun scoresById(response: RadarFixturesResponse): Map<String, RadarLiveScore> =
        response.livescore.values.asSequence().flatten()
            .mapNotNull { score -> score.eventId?.let { it to score } }
            .toMap()

    private fun coveredLivescoreSports(response: RadarFixturesResponse): Set<String> =
        response.livescore.asSequence()
            .filter { (_, scores) -> scores.isNotEmpty() }
            .map { (sport, _) -> sport.lowercase() }
            .toSet()

    // Broadcaster listings barely change and the edge function caches them 12h — one
    // fetch per event per app session is plenty.
    private val tvCache = java.util.concurrent.ConcurrentHashMap<String, List<RadarTvStation>>()

    /** TheSportsDB broadcaster list for a fixture (session-cached; empty when unknown). */
    suspend fun tvStations(eventId: String?): List<RadarTvStation> {
        if (eventId.isNullOrBlank()) return emptyList()
        tvCache[eventId]?.let { return it }
        val fetched = fixturesClient.fetchTv(eventId)
        if (fetched.isNotEmpty()) tvCache[eventId] = fetched
        return fetched
    }
}
