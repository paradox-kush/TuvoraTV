package com.nuvio.tv.ui.screens.radar

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.core.radar.RadarCategory
import com.nuvio.tv.core.radar.RadarChannelMatcher
import com.nuvio.tv.core.radar.RadarFeaturedEvent
import com.nuvio.tv.core.radar.RadarFixture
import com.nuvio.tv.core.radar.RadarLeague
import com.nuvio.tv.core.radar.RadarLiveScore
import com.nuvio.tv.core.radar.RadarTime
import com.nuvio.tv.core.radar.radarWhenLabel
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.placeholderCardShimmer
import com.nuvio.tv.ui.components.rememberPlaceholderShimmerOffsetState
import com.nuvio.tv.ui.screens.collection.NuvioTextField
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Sports Centre hub (drawer destination): featured event banners, live & upcoming fixtures for
 * followed leagues, and browse-by-sport with OK-toggle follows. OK on a match opens the
 * channel-matching overlay; OK on a channel plays it fullscreen through the live/mpv route.
 * D-pad only — no long-press idioms.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SportsHubScreen(
    onPlayChannel: (title: String, streamUrl: String, contentId: String) -> Unit,
    onAddProvider: () -> Unit,
    onOpenDetail: (contentId: String, type: String) -> Unit = { _, _ -> },
    viewModel: SportsHubViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.ensureLoaded() }

    val nowMs = RadarTime.nowMs()
    val featured = state.activeFeatured(nowMs)
    // Followed clubs feed the same Live & Upcoming row as followed leagues — someone who
    // follows only Arsenal still expects their match at the top, not buried under Browse.
    val upcoming = remember(state, nowMs) {
        (
            state.upcoming(state.followedLeagueIds + featured.map { it.leagueId }, nowMs) +
                state.upcomingForTeams(state.followedTeamIds, nowMs)
            )
            .distinctBy { it.id ?: "${it.leagueId}/${it.event}/${it.ts}" }
            .sortedBy { it.startEpochMs }
    }
    var browseCategory by remember { mutableStateOf<RadarCategory?>(null) }
    var pickingSport by remember { mutableStateOf(false) }
    var pickingTeam by remember { mutableStateOf(false) }
    // Set once a sport is chosen; the country list is the second step.
    var pickedSport by remember { mutableStateOf<String?>(null) }
    // Discovery drill-in: a league/event page listing everything happening in it.
    var leaguePage by remember { mutableStateOf<RadarLeague?>(null) }
    var fixturesPage by remember { mutableStateOf<SportsFixturesCollection?>(null) }
    val firstFocus = remember { FocusRequester() }

    fixturesPage?.let { page ->
        SportsFixturesPage(
            state = state,
            page = page,
            isLive = state.isLiveCheck(nowMs),
            onMatch = { viewModel.openMatch(it) },
            onBack = { fixturesPage = null },
        )
        sheet?.let { s ->
            val hasPlaylistsNow by viewModel.hasPlaylists.collectAsStateWithLifecycle()
            MatchChannelsOverlay(
                state = s.copy(hasPlaylists = hasPlaylistsNow),
                isLive = viewModel.uiState.value.isLive(s.fixture, RadarTime.nowMs()),
                onPlay = { match -> viewModel.playMatch(match, onPlayChannel) },
                onPlayReplay = { replay ->
                    viewModel.closeMatch()
                    onPlayChannel(replay.third, replay.second, replay.first)
                },
                onOpenRecording = { id ->
                    viewModel.closeMatch()
                    onOpenDetail(id, "movie")
                },
                onAddProvider = { viewModel.closeMatch(); onAddProvider() },
                onDismiss = { viewModel.closeMatch() },
            )
        }
        return
    }

    leaguePage?.let { league ->
        LeagueFixturesPage(
            state = state,
            league = league,
            isLive = state.isLiveCheck(nowMs),
            ensureLoaded = { viewModel.repository.ensureLeagueLoaded(it) },
            onMatch = { viewModel.openMatch(it) },
            onToggleFollow = { viewModel.repository.toggleFollow(league) },
            onBack = { leaguePage = null },
        )
        sheet?.let { s ->
            val hasPlaylistsNow by viewModel.hasPlaylists.collectAsStateWithLifecycle()
            MatchChannelsOverlay(
                state = s.copy(hasPlaylists = hasPlaylistsNow),
                isLive = viewModel.uiState.value.isLive(s.fixture, RadarTime.nowMs()),
                onPlay = { match -> viewModel.playMatch(match, onPlayChannel) },
                onPlayReplay = { replay ->
                    viewModel.closeMatch()
                    onPlayChannel(replay.third, replay.second, replay.first)
                },
                onOpenRecording = { id ->
                    viewModel.closeMatch()
                    onOpenDetail(id, "movie")
                },
                onAddProvider = { viewModel.closeMatch(); onAddProvider() },
                onDismiss = { viewModel.closeMatch() },
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = NuvioTheme.spacing.xl),
    ) {
        Text(
            "Sports",
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(start = SportsRowStartPadding, bottom = NuvioTheme.spacing.md),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxxl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
        ) {
            if (featured.isNotEmpty()) {
                item(key = "featured") {
                    RowTitle(
                        text = "Featured Events",
                        onSeeAll = {
                            fixturesPage = SportsFixturesCollection(
                                title = "Featured Events",
                                fixtures = featured.flatMap { event ->
                                    state.upcoming(listOf(event.leagueId), nowMs, cap = 40)
                                }.distinctBy { it.id ?: "${it.leagueId}/${it.event}/${it.ts}" }
                                    .sortedBy { it.startEpochMs },
                            )
                        },
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = SportsRowStartPadding),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    ) {
                        items(featured, key = { it.id }) { event ->
                            FeaturedBannerCard(
                                event = event,
                                matchCount = state.upcoming(listOf(event.leagueId), nowMs, cap = 99).size,
                                focusRequester = if (event === featured.first()) firstFocus else null,
                                // Into the event: every match, live + recent — discovery first.
                                onClick = { state.leagueById(event.leagueId)?.let { leaguePage = it } },
                            )
                        }
                    }
                }
            }
            if (upcoming.isNotEmpty()) {
                item(key = "upcoming") {
                    RowTitle(
                        text = "Live & Upcoming",
                        onSeeAll = {
                            fixturesPage = SportsFixturesCollection("Live & Upcoming", upcoming)
                        },
                    )
                    MatchRow(
                        upcoming,
                        state.isLiveCheck(nowMs),
                        liveScoreFor = { fx -> fx.id?.let { state.liveScores[it] } },
                        onMatch = { viewModel.openMatch(it) },
                        // The featured rail owns first focus when it exists; otherwise the
                        // top match card does — a follows-only profile used to land nowhere.
                        firstItemFocusRequester = if (featured.isEmpty()) firstFocus else null,
                    )
                }
            } else if (state.loadingFixtures && (state.follows.isNotEmpty() || featured.isNotEmpty())) {
                item(key = "loading") {
                    RowTitle("Live & Upcoming")
                    MatchRowSkeleton()
                }
            }
            state.followedTeams.forEach { team ->
                val fixtures = state.upcomingForTeams(listOf(team.id), nowMs, cap = 12)
                if (fixtures.isNotEmpty()) {
                    item(key = "team-${team.id}") {
                        RowTitle(
                            text = team.name,
                            onSeeAll = {
                                fixturesPage = SportsFixturesCollection(
                                    team.name,
                                    state.upcomingForTeams(listOf(team.id), nowMs, cap = 40),
                                )
                            },
                        )
                        MatchRow(
                            fixtures,
                            state.isLiveCheck(nowMs),
                            liveScoreFor = { fx -> fx.id?.let { state.liveScores[it] } },
                            onMatch = { viewModel.openMatch(it) },
                        )
                    }
                }
            }
            state.follows.forEach { follow ->
                val league = state.leagueById(follow.leagueId) ?: return@forEach
                val fixtures = state.upcoming(listOf(league.id), nowMs, cap = 12)
                if (fixtures.isNotEmpty()) {
                    item(key = "league-${league.id}") {
                        RowTitle(
                            text = league.name,
                            badge = league.badge,
                            onSeeAll = { leaguePage = league },
                        )
                        MatchRow(
                            fixtures,
                            state.isLiveCheck(nowMs),
                            liveScoreFor = { fx -> fx.id?.let { state.liveScores[it] } },
                            onMatch = { viewModel.openMatch(it) },
                        )
                    }
                }
            }
            item(key = "browse") {
                RowTitle(if (state.follows.isEmpty()) "Follow your sports" else "Browse sports")
                if (state.follows.isEmpty()) {
                    Text(
                        "Pick leagues and events to follow — they'll appear here when they're coming up, and Tuvora finds which of your channels is showing them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(
                            start = SportsRowStartPadding,
                            end = SportsRowStartPadding,
                            bottom = NuvioTheme.spacing.sm,
                        ),
                    )
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = SportsRowStartPadding),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                ) {
                    items(state.catalog.categories, key = { it.name }) { category ->
                        CategoryTile(
                            category = category,
                            followedCount = category.leagues.count { it.id in state.followedLeagueIds },
                            focusRequester = if (featured.isEmpty() && upcoming.isEmpty() &&
                                category === state.catalog.categories.firstOrNull()
                            ) firstFocus else null,
                            onClick = { browseCategory = category },
                        )
                    }
                    // Anything we didn't curate. Lives at the END of the row so the popular
                    // leagues stay first — this is the escape hatch, not the main path.
                    item(key = "__add_league__") {
                        AddFollowTile(
                            title = "Add a league",
                            subtitle = state.customLeagues.size
                                .let { if (it > 0) "$it added" else "Not in the list?" },
                            onClick = {
                                // Start clean: leaving the results dialog by any route other
                                // than its own dismiss used to leave the last search sitting in
                                // the view model, and it would reappear over the next sport.
                                viewModel.clearLeagueSearch()
                                pickedSport = null
                                pickingSport = true
                            },
                        )
                    }
                    item(key = "__add_team__") {
                        AddFollowTile(
                            title = "Follow a team",
                            subtitle = state.teamFollows.size
                                .let { if (it > 0) "$it followed" else "Just your club" },
                            onClick = {
                                viewModel.clearTeamSearch()
                                pickingTeam = true
                            },
                        )
                    }
                }
            }
        }
    }

    // Initial focus only — re-requesting on every fixture refresh would yank the D-pad
    // away from wherever the user has navigated to.
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(featured.size, upcoming.size) {
        if (!initialFocusDone && (featured.isNotEmpty() || upcoming.isNotEmpty() || state.catalog.categories.isNotEmpty())) {
            initialFocusDone = true
            runCatching { firstFocus.requestFocus() }
        }
    }

    // Sport first, then country, then the leagues themselves — a country alone would mix
    // every sport together, and TheSportsDB filters on both.
    if (pickingSport) {
        val nameSearch by viewModel.leagueSearch.collectAsStateWithLifecycle()
        var leagueQuery by remember { mutableStateOf("") }
        // Debounced: on a D-pad keyboard every character is a deliberate press, but an IME
        // with autocomplete can still emit bursts.
        LaunchedEffect(leagueQuery) {
            delay(SEARCH_DEBOUNCE_MS)
            viewModel.searchLeaguesByName(leagueQuery)
        }
        NuvioDialog(
            onDismiss = { pickingSport = false; viewModel.clearLeagueSearch() },
            title = "Add a league",
            subtitle = "Search by name, or pick a sport — leagues you add here are on your account only",
        ) {
            NuvioTextField(
                value = leagueQuery,
                onValueChange = { leagueQuery = it },
                placeholder = "Search leagues",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            )
            LazyColumn(modifier = Modifier.height(360.dp)) {
                // A typed query takes over the list; clearing it returns the sport picker.
                if (nameSearch.query.isNotBlank()) {
                    if (nameSearch.loading) {
                        item { DialogHintText("Finding leagues…") }
                    } else if (nameSearch.results.isEmpty()) {
                        item { DialogHintText(nameSearch.emptyText) }
                    }
                    items(nameSearch.results, key = { it.id }) { league ->
                        LeagueFollowRow(
                            league = league,
                            followed = league.id in state.followedLeagueIds,
                            onClick = { viewModel.toggleFollow(league) },
                        )
                    }
                } else {
                    items(RADAR_LEAGUE_SPORTS, key = { it }) { sport ->
                        FocusableRow(onClick = {
                            pickingSport = false
                            viewModel.clearLeagueSearch()
                            pickedSport = sport
                        }) {
                            Text(
                                sport,
                                style = MaterialTheme.typography.bodyLarge,
                                color = NuvioTheme.colors.TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Text("›", style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary)
                        }
                    }
                }
            }
        }
    }

    if (pickingTeam) {
        val teamSearch by viewModel.teamSearch.collectAsStateWithLifecycle()
        var teamQuery by remember { mutableStateOf("") }
        LaunchedEffect(teamQuery) {
            delay(SEARCH_DEBOUNCE_MS)
            viewModel.searchTeams(teamQuery)
        }
        NuvioDialog(
            onDismiss = { pickingTeam = false; viewModel.clearTeamSearch() },
            title = "Follow a team",
            // Search-only: nobody finds their club by scrolling every team in a country, and
            // unlike leagues there is no curated set to browse in the first place.
            subtitle = "Search for a club — teams you follow here are on your account only",
        ) {
            NuvioTextField(
                value = teamQuery,
                onValueChange = { teamQuery = it },
                placeholder = "Search teams",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            )
            LazyColumn(modifier = Modifier.height(360.dp)) {
                if (teamSearch.loading) {
                    item { DialogHintText("Finding teams…") }
                } else if (teamSearch.results.isEmpty()) {
                    item { DialogHintText(teamSearch.emptyText) }
                }
                items(teamSearch.results, key = { it.id }) { team ->
                    val followed = team.id in state.followedTeamIds
                    FocusableRow(onClick = { viewModel.toggleFollowTeam(team) }) {
                        AsyncImage(model = team.badge, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(NuvioTheme.spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                team.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = NuvioTheme.colors.TextPrimary,
                            )
                            // Many clubs share a short name; the league separates them.
                            listOfNotNull(team.league, team.country)
                                .firstOrNull { it.isNotBlank() }
                                ?.let { subtitle ->
                                    Text(
                                        subtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NuvioTheme.colors.TextSecondary,
                                    )
                                }
                        }
                        Text(
                            if (followed) "★ Following" else "+ Follow",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (followed) MaterialTheme.colorScheme.primary else NuvioTheme.colors.TextSecondary,
                        )
                    }
                }
            }
        }
    }

    pickedSport?.let { sport ->
        NuvioDialog(
            onDismiss = { pickedSport = null },
            title = sport,
            subtitle = "Pick a country",
        ) {
            LazyColumn(modifier = Modifier.height(400.dp)) {
                items(RADAR_LEAGUE_COUNTRIES, key = { it }) { country ->
                    FocusableRow(onClick = {
                        pickedSport = null
                        viewModel.searchLeaguesIn(sport, country)
                    }) {
                        Text(
                            country,
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text("›", style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary)
                    }
                }
            }
        }
    }

    val search by viewModel.leagueSearch.collectAsStateWithLifecycle()
    if (search.country.isNotBlank() && search.sport.isNotBlank()) {
        NuvioDialog(
            onDismiss = { viewModel.clearLeagueSearch() },
            title = "${search.sport} · ${search.country}",
            subtitle = if (search.loading) "Finding leagues…" else "Select a league to follow it",
        ) {
            LazyColumn(modifier = Modifier.height(400.dp)) {
                if (!search.loading && search.results.isEmpty()) {
                    item { DialogHintText(search.emptyText) }
                }
                items(search.results, key = { it.id }) { league ->
                    LeagueFollowRow(
                        league = league,
                        followed = league.id in state.followedLeagueIds,
                        onClick = { viewModel.toggleFollow(league) },
                    )
                }
            }
        }
    }

    browseCategory?.let { category ->
        NuvioDialog(
            onDismiss = { browseCategory = null },
            title = category.name,
            subtitle = "Select a league to see its matches",
        ) {
            LazyColumn(modifier = Modifier.height(400.dp)) {
                items(category.leagues, key = { it.id }) { league ->
                    val followed = league.id in state.followedLeagueIds
                    // OK = go INSIDE the league (discovery); following happens on its page.
                    FocusableRow(onClick = { browseCategory = null; leaguePage = league }) {
                        AsyncImage(model = league.badge, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(NuvioTheme.spacing.md))
                        Text(
                            league.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (followed) "★ Following" else "›",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (followed) MaterialTheme.colorScheme.primary else NuvioTheme.colors.TextSecondary,
                        )
                    }
                }
            }
        }
    }

    sheet?.let { s ->
        // hasPlaylists reactively: the sheet snapshot can be stale for the first frames
        // (stateIn's initial value is a placeholder until DataStore emits).
        val hasPlaylistsNow by viewModel.hasPlaylists.collectAsStateWithLifecycle()
        MatchChannelsOverlay(
            state = s.copy(hasPlaylists = hasPlaylistsNow),
            isLive = viewModel.uiState.value.isLive(s.fixture, RadarTime.nowMs()),
            onPlay = { match -> viewModel.playMatch(match, onPlayChannel) },
            onPlayReplay = { replay ->
                viewModel.closeMatch()
                onPlayChannel(replay.third, replay.second, replay.first)
            },
            onOpenRecording = { id ->
                viewModel.closeMatch()
                onOpenDetail(id, "movie")
            },
            onAddProvider = { viewModel.closeMatch(); onAddProvider() },
            onDismiss = { viewModel.closeMatch() },
        )
    }
}

/** Small helper so rows can ask "is this fixture live" without recomputing state. */
private fun com.nuvio.tv.core.radar.RadarUiState.isLiveCheck(nowMs: Long): (RadarFixture) -> Boolean =
    { fx -> isLive(fx, nowMs) }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MatchChannelsOverlay(
    state: MatchSheetState,
    isLive: Boolean,
    onPlay: (RadarChannelMatcher.ChannelMatch) -> Unit,
    onPlayReplay: (Triple<String, String, String>) -> Unit = {},
    onOpenRecording: (String) -> Unit = {},
    onAddProvider: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fixture = state.fixture
    NuvioDialog(
        onDismiss = onDismiss,
        title = fixture.displayTitle + (if (isLive) "   🔴 LIVE" else ""),
        subtitle = listOfNotNull(
            fixture.roundLabel ?: fixture.league,
            fixture.startEpochMs?.let { radarWhenLabel(it) },
            fixture.venue,
        ).joinToString(" · "),
        width = 620.dp,
    ) {
        when {
            !state.hasPlaylists -> {
                Text(
                    "Add an IPTV playlist to find and watch this match on your channels.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                )
                Spacer(Modifier.height(NuvioTheme.spacing.md))
                FocusableRow(onClick = onAddProvider) {
                    Text("Add IPTV provider", style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextPrimary)
                }
            }
            state.matches.isEmpty() && state.matching -> Text(
                "Finding channels…",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
            )
            state.matches.isEmpty() && state.matchingFailed -> Text(
                "Couldn't load channels from your providers. Please try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
            )
            state.matches.isEmpty() -> Text(
                "None of your channels list this match. Matching depends on your playlist's EPG and channel names.",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
            )
            else -> LazyColumn(modifier = Modifier.height(360.dp)) {
                if (state.recordings.isNotEmpty()) {
                    item(key = "recordings-title") {
                        Text(
                            "RECORDINGS",
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.TextSecondary,
                            modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs),
                        )
                    }
                    items(state.recordings, key = { "rec-${it.contentId}" }) { rec ->
                        FocusableRow(onClick = { onOpenRecording(rec.contentId) }) {
                            AsyncImage(
                                model = rec.poster,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
                            )
                            Spacer(Modifier.width(NuvioTheme.spacing.md))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    rec.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = NuvioTheme.colors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    rec.playlistName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NuvioTheme.colors.TextSecondary,
                                )
                            }
                            Text("›", color = NuvioTheme.colors.TextPrimary)
                        }
                    }
                    item(key = "channels-title") {
                        Text(
                            "CHANNELS",
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.TextSecondary,
                            modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs),
                        )
                    }
                }
                items(state.matches, key = { it.channel.contentId }) { match ->
                    val isProbing = state.probingContentId == match.channel.contentId
                    val isDead = match.channel.contentId in state.deadContentIds
                    FocusableRow(onClick = { onPlay(match) }) {
                        AsyncImage(
                            model = match.channel.logo,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(NuvioTheme.spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                match.channel.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isDead) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val programme = match.programme
                            Text(
                                when {
                                    isProbing -> "Checking channel…"
                                    isDead -> "Offline · ${match.channel.playlistName}"
                                    programme != null -> listOfNotNull(
                                        match.language,
                                        "${programme.title} · ${RadarTime.formatTime(programme.startMs)} – ${RadarTime.formatTime(programme.endMs)}",
                                    ).joinToString(" · ")
                                    match.via == RadarChannelMatcher.MatchVia.LISTING -> listOfNotNull(
                                        match.language,
                                        "TV listing",
                                        match.channel.playlistName,
                                    ).joinToString(" · ")
                                    else -> match.channel.playlistName
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = NuvioTheme.colors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            if (isProbing) "…" else if (isDead) "✕" else "▶",
                            color = if (isDead) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary,
                        )
                    }
                    // Archived channel + started fixture -> its catch-up Replay, indented
                    // under the channel as its own focusable row (no long-press on TV).
                    state.replays[match.channel.contentId]?.let { replay ->
                        FocusableRow(onClick = { onPlayReplay(replay) }) {
                            Spacer(Modifier.width(48.dp))
                            Text(
                                "↩ Replay from kick-off",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (state.probingContentId == null && state.matches.isNotEmpty() &&
                    state.matches.all { it.channel.contentId in state.deadContentIds }
                ) {
                    item(key = "all-offline") {
                        Text(
                            "All matched channels appear offline right now. Try a recording or replay if available.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextSecondary,
                            modifier = Modifier.padding(NuvioTheme.spacing.sm),
                        )
                    }
                }
                if (state.matching) {
                    item {
                        Text(
                            "Still looking…",
                            style = MaterialTheme.typography.labelSmall,
                            color = NuvioTheme.colors.TextSecondary,
                            modifier = Modifier.padding(NuvioTheme.spacing.sm),
                        )
                    }
                }
            }
        }
    }
}

// --- rows & cards ---------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun MatchRow(
    fixtures: List<RadarFixture>,
    isLive: (RadarFixture) -> Boolean,
    liveScoreFor: (RadarFixture) -> RadarLiveScore?,
    onMatch: (RadarFixture) -> Unit,
    firstItemFocusRequester: FocusRequester? = null,
) {
    // Left-align the focused card to the 52dp gutter while scrolling, like the Modern rows.
    val density = LocalDensity.current
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, isRtl) {
        val startPx = with(density) { SportsRowStartPadding.roundToPx() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> =
                defaultBringIntoViewSpec.scrollAnimationSpec

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val childSize = abs(size)
                val childSmallerThanParent = childSize <= containerSize
                if (isRtl) {
                    val initialTarget = containerSize - startPx.toFloat()
                    val targetForTrailingEdge =
                        if (childSmallerThanParent && initialTarget < childSize) childSize else initialTarget
                    return (offset + size) - targetForTrailingEdge
                }
                val target = startPx.toFloat()
                val space = containerSize - target
                val leading = if (childSmallerThanParent && space < childSize) containerSize - childSize else target
                return offset - leading
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
        LazyRow(
            modifier = Modifier
                .focusRestorer { firstItemFocusRequester ?: FocusRequester.Default }
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = SportsRowStartPadding),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        ) {
            itemsIndexed(
                fixtures,
                key = { _, fx -> fx.id ?: "${fx.leagueId}/${fx.event}/${fx.ts}" },
            ) { index, fx ->
                MatchCard(
                    fx,
                    live = isLive(fx),
                    onClick = { onMatch(fx) },
                    modifier = Modifier
                        .width(MatchCardWidth)
                        .then(
                            if (index == 0 && firstItemFocusRequester != null) {
                                Modifier.focusRequester(firstItemFocusRequester)
                            } else Modifier
                        ),
                    liveScore = liveScoreFor(fx),
                )
            }
        }
    }
}

/** Shimmer stand-in for a match row while the first fixtures load. */
@Composable
private fun MatchRowSkeleton() {
    val shimmer = rememberPlaceholderShimmerOffsetState("sportsSkeleton")
    Row(
        modifier = Modifier.padding(horizontal = SportsRowStartPadding),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
    ) {
        repeat(4) {
            Box(
                Modifier
                    .width(MatchCardWidth)
                    .height(140.dp)
                    .clip(RoundedCornerShape(NuvioTheme.radii.md))
                    .placeholderCardShimmer(shimmer, NuvioTheme.colors.BackgroundCard),
            )
        }
    }
}

/**
 * A fixture card in the Modern-home vocabulary: league line + status pill on top, one row
 * per team with its crest and (live/final) score, kickoff + venue below. Works both as a
 * fixed-width rail tile and stretched fillMaxWidth in the league page list.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MatchCard(
    fixture: RadarFixture,
    live: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(MatchCardWidth),
    liveScore: RadarLiveScore? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(NuvioTheme.radii.md)
    // Focus = FocusBackground fill + 2dp FocusRing border — unmistakable, and never the
    // grey `Primary`. onFocusChanged BEFORE clickable, per the guide-row gotcha.
    Column(
        modifier = modifier
            .clip(cardShape)
            .background(
                if (focused) NuvioTheme.colors.FocusBackground
                else NuvioTheme.colors.BackgroundElevated
            )
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                cardShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(NuvioTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            fixture.leagueBadge?.takeIf { it.isNotBlank() }?.let { badge ->
                BadgeImage(url = badge, size = 16.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                fixture.roundLabel ?: fixture.league ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            MatchStatusPill(fixture, live, liveScore)
        }
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        val home = fixture.home?.takeIf { it.isNotBlank() }
        val away = fixture.away?.takeIf { it.isNotBlank() }
        Column(Modifier.heightIn(min = MatchCardTeamsMinHeight)) {
            if (home != null && away != null) {
                val homeScore = (liveScore?.homeScore ?: fixture.homeScore)?.takeIf { it.isNotBlank() }
                val awayScore = (liveScore?.awayScore ?: fixture.awayScore)?.takeIf { it.isNotBlank() }
                TeamRow(home, fixture.homeBadge, homeScore, dimScore = scoreTrails(homeScore, awayScore))
                TeamRow(away, fixture.awayBadge, awayScore, dimScore = scoreTrails(awayScore, homeScore))
            } else {
                // Motorsport/golf-style events have no team pair — the event name carries the card.
                Text(
                    fixture.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        val startMs = fixture.startEpochMs
        val whenLabel = when {
            live -> null
            startMs != null -> radarWhenLabel(startMs)
            else -> "Time TBC"
        }
        val hot = !live && startMs != null &&
            RadarTime.dayLabel(startMs).let { it == "Today" || it == "Tomorrow" }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 18.dp),
        ) {
            if (whenLabel != null) {
                Text(
                    whenLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hot) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                )
            }
            fixture.venue?.takeIf { it.isNotBlank() }?.let { venue ->
                if (whenLabel != null) {
                    Text(
                        " · ",
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextTertiary,
                    )
                }
                Text(
                    venue,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TeamRow(name: String, badge: String?, score: String?, dimScore: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTheme.spacing.xxs),
    ) {
        TeamBadge(name = name, badge = badge)
        Spacer(Modifier.width(NuvioTheme.spacing.sm + NuvioTheme.spacing.xxs))
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!score.isNullOrBlank()) {
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            Text(
                score,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (dimScore) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextPrimary,
                maxLines = 1,
            )
        }
    }
}

/** Team crest with a monogram-circle fallback so badge-less teams never leave a hole. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TeamBadge(name: String, badge: String?) {
    val failed = remember(badge) { mutableStateOf(false) }
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        if (!badge.isNullOrBlank() && !failed.value) {
            BadgeImage(
                url = badge,
                size = 28.dp,
                onError = { failed.value = true },
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(NuvioTheme.colors.BackgroundCard)
                    .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    teamMonogram(name),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
        }
    }
}

/**
 * TheSportsDB badges are ~500px PNGs; decode them at display size with the ContentCard
 * memory-cache-key convention instead of at full source resolution.
 */
@Composable
private fun BadgeImage(url: String, size: Dp, onError: (() -> Unit)? = null) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val px = remember(size, density) { with(density) { size.roundToPx() }.coerceAtLeast(1) }
    val request = remember(url, px) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .memoryCacheKey("${url}_${px}x${px}")
            .size(px, px)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(size),
        onState = { state ->
            if (state is AsyncImagePainter.State.Error) onError?.invoke()
        },
    )
}

