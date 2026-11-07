package com.nuvio.tv.ui.screens.gamedetail

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private val LiveRed = Color(0xFFE5484D)

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
            withFrame()
            runCatching { requesters.getValue(target).requestFocus() }
            withFrame()
            autoFocusing = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(start = 56.dp, end = 56.dp, top = 40.dp, bottom = 64.dp),
    ) {
        when (val s = ui) {
            is GameDetailUiState.Loading, GameDetailUiState.NoData -> CenterMessage(
                if (s is GameDetailUiState.NoData) "Opening channels…" else "Loading game…",
            )
            is GameDetailUiState.Failed -> FailedPane(s.timedOut, requesters.getValue(FocusKey.RETRY), { onFocused(FocusKey.RETRY) }, viewModel::retry)
            is GameDetailUiState.Loaded -> {
                val model = GameDetailTvModel.from(s.detail, RadarTime.nowMs())
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Column(Modifier.weight(1.5f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        HeaderBlock(model, s.stale)
                        model.situation?.let { SituationLine(it) }
                        model.homeWinPercent?.let { WinProbability(it, model.sides.getOrNull(0)?.tvName.orEmpty(), model.sides.getOrNull(1)?.tvName.orEmpty()) }
                        model.linescore?.let { Linescore(it.columns, it.rows, model.sides) }
                        Spacer(Modifier.weight(1f))
                        SourceRow(
                            watch = watch,
                            requesters = requesters,
                            onFocused = onFocused,
                            onPrimary = {
                                when (watch.primary) {
                                    PrimaryAction.AddProvider -> onAddProvider()
                                    is PrimaryAction.Rematch -> viewModel.retryMatching()
                                    else -> viewModel.playPrimary(onPlayChannel)
                                }
                            },
                            onChannels = { viewModel.requestMatchSheet(); onOpenMatchSheet() },
                        )
                    }
                    LatestPlays(model, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

private suspend fun withFrame() { androidx.compose.runtime.withFrameNanos { } }

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary)
    }
}

@Composable
private fun FailedPane(timedOut: Boolean, requester: FocusRequester, onFocused: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (timedOut) "This game is taking too long to load." else "Couldn't load this game.",
            style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary,
        )
        Spacer(Modifier.height(20.dp))
        TvButton("Retry", primary = true, requester = requester, onFocused = onFocused, onClick = onRetry)
    }
}

@Composable
private fun HeaderBlock(model: GameDetailTvModel, stale: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        model.subtitle?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (model.sides.size == 2) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SideBlock(model.sides[0], Modifier.weight(1f))
                Column(Modifier.width(180.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (model.isLive) LivePill()
                    model.liveLine?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary, textAlign = TextAlign.Center) }
                }
                SideBlock(model.sides[1], Modifier.weight(1f))
            }
        } else {
            Text(model.title, style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            model.liveLine?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary) }
        }
        val note = when {
            stale -> "Showing the last update — retrying"
            else -> model.updatedLabel
        }
        note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary) }
    }
}

@Composable
private fun SideBlock(side: Side, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!side.badgeUrl.isNullOrBlank()) {
            AsyncImage(model = side.badgeUrl, contentDescription = null, modifier = Modifier.size(56.dp).clip(CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(side.tvName, style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary, maxLines = 2)
            side.record?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary) }
        }
        side.score?.let { Text(it, style = MaterialTheme.typography.displaySmall, color = NuvioTheme.colors.TextPrimary, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun LivePill() {
    Text(
        "LIVE", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(LiveRed).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun SituationLine(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.FocusRing, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun WinProbability(homePercent: Int, home: String, away: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("$home $homePercent%", style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.weight(1f))
            Text("${100 - homePercent}% $away", style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary)
        }
        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(NuvioTheme.colors.Border)) {
            if (homePercent > 0) Box(Modifier.weight(homePercent.toFloat()).fillMaxHeight().background(NuvioTheme.colors.FocusRing))
            if (homePercent < 100) Box(Modifier.weight((100 - homePercent).toFloat()).fillMaxHeight())
        }
    }
}

@Composable
private fun Linescore(columns: List<String>, rows: List<List<String>>, sides: List<Side>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NuvioTheme.colors.BackgroundElevated).padding(horizontal = 16.dp, vertical = 10.dp)) {
        LineRow("", columns, header = true)
        rows.forEachIndexed { i, r -> LineRow(sides.getOrNull(i)?.tvName.orEmpty(), r, header = false) }
    }
}

@Composable
private fun LineRow(label: String, cells: List<String>, header: Boolean) {
    val color = if (header) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        cells.forEach { Text(it, color = color, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(watchStatusLine(watch), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val p = watch.primary) {
                is PrimaryAction.Play -> TvButton("▶  Play ${p.channel.name}", true, requesters.getValue(FocusKey.PRIMARY), { onFocused(FocusKey.PRIMARY) }, onPrimary)
                is PrimaryAction.Switch -> TvButton("⇄  Switch to ${p.channel.name}", true, requesters.getValue(FocusKey.PRIMARY), { onFocused(FocusKey.PRIMARY) }, onPrimary)
                PrimaryAction.AddProvider -> TvButton("Add IPTV provider", true, requesters.getValue(FocusKey.PRIMARY), { onFocused(FocusKey.PRIMARY) }, onPrimary)
                is PrimaryAction.Rematch -> TvButton(if (p.failed) "Retry" else "Find channels", true, requesters.getValue(FocusKey.PRIMARY), { onFocused(FocusKey.PRIMARY) }, onPrimary)
                PrimaryAction.None -> Unit
            }
            if (watch.showChannels) {
                TvButton(
                    if (watch.alternatives > 0) "Channels (${watch.alternatives + 1})" else "Channels",
                    false, requesters.getValue(FocusKey.CHANNELS), { onFocused(FocusKey.CHANNELS) }, onChannels,
                )
            }
        }
    }
}

private fun watchStatusLine(w: WatchUi): String = when (w.status) {
    WatchStatus.FINDING -> if (w.mode == WatchMode.WATCHING && w.currentChannelName != null) "Watching ${w.currentChannelName} · finding channels…" else "Finding channels…"
    WatchStatus.READY -> if (w.mode == WatchMode.WATCHING) "Watching ${w.currentChannelName ?: "a channel"}" else "Found on your channels"
    WatchStatus.ALREADY_ON_IT -> "Watching this game on ${w.currentChannelName ?: "this channel"}"
    WatchStatus.NO_CHANNELS -> "None of your channels list this game yet"
    WatchStatus.FAILED -> "Couldn't check your channels"
    WatchStatus.NO_PROVIDER -> "Add an IPTV playlist to watch this game"
}

@Composable
private fun LatestPlays(model: GameDetailTvModel, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(NuvioTheme.colors.BackgroundElevated).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("LATEST PLAYS", style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary)
        if (model.latestPlays.isEmpty()) {
            Text("No plays yet", style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
        }
        model.latestPlays.forEach { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(p.time, style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.width(52.dp))
                Text(
                    listOfNotNull(p.tag, p.text).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Focus = FocusBackground fill + 2dp FocusRing border (the Sports cards' convention), never a bare colour shift. */
@Composable
private fun TvButton(label: String, primary: Boolean, requester: FocusRequester, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Box(
        Modifier
            .focusRequester(requester)
            .clip(shape)
            .background(
                when {
                    focused -> NuvioTheme.colors.FocusBackground
                    primary -> NuvioTheme.colors.Primary
                    else -> NuvioTheme.colors.BackgroundElevated
                },
            )
            .border(if (focused) 2.dp else 1.dp, if (focused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border, shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
