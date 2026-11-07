package com.nuvio.tv.ui.screens.gamedetail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.core.radar.RadarTime
import com.nuvio.tv.core.sports.detail.Side
import com.nuvio.tv.core.sports.detail.screen.FocusKey
import com.nuvio.tv.core.sports.detail.screen.GameDetailFocus
import com.nuvio.tv.core.sports.detail.screen.GameDetailTvModel
import com.nuvio.tv.core.sports.detail.screen.GameDetailUiState
import com.nuvio.tv.core.sports.detail.screen.tvName
import com.nuvio.tv.core.sports.detail.screen.PrimaryAction
import com.nuvio.tv.core.sports.detail.screen.WatchMode
import com.nuvio.tv.core.sports.detail.screen.WatchStatus
import com.nuvio.tv.core.sports.detail.screen.WatchUi
import com.nuvio.tv.core.sports.detail.SideKey
import com.nuvio.tv.core.sports.detail.screen.TvForm
import com.nuvio.tv.core.sports.detail.screen.TvLeader
import com.nuvio.tv.core.sports.detail.screen.TvLinescore
import com.nuvio.tv.core.sports.detail.screen.TvPlay
import com.nuvio.tv.core.sports.detail.screen.TvStat
import com.nuvio.tv.ui.components.AutoResizeText
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.shimmer
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private val LiveRed = NuvioPrimitives.marigoldLive

/**
 * TV game screen (research/mocks/sports-game-screen-design.html): two panes, nothing scrolls, so the header
 * never leaves the screen and focus never has to chase content. Left: header, live situation, win
 * probability, by-period linescore and the source row (Play or Switch, Channels). Right: latest plays.
 * Dense tables (stats, lineups, standings) are intentionally absent on TV.
 */
