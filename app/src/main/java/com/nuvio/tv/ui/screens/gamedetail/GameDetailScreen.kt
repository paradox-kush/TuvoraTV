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
import com.nuvio.tv.core.sports.detail.screen.ByPeriodFit
import com.nuvio.tv.core.sports.detail.screen.FocusKey
import com.nuvio.tv.core.sports.detail.screen.WatchCopy
import com.nuvio.tv.core.sports.detail.screen.GameDetailFocus
import com.nuvio.tv.core.sports.detail.screen.GameDetailTvModel
import com.nuvio.tv.core.sports.detail.screen.GameDetailUiState
import com.nuvio.tv.core.sports.detail.screen.PreviewFailure
import com.nuvio.tv.core.sports.detail.screen.PreviewResolver
import com.nuvio.tv.core.sports.detail.screen.PreviewSlot
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
import com.nuvio.tv.ui.util.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LiveRed = NuvioPrimitives.marigoldLive

/** A preview that is not playing this long after the screen opened is given up on (dwell + tune + first frames). */
private const val PREVIEW_STALL_MS = 15_000L

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
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // ---- muted live preview (the same clean live host the guide uses; never two decoders) ----
    val playback = hiltViewModel<com.nuvio.tv.ui.screens.iptv.CleanLiveGuidePlaybackViewModel>()
    val playbackState by playback.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val surfaceOwner = remember(context) { android.widget.FrameLayout(context) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val previewViewport = remember(density) {
        // The box is ~16:9 and ~160 dp tall: ask for no more than that, so the engine picks a low variant.
        val heightPx = with(density) { 160.dp.roundToPx() }
        com.nuvio.tv.playback.core.VideoDimensions(width = heightPx * 16 / 9, height = heightPx)
    }
    val liveSlot = preview as? PreviewSlot.Live
    // Start only after the dwell, only while RESUMED; leaving (Back/Home/other screen) releases the player right away.
    LaunchedEffect(liveSlot?.contentId) {
        val slot = liveSlot ?: return@LaunchedEffect
        val host = context.findActivity() ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(PreviewResolver.DWELL_MS)
            if (!viewModel.publishLineup(slot.contentId)) {
                viewModel.markPreviewFailed(PreviewFailure.NO_LINEUP)
                return@repeatOnLifecycle
            }
            playback.attachGuide(
                initialContentId = com.nuvio.tv.playback.core.ProviderSelectionId(slot.contentId),
                activity = host, lifecycle = lifecycleOwner.lifecycle, surfaceOwner = surfaceOwner, previewViewport = previewViewport,
            )
            try { kotlinx.coroutines.awaitCancellation() } finally { playback.detachGuide() }
        }
    }
    // Muted = audio track off (not just volume 0) once the stream is up.
    // The state machine drops playback commands until playback is actually running, so the request waits for PLAYING
    // (asking at Ready, while the surface is still attaching, was silently ignored and left the audio on).
    val playingTarget = (playbackState as? com.nuvio.tv.ui.screens.iptv.CleanLiveGuidePlaybackState.Ready)
        ?.takeIf { it.snapshot.state == com.nuvio.tv.playback.core.PlaybackState.PLAYING }?.target?.contentId?.value
    LaunchedEffect(playingTarget) { if (playingTarget != null) playback.requestAudioEnabled(false) }
    // One failure ends the preview quietly ("Preview unavailable: <reason>"): no spinner loop, no retry loop.
    LaunchedEffect(playbackState) {
        val failure = when (val st = playbackState) {
            is com.nuvio.tv.ui.screens.iptv.CleanLiveGuidePlaybackState.Rejected -> PreviewFailure.rejected(st.reason.name)
            is com.nuvio.tv.ui.screens.iptv.CleanLiveGuidePlaybackState.Ready ->
                if (st.snapshot.state == com.nuvio.tv.playback.core.PlaybackState.FAILED) PreviewFailure.stream(st.snapshot.failure?.code?.name) else null
            else -> null
        }
        if (failure != null) {
            (playbackState as? com.nuvio.tv.ui.screens.iptv.CleanLiveGuidePlaybackState.Ready)?.snapshot?.let { sn ->
                android.util.Log.w(
                    "GameDetailPreview",
                    "failed code=${failure.code} engine failure=${sn.failure?.code}/${sn.failure?.phase}/${sn.failure?.domain} preview=${sn.previewAvailability}",
                )
            }
        }
        if (failure != null) viewModel.markPreviewFailed(failure)
    }
    // Bounded: a stream that has not come up within the stall deadline ends as "stream timed out".
    LaunchedEffect(liveSlot?.contentId) {
        if (liveSlot == null) return@LaunchedEffect
        delay(PREVIEW_STALL_MS)
        if (playbackState !is com.nuvio.tv.ui.screens.iptv.CleanLiveGuidePlaybackState.Ready) viewModel.markPreviewFailed(PreviewFailure.TIMEOUT)
    }
    val previewReady = playbackState is com.nuvio.tv.ui.screens.iptv.CleanLiveGuidePlaybackState.Ready

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
    val controls = GameDetailFocus.controls(ui, watch, preview)
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
                            // Before kickoff only the market has a win chance (and its storylines): a small card above the kickoff.
                            if (model.winChance != null) SituationCard(model)
                            KickoffCard(model, Modifier.weight(1f))
                        } else {
                            if (model.situation != null || model.winChance != null) SituationCard(model)
                            model.linescore?.let { ByPeriodCard(it, Modifier.weight(1f)) } ?: Spacer(Modifier.weight(1f))
                        }
                        if (watch.visible) SourceRow(
                            watch = watch, requesters = requesters, onFocused = onFocused,
                            onPrimary = {
                                when (watch.primary) {
                                    PrimaryAction.AddProvider -> onAddProvider()
                                    is PrimaryAction.Replay -> viewModel.playReplay(onPlayCatchUp)
                                    is PrimaryAction.Rematch -> viewModel.retryMatching()
                                    else -> scope.launch {
                                        // The preview decoder is released BEFORE the fullscreen player opens: never two at once.
                                        playback.detach()
                                        viewModel.playPrimary(onPlayChannel)
                                    }
                                }
                            },
                            onChannels = { scope.launch { playback.detach(); viewModel.requestMatchSheet(); onOpenMatchSheet() } },
                        )
                    }
                    val upcoming = model.state == com.nuvio.tv.core.sports.detail.GameState.UPCOMING
                    // An upcoming game with no form yet has nothing for a second pane: the left column carries it all.
                    if (!upcoming || model.form != null) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (upcoming) {
                            model.form?.let { FormCard(it, model, Modifier.weight(1f)) }
                        } else {
                            val showPreview = preview !is PreviewSlot.Hidden
                            if (showPreview) PreviewBox(slot = preview, surfaceOwner = surfaceOwner, ready = previewReady)
                            if (model.leaders.isNotEmpty()) PerformersCard(model.leaders.take(if (showPreview) 2 else 3))
                            // The stats comparison gives way to the preview box: the right column is a fixed height.
                            if (model.stats.isNotEmpty() && !showPreview) StatsCard(model)
                            PlaysCard(model, Modifier.weight(1f).heightIn(min = 100.dp))
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
        modifier.fillMaxWidth().clip(shape).background(NuvioTheme.colors.BackgroundCard).border(1.dp, NuvioTheme.colors.Border, shape).padding(horizontal = 18.dp, vertical = 10.dp),
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
    Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(side.badgeUrl, side.name, 48.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            // Long names shrink to fit (never "Wa…"); the record sits under in a legible size.
            AutoResizeText(
                text = side.name, color = NuvioTheme.colors.TextPrimary,
                style = androidx.compose.ui.text.TextStyle(fontSize = 23.sp, fontWeight = FontWeight.SemiBold), minFontSize = 15.sp,
            )
            side.record?.let { Text(it, fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary) }
        }
        side.score?.takeIf { showScore }?.let {
            Text(
                it, fontSize = 47.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
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
            Text(it, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = NuvioTheme.colors.TextPrimary, maxLines = 2)
            if (model.winChance != null) Spacer(Modifier.height(10.dp))
        }
        model.winChance?.let { w ->
            val foot = listOfNotNull(w.caption, w.staleLabel).joinToString("  ·  ")
            // With no situation line the card has a title row: the credit sits at its right end (one row less height,
            // so By Period below keeps its team rows on the fixed-height canvas).
            val footInTitle = model.situation == null
            if (footInTitle) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text((if (model.state == com.nuvio.tv.core.sports.detail.GameState.UPCOMING) "Win chance" else "Win probability").uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.weight(1f))
                    if (foot.isNotEmpty()) Text(foot, fontSize = 13.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
            }
            val home = model.sides.getOrNull(0)?.tvName.orEmpty(); val away = model.sides.getOrNull(1)?.tvName.orEmpty()
            Row(Modifier.fillMaxWidth()) {
                Text("$home ${w.homePercent}%", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.Secondary, modifier = Modifier.weight(1f))
                w.drawPercent?.let { Text("Draw $it%", fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp)) }
                Text("${w.awayPercent}% $away", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.Info)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(NuvioTheme.colors.Border)) {
                if (w.homePercent > 0) Box(Modifier.weight(w.homePercent.toFloat()).fillMaxHeight().background(NuvioTheme.colors.Secondary))
                w.drawPercent?.let { Box(Modifier.weight(it.toFloat()).fillMaxHeight().background(NuvioTheme.colors.TextSecondary.copy(alpha = 0.5f))) }
                if (w.awayPercent > 0) Box(Modifier.weight(w.awayPercent.toFloat()).fillMaxHeight().background(NuvioTheme.colors.Info))
            }
            w.line?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 16.sp, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!footInTitle && foot.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(foot, fontSize = 13.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    // The left column is a fixed height: measure what is left and degrade (ByPeriodFit) instead of clipping a team row.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val fit = ByPeriodFit.layout(maxHeight.value.toInt(), t.rows.size) ?: return@BoxWithConstraints
        val roomy = fit.rowDp >= 30
        Card(Modifier.fillMaxSize()) {
            if (fit.titleRow) CardTitle(title)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
                if (fit.header) Row(Modifier.fillMaxWidth().height(ByPeriodFit.HEADER_DP.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(150.dp)) {
                        // Folded title: the header row's empty label column carries it.
                        if (!fit.titleRow) Text(title.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
                    }
                    t.columns.forEachIndexed { i, c -> PeriodCell(c, header = true, current = i == t.currentIndex, strong = false) }
                    t.totalLabel?.let { Text(it, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp)) }
                }
                t.rows.forEach { r ->
                    Row(Modifier.fillMaxWidth().height(fit.rowDp.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.width(150.dp), verticalAlignment = Alignment.CenterVertically) {
                            Badge(r.badgeUrl, r.label, if (roomy) 30.dp else (fit.rowDp - 2).dp)
                            Spacer(Modifier.width(10.dp))
                            Text(r.label, fontSize = if (roomy) 19.sp else 16.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
                        }
                        r.cells.forEachIndexed { i, c -> PeriodCell(c, header = false, current = i == t.currentIndex, strong = false, small = !roomy) }
                        r.total?.let {
                            Text(
                                it, fontSize = if (roomy) (if (r.leading) 28.sp else 25.sp) else (if (r.leading) 20.sp else 18.sp),
                                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1,
                                color = if (r.leading) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary, modifier = Modifier.width(64.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PeriodCell(text: String, header: Boolean, current: Boolean, strong: Boolean, small: Boolean = false) {
    Box(
        Modifier.weight(1f).padding(horizontal = 3.dp).clip(RoundedCornerShape(6.dp))
            .background(if (current) NuvioTheme.colors.Secondary.copy(alpha = 0.18f) else Color.Transparent).padding(vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = if (header) 14.sp else if (small) 17.sp else 20.sp, maxLines = 1, fontWeight = if (header || current || strong) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center,
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

/** The 16:9 preview slot. Live = the muted stream; otherwise a calm card, never a black rectangle or a spinner. */
@Composable
private fun PreviewBox(
    slot: PreviewSlot,
    surfaceOwner: android.widget.FrameLayout,
    ready: Boolean,
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Box(
        Modifier.fillMaxWidth().height(160.dp).clip(shape).background(Color.Black).border(1.dp, NuvioTheme.colors.Border, shape),
        contentAlignment = Alignment.Center,
    ) {
        when (slot) {
            is PreviewSlot.Live -> {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = {
                        // A SurfaceView creates its surface during a window draw pass. This screen is static, and Compose
                        // swallows a hosted child's requestLayout, so nothing would draw after the host adds the view and the
                        // surface would never come up (SURFACE_LOST). Nudge the window once per added surface.
                        surfaceOwner.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                            override fun onChildViewAdded(parent: android.view.View?, child: android.view.View?) {
                                parent?.post { parent.rootView.invalidate() }
                            }
                            override fun onChildViewRemoved(parent: android.view.View?, child: android.view.View?) = Unit
                        })
                        surfaceOwner
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (!ready) PreviewMessage("Starting preview…", sub = slot.name)
            }
            is PreviewSlot.PressOk -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(slot.logoUrl, slot.name, 56.dp)
                Text(slot.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Press OK to watch", fontSize = 15.sp, color = NuvioTheme.colors.TextSecondary)
            }
            is PreviewSlot.Watching -> PreviewMessage("Now playing", sub = slot.name)
            PreviewSlot.Searching -> PreviewMessage("Looking for a channel…")
            is PreviewSlot.Unavailable -> PreviewMessage("Preview unavailable", sub = slot.failure?.label)
            // The one Find channels / Retry action lives in the source row; the box only says why it is empty.
            is PreviewSlot.NoMatch -> PreviewMessage(
                if (slot.failed) "Couldn't check your channels" else "No channel found yet",
                sub = "Use the button below to search your playlists",
            )
            PreviewSlot.Hidden -> Unit
        }
    }
}

@Composable
private fun PreviewMessage(text: String, sub: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary)
        sub?.let { Text(it, fontSize = 14.sp, color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun PerformersCard(items: List<TvLeader>) {
    Card {
        CardTitle("Top performers")
        items.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(l.category, fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.width(112.dp), maxLines = 1)
                Text(l.athlete, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                l.tag?.let { TagChip(it, l.side); Spacer(Modifier.width(10.dp)) }
                Text(l.value, fontSize = if (l.value.length > 4) 17.sp else 22.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary)
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(p.time, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.width(60.dp))
        Box(Modifier.padding(top = 8.dp, end = 8.dp).size(10.dp).clip(CircleShape).background(if (p.scoring) NuvioTheme.colors.Secondary else Color.Transparent))
        p.tag?.let { Box(Modifier.padding(end = 8.dp)) { TagChip(it, p.side) } }
        Text(p.text, fontSize = 15.sp, color = NuvioTheme.colors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
            WatchCopy.primaryLabel(watch.primary)?.let { label ->
                TvButton(label, true, requesters.getValue(FocusKey.PRIMARY), { onFocused(FocusKey.PRIMARY) }, onPrimary, Modifier.weight(1f, fill = false))
            }
            if (watch.showChannels) {
                TvButton(
                    if (watch.alternatives > 0) "Channels (${watch.alternatives + 1})" else "Channels",
                    false, requesters.getValue(FocusKey.CHANNELS), { onFocused(FocusKey.CHANNELS) }, onChannels,
                )
            }
        }
        // The status line always gets the card's full width under the buttons, so it never squeezes them.
        if (status.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            StatusText(status, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StatusText(text: String, modifier: Modifier) {
    Text(text, fontSize = 14.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

private fun watchStatusLine(w: WatchUi): String = WatchCopy.statusLine(w)

/**
 * The app's TV focus vocabulary: theme focus ring + scale ([NuvioTheme.focus]) + a brightened fill. The primary action
 * is the brand accent (Secondary) with its own on-colour text, the secondary one an elevated surface.
 */
@Composable
private fun TvButton(label: String, primary: Boolean, requester: FocusRequester, onFocused: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) NuvioTheme.focus.scale else 1f, label = "gameDetailButtonScale")
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(requester)
            .clip(shape)
            // ONE focus treatment (the app's ring + scale): the fill never changes with focus, so the first open
            // and every later D-pad move look the same.
            .background(if (primary) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundElevated)
            .border(if (focused) NuvioTheme.focusRing.border(3.dp) else androidx.compose.foundation.BorderStroke(1.dp, NuvioTheme.colors.Border), shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (primary) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
        )
    }
}