private fun teamMonogram(name: String): String {
    val words = name.split(' ', '-').filter { it.firstOrNull()?.isLetter() == true }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
        words.isNotEmpty() -> words[0].take(2).uppercase()
        else -> "?"
    }
}

/** True when both scores parse as numbers and this side is behind — the trailing score dims. */
private fun scoreTrails(own: String?, other: String?): Boolean {
    val a = own?.trim()?.toIntOrNull() ?: return false
    val b = other?.trim()?.toIntOrNull() ?: return false
    return a < b
}

@Composable
private fun MatchStatusPill(fixture: RadarFixture, live: Boolean, liveScore: RadarLiveScore?) {
    when {
        live -> LiveBadge(progress = liveScore?.progress)
        fixture.postponed == "yes" -> StatusPill("POSTPONED", NuvioTheme.colors.TextTertiary)
        fixture.scoreLabel != null -> StatusPill("FT", NuvioTheme.colors.TextTertiary)
        else -> {
            val day = fixture.startEpochMs?.let { RadarTime.dayLabel(it) }
            if (day == "Today" || day == "Tomorrow") StatusPill(day.uppercase(), NuvioTheme.colors.Secondary)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatusPill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(MatchPillShape)
            .background(color.copy(alpha = 0.12f))
            .border(NuvioTheme.spacing.hairline, color.copy(alpha = 0.35f), MatchPillShape)
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 2.dp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveBadge(progress: String? = null) {
    val live = NuvioPrimitives.marigoldLive
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MatchPillShape)
            .background(live.copy(alpha = 0.16f))
            .border(NuvioTheme.spacing.hairline, live.copy(alpha = 0.5f), MatchPillShape)
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 2.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(live))
        Spacer(Modifier.width(4.dp))
        Text(
            if (progress.isNullOrBlank()) "LIVE" else "LIVE $progress",
            style = MaterialTheme.typography.labelSmall,
            color = live,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

private data class SportsFixturesCollection(
    val title: String,
    val fixtures: List<RadarFixture>,
)

@Composable
private fun SportsFixturesPage(
    state: com.nuvio.tv.core.radar.RadarUiState,
    page: SportsFixturesCollection,
    isLive: (RadarFixture) -> Boolean,
    onMatch: (RadarFixture) -> Unit,
    onBack: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(page.fixtures.isNotEmpty()) {
        if (page.fixtures.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = NuvioTheme.spacing.xl, start = SportsRowStartPadding, end = SportsRowStartPadding),
    ) {
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(bottom = NuvioTheme.spacing.md),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxxl)) {
            itemsIndexed(
                items = page.fixtures,
                key = { _, fixture -> fixture.id ?: "${fixture.leagueId}/${fixture.event}/${fixture.ts}" },
            ) { index, fixture ->
                MatchCard(
                    fixture = fixture,
                    live = isLive(fixture),
                    onClick = { onMatch(fixture) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = NuvioTheme.spacing.xs)
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    liveScore = fixture.id?.let { state.liveScores[it] },
                )
            }
        }
    }
}

/** League/event page: EVERYTHING happening in it — live, upcoming, recent results. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LeagueFixturesPage(
    state: com.nuvio.tv.core.radar.RadarUiState,
    league: RadarLeague,
    isLive: (RadarFixture) -> Boolean,
    ensureLoaded: (String) -> Unit,
    onMatch: (RadarFixture) -> Unit,
    onToggleFollow: () -> Unit,
    onBack: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    // Browsing must not require following — fetch this league on demand.
    LaunchedEffect(league.id) { ensureLoaded(league.id) }
    val nowMs = RadarTime.nowMs()
    val upcoming = state.upcoming(listOf(league.id), nowMs, cap = 40)
    val recent = state.recent(league.id, nowMs)
    val loaded = state.fixturesByLeague.containsKey(league.id)
    val followed = league.id in state.followedLeagueIds
    val headerFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { headerFocus.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = NuvioTheme.spacing.xl, start = SportsRowStartPadding, end = SportsRowStartPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            league.badge?.takeIf { it.isNotBlank() }?.let { BadgeImage(url = it, size = 56.dp) }
            Spacer(Modifier.width(NuvioTheme.spacing.md))
            Column(Modifier.weight(1f)) {
                Text(league.name, style = MaterialTheme.typography.headlineSmall, color = NuvioTheme.colors.TextPrimary)
                Text(
                    listOfNotNull(league.sport, if (loaded) "${upcoming.size} upcoming" else "Loading…").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
            // IntrinsicSize.Max: FocusableRow is fillMaxWidth, and an unweighted Row child
            // measures before the weighted name column — unbounded it swallows the header.
            Box(modifier = Modifier.width(IntrinsicSize.Max).focusRequester(headerFocus)) {
                FocusableRow(onClick = onToggleFollow) {
                    Text(
                        if (followed) "★ Following" else "Follow",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (followed) MaterialTheme.colorScheme.primary else NuvioTheme.colors.TextPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.height(NuvioTheme.spacing.md))
        LazyColumn(contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxxl)) {
            if (upcoming.isNotEmpty()) {
                item(key = "up-title") {
                    Text(
                        "Live & Upcoming",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.padding(vertical = NuvioTheme.spacing.sm),
                    )
                }
                items(upcoming, key = { "up-${it.id ?: it.hashCode()}" }) { fx ->
                    MatchCard(
                        fx, live = isLive(fx), onClick = { onMatch(fx) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTheme.spacing.xs),
                        liveScore = fx.id?.let { state.liveScores[it] },
                    )
                }
            }
            if (recent.isNotEmpty()) {
                item(key = "recent-title") {
                    Text(
                        "Recent results",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.padding(vertical = NuvioTheme.spacing.sm),
                    )
                }
                items(recent, key = { "rec-${it.id ?: it.hashCode()}" }) { fx ->
                    MatchCard(
                        fx, live = false, onClick = { onMatch(fx) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTheme.spacing.xs),
                        liveScore = fx.id?.let { state.liveScores[it] },
                    )
                }
            }
            if (loaded && upcoming.isEmpty() && recent.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No scheduled matches right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(vertical = NuvioTheme.spacing.md),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FeaturedBannerCard(
    event: RadarFeaturedEvent,
    matchCount: Int,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Image card: a fat Primary border is the only focus treatment that stays visible
    // over arbitrary artwork.
    Box(
        modifier = Modifier
            .width(360.dp)
            .height(140.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clip(RoundedCornerShape(12.dp))
            .border(
                if (focused) 3.dp else 0.dp,
                if (focused) NuvioTheme.colors.FocusRing else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = event.banner ?: event.badge,
            contentDescription = event.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))),
        )
        if (focused) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.12f)),
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(NuvioTheme.spacing.md)) {
            Text(
                event.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (matchCount > 0) "$matchCount upcoming" else "${event.from} – ${event.to}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
/**
 * Entry point for leagues outside the published catalog. Visually a peer of [CategoryTile] so
 * it reads as "one more category", but it opens the country picker instead of a league list.
 */
@Composable
private fun AddFollowTile(title: String, subtitle: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) NuvioTheme.colors.FocusBackground
                else NuvioTheme.colors.BackgroundElevated
            )
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(NuvioTheme.spacing.md),
    ) {
        Text(
            "+",
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun CategoryTile(
    category: RadarCategory,
    followedCount: Int,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(200.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) NuvioTheme.colors.FocusBackground
                else NuvioTheme.colors.BackgroundElevated
            )
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(NuvioTheme.spacing.md),
    ) {
        // Artwork-first like the rest of the app: the category's flagship league badge.
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            category.leagues.firstOrNull()?.badge?.takeIf { it.isNotBlank() }?.let {
                BadgeImage(url = it, size = 40.dp)
            }
        }
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        Text(
            category.name,
            style = MaterialTheme.typography.titleSmall,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (followedCount > 0) "$followedCount followed" else "${category.leagues.size} to track",
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextSecondary,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FocusableRow(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // Same treatment as the live guide's rows: full Primary fill marks the D-pad cursor.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) NuvioTheme.colors.Primary else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RowTitle(
    text: String,
    badge: String? = null,
    onSeeAll: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = SportsRowStartPadding,
                end = SportsRowStartPadding,
                bottom = SportsRowTitleBottom,
            ),
    ) {
        badge?.takeIf { it.isNotBlank() }?.let {
            BadgeImage(url = it, size = 22.dp)
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary,
        )
        onSeeAll?.let { openAll ->
            Spacer(Modifier.width(NuvioTheme.spacing.md))
            Button(
                onClick = openAll,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextSecondary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary,
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.sm)),
                contentPadding = PaddingValues(
                    horizontal = NuvioTheme.spacing.md,
                    vertical = NuvioTheme.spacing.xs,
                ),
            ) {
                Text("See all", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// Modern-home rail metrics (XtreamHubScreen's HubRowStartPadding/HubRowTitleBottom).
private val SportsRowStartPadding = 52.dp
private val SportsRowTitleBottom = 14.dp
private val MatchCardWidth = 300.dp
private val MatchCardTeamsMinHeight = 64.dp
private val MatchPillShape = RoundedCornerShape(percent = 50)


private const val SEARCH_DEBOUNCE_MS = 350L

/** Follow/unfollow row shared by the name-search and country-browse league lists. */
@Composable
private fun LeagueFollowRow(league: RadarLeague, followed: Boolean, onClick: () -> Unit) {
    FocusableRow(onClick = onClick) {
        AsyncImage(model = league.badge, contentDescription = null, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(NuvioTheme.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                league.name,
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextPrimary,
            )
            // Name search spans every country, so the sport is what separates lookalikes.
            league.sport?.takeIf { it.isNotBlank() }?.let { sport ->
                Text(
                    sport,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
        }
        Text(
            if (followed) "★ Following" else "+ Follow",
            style = MaterialTheme.typography.labelLarge,
            color = if (followed) MaterialTheme.colorScheme.primary else NuvioTheme.colors.TextSecondary,
        )
    }
}

/** Non-focusable explanatory line inside a picker dialog (loading / empty states). */
@Composable
private fun DialogHintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier.padding(NuvioTheme.spacing.md),
    )
}