@Composable
fun GameDetailScreen(
    onBack: () -> Unit,
    onPlayChannel: (contentId: String) -> Unit,
    onAddProvider: () -> Unit,
    onOpenMatchSheet: () -> Unit,
    onPlayCatchUp: (url: String, title: String, contentId: String, startMs: Long, endMs: Long) -> Unit = { _, _, _, _, _ -> },
    viewModel: GameDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val watch by viewModel.watch.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Lifecycle-bound poll: only while RESUMED, one loop, restarted (not stacked) on return. The loop ends
    // itself when the game is idle (final / far-off): zero requests then.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.poll() }
    }
    // UI backstop: the loading state carries its deadline, so the wait ends even if the work never returns.
    (ui as? GameDetailUiState.Loading)?.let { loading ->
        LaunchedEffect(loading.deadlineAtMs) {
            delay((loading.deadlineAtMs - RadarTime.nowMs()).coerceAtLeast(0))
            viewModel.checkDeadline()
        }
    }
    // No data: hand over to the existing match sheet (channels), as before this screen existed.
    LaunchedEffect(ui) {
        if (ui is GameDetailUiState.NoData) {
            viewModel.requestMatchSheet()
            onOpenMatchSheet()
        }
    }

    val requesters = remember { FocusKey.entries.associateWith { FocusRequester() } }
    var focusedKey by remember { mutableStateOf<FocusKey?>(null) }
    var userMoved by remember { mutableStateOf(false) }
    var autoFocusing by remember { mutableStateOf(false) }
    val onFocused: (FocusKey) -> Unit = { key ->
        if (!autoFocusing) userMoved = true
        focusedKey = key
    }
    val controls = GameDetailFocus.controls(ui, watch)
    // The primary action usually appears after the channel match finished, when Channels already holds focus:
    // until the viewer moves focus themselves, Play/Switch takes it the moment it exists.
    LaunchedEffect(controls) {
        val target = GameDetailFocus.target(focusedKey, userMoved, controls)
        if (target != null && target != focusedKey) {
            autoFocusing = true
            // The control may not be attached on the first frame after it appears: retry briefly until focus lands.
            repeat(6) { attempt ->
                withFrame()
                runCatching { requesters.getValue(target).requestFocus() }
                kotlinx.coroutines.delay(80L * (attempt + 1))
                if (focusedKey == target) return@repeat
            }
            autoFocusing = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(start = 48.dp, end = 40.dp, top = 28.dp, bottom = 40.dp),
    ) {
        when (val s = ui) {
            is GameDetailUiState.Loading -> LoadingSkeleton()
            GameDetailUiState.NoData -> CenterMessage("Opening channels…")
            is GameDetailUiState.Failed -> FailedPane(s.timedOut, requesters.getValue(FocusKey.RETRY), { onFocused(FocusKey.RETRY) }, viewModel::retry)
            is GameDetailUiState.Loaded -> {
                val model = GameDetailTvModel.from(s.detail, RadarTime.nowMs())
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(if (model.state == com.nuvio.tv.core.sports.detail.GameState.UPCOMING && model.form == null) 1f else 1.12f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ScoreboardCard(model, s.stale)
                        if (model.state == com.nuvio.tv.core.sports.detail.GameState.UPCOMING) {
                            KickoffCard(model, Modifier.weight(1f))
                        } else {
                            if (model.situation != null || model.homeWinPercent != null) SituationCard(model)
                            model.linescore?.let { ByPeriodCard(it, Modifier.weight(1f)) } ?: Spacer(Modifier.weight(1f))
                        }
                        if (watch.visible) SourceRow(
                            watch = watch, requesters = requesters, onFocused = onFocused,
                            onPrimary = {
                                when (watch.primary) {
                                    PrimaryAction.AddProvider -> onAddProvider()
                                    is PrimaryAction.Replay -> viewModel.playReplay(onPlayCatchUp)
                                    is PrimaryAction.Rematch -> viewModel.retryMatching()
                                    else -> viewModel.playPrimary(onPlayChannel)
                                }
                            },
                            onChannels = { viewModel.requestMatchSheet(); onOpenMatchSheet() },
                        )
                    }
                    val upcoming = model.state == com.nuvio.tv.core.sports.detail.GameState.UPCOMING
                    // An upcoming game with no form yet has nothing for a second pane: the left column carries it all.
                    if (!upcoming || model.form != null) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (upcoming) {
                            model.form?.let { FormCard(it, model, Modifier.weight(1f)) }
                        } else {
                            if (model.leaders.isNotEmpty()) PerformersCard(model.leaders)
                            if (model.stats.isNotEmpty()) StatsCard(model)
                            PlaysCard(model, Modifier.weight(1f).heightIn(min = 160.dp))
                        }
                    }
                }
            }
        }
    }
}

private suspend fun withFrame() { androidx.compose.runtime.withFrameNanos { } }

// ---------------------------------------------------------------------------------------------
// 10-foot building blocks (sizes are for a 960 x 540 dp TV canvas)
// ---------------------------------------------------------------------------------------------

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Column(
        modifier.fillMaxWidth().clip(shape).background(NuvioTheme.colors.BackgroundCard).border(1.dp, NuvioTheme.colors.Border, shape).padding(horizontal = 18.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
private fun CardTitle(text: String) {
    Text(text.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = NuvioTheme.colors.TextSecondary)
    Spacer(Modifier.height(8.dp))
}

/** The standard TV loading indicator over a shimmering scoreboard skeleton, so the layout is already in place. */
@Composable
private fun LoadingSkeleton() {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1.12f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                Box(Modifier.width(160.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(NuvioTheme.colors.BackgroundElevated).shimmer())
                Spacer(Modifier.height(14.dp))
                repeat(2) {
                    Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(56.dp).clip(CircleShape).background(NuvioTheme.colors.BackgroundElevated).shimmer())
                        Spacer(Modifier.width(16.dp))
                        Box(Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(6.dp)).background(NuvioTheme.colors.BackgroundElevated).shimmer())
                        Spacer(Modifier.width(24.dp))
                        Box(Modifier.width(70.dp).height(48.dp).clip(RoundedCornerShape(6.dp)).background(NuvioTheme.colors.BackgroundElevated).shimmer())
                    }
                }
            }
            Card(Modifier.weight(1f)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() } }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f)) { Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(NuvioTheme.colors.BackgroundElevated).shimmer()) }
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 22.sp, color = NuvioTheme.colors.TextSecondary)
    }
}

@Composable
private fun FailedPane(timedOut: Boolean, requester: FocusRequester, onFocused: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (timedOut) "This game is taking too long to load." else "Couldn't load this game.",
            fontSize = 24.sp, color = NuvioTheme.colors.TextPrimary,
        )
        Spacer(Modifier.height(20.dp))
        TvButton("Retry", primary = true, requester = requester, onFocused = onFocused, onClick = onRetry)
    }
}

@Composable
private fun Badge(url: String?, name: String, size: androidx.compose.ui.unit.Dp) {
    if (!url.isNullOrBlank()) {
        AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(NuvioTheme.colors.BackgroundElevated), contentAlignment = Alignment.Center) {
            Text(name.take(2).uppercase(), fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary)
        }
    }
}

@Composable
private fun StatePill(model: GameDetailTvModel) {
    val pill = model.pill ?: return
    val live = model.isLive
    Text(
        pill, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
        color = if (live) Color.White else NuvioTheme.colors.TextPrimary,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (live) LiveRed else NuvioTheme.colors.TextPrimary.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun ScoreboardCard(model: GameDetailTvModel, stale: Boolean) {
    Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                model.subtitle.orEmpty().uppercase(), fontSize = 14.sp, letterSpacing = 1.sp, color = NuvioTheme.colors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            val note = if (stale) "Showing the last update" else model.updatedLabel
            note?.let { Text(it, fontSize = 13.sp, color = NuvioTheme.colors.TextSecondary); Spacer(Modifier.width(12.dp)) }
            StatePill(model)
        }
        Spacer(Modifier.height(10.dp))
        if (model.sides.size == 2) {
            val final = model.state == com.nuvio.tv.core.sports.detail.GameState.FINAL
            val a = model.sides[0].score?.toIntOrNull(); val b = model.sides[1].score?.toIntOrNull()
            val showScore = model.state != com.nuvio.tv.core.sports.detail.GameState.UPCOMING
            TeamRow(model.sides[0], dim = final && a != null && b != null && a < b, showScore = showScore)
            Spacer(Modifier.height(8.dp))
            TeamRow(model.sides[1], dim = final && a != null && b != null && b < a, showScore = showScore)
        } else {
            Text(model.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, maxLines = 2)
            model.liveLine?.let { Text(it, fontSize = 20.sp, color = NuvioTheme.colors.TextSecondary) }
        }
    }
}

@Composable
private fun TeamRow(side: Side, dim: Boolean, showScore: Boolean = true) {
    Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(side.badgeUrl, side.name, 56.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            // Long names shrink to fit (never "Wa…"); the record sits under in a legible size.
            AutoResizeText(
                text = side.name, color = NuvioTheme.colors.TextPrimary,
                style = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold), minFontSize = 16.sp,
            )
            side.record?.let { Text(it, fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary) }
        }
        side.score?.takeIf { showScore }?.let {
            Text(
                it, fontSize = 64.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                color = if (dim) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary,
                modifier = Modifier.width(110.dp),
            )
        }
    }
}

@Composable
private fun SituationCard(model: GameDetailTvModel) {
    Card {
        model.situation?.let {
            Text(it, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = NuvioTheme.colors.TextPrimary, maxLines = 2)
            if (model.homeWinPercent != null) Spacer(Modifier.height(10.dp))
        }
        model.homeWinPercent?.let { pct ->
            if (model.situation == null) CardTitle("Win probability")
            val home = model.sides.getOrNull(0)?.tvName.orEmpty(); val away = model.sides.getOrNull(1)?.tvName.orEmpty()
            Row(Modifier.fillMaxWidth()) {
                Text("$home $pct%", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.Secondary, modifier = Modifier.weight(1f))
                Text("${100 - pct}% $away", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.Info)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(NuvioTheme.colors.Border)) {
                if (pct > 0) Box(Modifier.weight(pct.toFloat()).fillMaxHeight().background(NuvioTheme.colors.Secondary))
                if (pct < 100) Box(Modifier.weight((100 - pct).toFloat()).fillMaxHeight().background(NuvioTheme.colors.Info))
            }
        }
    }
}

@Composable
private fun ByPeriodCard(t: TvLinescore, modifier: Modifier) {
    val title = when {
        t.columns.firstOrNull()?.startsWith("Q") == true -> "By quarter"
        t.columns.firstOrNull()?.startsWith("P") == true -> "By period"
        t.columns.firstOrNull()?.all { it.isDigit() } == true -> "By inning"
        else -> "Score"
    }
    Card(modifier) {
        CardTitle(title)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(150.dp))
                t.columns.forEachIndexed { i, c -> PeriodCell(c, header = true, current = i == t.currentIndex, strong = false) }
                t.totalLabel?.let { Text(it, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp)) }
            }
            t.rows.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(150.dp), verticalAlignment = Alignment.CenterVertically) {
                        Badge(r.badgeUrl, r.label, 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(r.label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
                    }
                    r.cells.forEachIndexed { i, c -> PeriodCell(c, header = false, current = i == t.currentIndex, strong = false) }
                    r.total?.let {
                        Text(
                            it, fontSize = if (r.leading) 34.sp else 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            color = if (r.leading) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary, modifier = Modifier.width(64.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PeriodCell(text: String, header: Boolean, current: Boolean, strong: Boolean) {
    Box(
        Modifier.weight(1f).padding(horizontal = 3.dp).clip(RoundedCornerShape(6.dp))
            .background(if (current) NuvioTheme.colors.Secondary.copy(alpha = 0.18f) else Color.Transparent).padding(vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = if (header) 16.sp else 24.sp, fontWeight = if (header || current || strong) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center,
            color = when { current -> NuvioTheme.colors.Secondary; header -> NuvioTheme.colors.TextSecondary; else -> NuvioTheme.colors.TextPrimary },
        )
    }
}

@Composable
private fun KickoffCard(model: GameDetailTvModel, modifier: Modifier) {
    Card(modifier) {
        CardTitle("Starts")
        Spacer(Modifier.weight(1f))
        Text(model.kickoffLabel ?: "Time to be confirmed", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary)
        model.venue?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 22.sp, color = NuvioTheme.colors.TextSecondary) }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun FormCard(form: TvForm, model: GameDetailTvModel, modifier: Modifier = Modifier) {
    Card(modifier) {
        CardTitle("Form · last 5")
        listOf(form.home to model.sides.getOrNull(0), form.away to model.sides.getOrNull(1)).forEach { (results, side) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(side?.tvName.orEmpty(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f))
                results.forEach { r ->
                    val c = when (r) { "W" -> NuvioTheme.colors.Success; "L" -> NuvioTheme.colors.Error; else -> NuvioTheme.colors.TextSecondary }
                    Text(
                        r, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(start = 6.dp).size(30.dp).clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.85f)).padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PerformersCard(items: List<TvLeader>) {
    Card {
        CardTitle("Top performers")
        items.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(l.category, fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.width(112.dp), maxLines = 1)
                Text(l.athlete, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 2)
                l.tag?.let { TagChip(it, l.side); Spacer(Modifier.width(10.dp)) }
                Text(l.value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary)
            }
        }
    }
}

@Composable
private fun StatsCard(model: GameDetailTvModel) {
    Card {
        CardTitle("Team stats")
        model.stats.forEach { st -> StatRowView(st) }
    }
}

@Composable
private fun StatRowView(st: TvStat) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(st.home, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.width(72.dp))
            Text(st.label, fontSize = 15.sp, color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1)
            Text(st.away, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(NuvioTheme.colors.Border)) {
            val f = st.homeFraction.coerceIn(0.02f, 0.98f)
            Box(Modifier.weight(f).fillMaxHeight().background(NuvioTheme.colors.Secondary))
            Box(Modifier.weight(1f - f).fillMaxHeight().background(NuvioTheme.colors.Info))
        }
    }
}

@Composable
private fun TagChip(text: String, side: SideKey?) {
    val c = if (side == SideKey.AWAY) NuvioTheme.colors.Info else NuvioTheme.colors.Secondary
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(c.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun PlaysCard(model: GameDetailTvModel, modifier: Modifier) {
    Card(modifier) {
        CardTitle(if (model.isLive) "Latest plays" else "Scoring & plays")
        if (model.latestPlays.isEmpty()) {
            Text("No plays yet", fontSize = 18.sp, color = NuvioTheme.colors.TextSecondary)
        } else {
            // Whole rows only: a row that does not fit is never drawn half-cut. D-pad down/up on the last/first row pages the list.
            WholeRowsList(model.latestPlays, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun WholeRowsList(plays: List<TvPlay>, modifier: Modifier) {
    var first by remember(plays.firstOrNull()?.text) { mutableStateOf(0) }
    val fitted = remember { IntArray(1) { 1 } }
    androidx.compose.ui.layout.SubcomposeLayout(modifier.clipToBounds()) { constraints ->
        val gap = 4.dp.roundToPx()
        var used = 0
        val placed = ArrayList<androidx.compose.ui.layout.Placeable>()
        var i = first.coerceIn(0, (plays.size - 1).coerceAtLeast(0))
        while (i < plays.size) {
            val idx = i
            val p = subcompose("${plays[idx].time}|${plays[idx].text}") {
                PlayRow(
                    plays[idx],
                    onDown = { if (idx == first + fitted[0] - 1 && first + fitted[0] < plays.size) { first += 1; true } else false },
                    onUp = { if (idx == first && first > 0) { first -= 1; true } else false },
                )
            }.first().measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
            if (placed.isNotEmpty() && used + gap + p.height > constraints.maxHeight) break
            used += (if (placed.isEmpty()) 0 else gap) + p.height
            placed += p
            i++
        }
        fitted[0] = placed.size.coerceAtLeast(1)
        layout(constraints.maxWidth, used.coerceAtMost(constraints.maxHeight)) {
            var y = 0
            placed.forEach { it.placeRelative(0, y); y += it.height + gap }
        }
    }
}

@Composable
private fun PlayRow(p: TvPlay, onDown: () -> Boolean, onUp: () -> Boolean) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (focused) NuvioTheme.colors.FocusBackground else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) NuvioTheme.colors.FocusRing else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
                else when (e.key) {
                    androidx.compose.ui.input.key.Key.DirectionDown -> onDown()
                    androidx.compose.ui.input.key.Key.DirectionUp -> onUp()
                    else -> false
                }
            }
            .focusable()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(p.time, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.width(64.dp))
        Box(Modifier.padding(top = 8.dp, end = 8.dp).size(10.dp).clip(CircleShape).background(if (p.scoring) NuvioTheme.colors.Secondary else Color.Transparent))
        p.tag?.let { Box(Modifier.padding(end = 8.dp)) { TagChip(it, p.side) } }
        Text(p.text, fontSize = 16.sp, color = NuvioTheme.colors.TextPrimary, maxLines = 3, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SourceRow(
    watch: WatchUi,
    requesters: Map<FocusKey, FocusRequester>,
    onFocused: (FocusKey) -> Unit,
    onPrimary: () -> Unit,
    onChannels: () -> Unit,
) {
    val status = watchStatusLine(watch)
    val longStatus = status.length > 38
    Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val p = watch.primary) {
                is PrimaryAction.Play -> TvButton("▶  Play ${p.channel.name}", true, requesters.getValue(FocusKey.PRIMARY), { onFocused(FocusKey.PRIMARY) }, onPrimary)
                is PrimaryAction.Switch -> TvButton("⇄  Switch to ${p.channel.name}", true, requesters.getValue(FocusKey.PRIMARY), { onFocused(FocusKey.PRIMARY) }, onPrimary)
                PrimaryAction.AddProvider -> TvButton("Add IPTV provider", true, requesters.getValue(FocusKey.PRIMARY), { onFocused(FocusKey.PRIMARY) }, onPrimary)
                is PrimaryAction.Replay -> TvButton("↩  Watch replay", true, requesters.getValue(FocusKey.PRIMARY), { onFocused(FocusKey.PRIMARY) }, onPrimary)
                is PrimaryAction.Rematch -> TvButton(if (p.failed) "Retry" else "Find channels", true, requesters.getValue(FocusKey.PRIMARY), { onFocused(FocusKey.PRIMARY) }, onPrimary)
                PrimaryAction.None -> Unit
            }
            if (watch.showChannels) {
                TvButton(
                    if (watch.alternatives > 0) "Channels (${watch.alternatives + 1})" else "Channels",
                    false, requesters.getValue(FocusKey.CHANNELS), { onFocused(FocusKey.CHANNELS) }, onChannels,
                )
            }
            if (!longStatus) StatusText(status, Modifier.weight(1f))
        }
        // A long status (the empty-channels explanation) gets the card's full width under the buttons instead of a squeezed column.
        if (longStatus) {
            Spacer(Modifier.height(8.dp))
            StatusText(status, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StatusText(text: String, modifier: Modifier) {
    Text(text, fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

private fun watchStatusLine(w: WatchUi): String = when (w.status) {
    WatchStatus.FINDING -> if (w.mode == WatchMode.WATCHING && w.currentChannelName != null) "Watching ${w.currentChannelName} · finding channels…" else "Finding channels…"
    WatchStatus.READY -> if (w.primary is PrimaryAction.Replay) "Replay from kick-off on ${(w.primary as PrimaryAction.Replay).channel.name}" else if (w.mode == WatchMode.WATCHING) "Watching ${w.currentChannelName ?: "a channel"}" else "Found on your channels"
    WatchStatus.ALREADY_ON_IT -> "Watching this game on ${w.currentChannelName ?: "this channel"}"
    WatchStatus.NO_CHANNELS -> "None of your channels list this game yet. Find channels searches your playlists."
    WatchStatus.HIDDEN -> ""
    WatchStatus.FAILED -> "Couldn't check your channels"
    WatchStatus.NO_PROVIDER -> "Add an IPTV playlist to watch this game"
}

/**
 * The app's TV focus vocabulary: theme focus ring + scale ([NuvioTheme.focus]) + a brightened fill. The primary action
 * is the brand accent (Secondary) with its own on-colour text, the secondary one an elevated surface.
 */
@Composable
private fun TvButton(label: String, primary: Boolean, requester: FocusRequester, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) NuvioTheme.focus.scale else 1f, label = "gameDetailButtonScale")
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(requester)
            .clip(shape)
            // ONE focus treatment (the app's ring + scale): the fill never changes with focus, so the first open
            // and every later D-pad move look the same.
            .background(if (primary) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundElevated)
            .border(if (focused) NuvioTheme.focusRing.border(3.dp) else androidx.compose.foundation.BorderStroke(1.dp, NuvioTheme.colors.Border), shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (primary) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
        )
    }
}
