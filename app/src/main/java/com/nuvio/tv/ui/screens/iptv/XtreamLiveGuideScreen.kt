package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamProgram
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.enableComposeSurfaceSyncWorkaroundIfAvailable
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Locale

/**
 * TiViMate-style Live TV guide (B10): category column -> channel list with now/next EPG, and a
 * LIVE video preview pane up top. ONE ExoPlayer instance serves the whole guide: it resumes the
 * last-played channel on entry, OK on a row tunes it (setMediaSource replace), OK on the
 * already-tuned channel expands the same surface to fullscreen in place, BACK collapses. Focus
 * movement only browses (info/EPG) — it never touches the stream. No PlayerScreen navigation, no
 * second player init (two live decoders are unstable on weak GPUs and double-dip provider
 * connections, which are often capped at 1).
 *
 * Engine: ExoPlayer (was mpv). mpv's vo=gpu repaints every frame through GLES — measured ~82% of
 * a core + up to 128MB demuxer cache on a 2GB Onn box, which starved the guide UI. ExoPlayer's
 * MediaCodec→SurfaceView path is near-free and all its calls are main-thread (no off-main dance).
 */
@Composable
fun LiveGuide(
    account: XtreamAccount,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    selectedTabRequester: FocusRequester? = null,
    viewModel: XtreamLiveGuideViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteLiveIds.collectAsStateWithLifecycle()
    // Keyed on the whole account (not just id) so option edits (category selections) re-filter.
    LaunchedEffect(account) { viewModel.setAccount(account) }

    // Minute tick drives the now-progress bar and rolls the timeline window at half-hour marks.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val windowStartMs = (nowMs / GUIDE_SLOT_MS) * GUIDE_SLOT_MS

    // RIGHT from a category must land on the channel list — without this, focus search looks
    // rightward at the (non-focusable) preview pane and jumps up to the tabs instead.
    val channelListFocus = remember { FocusRequester() }
    // focusRestorer with no saved child fails the enter and focus wanders — fall back to row 1.
    val firstChannelFocus = remember { FocusRequester() }

    val context = LocalContext.current
    val previewSourceFactory = remember(context) { PlayerMediaSourceFactory(context) }
    val previewPlayer = remember(context) {
        ExoPlayer.Builder(context)
            // Zap-style preview: a small buffer keeps memory flat on budget boxes.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setTargetBufferBytes(16 * 1024 * 1024)
                    .setBufferDurationsMs(5_000, 20_000, 1_500, 2_000)
                    .build()
            )
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        // No engine failover here — OK on the row re-tunes. Log for field triage.
                        Log.w("LiveGuide", "preview playback error: ${error.errorCodeName}")
                    }
                })
            }
    }
    DisposableEffect(Unit) { onDispose { previewPlayer.release() } }
    BackHandler(enabled = fullscreen) { onFullscreenChange(false) }

    // Fullscreen controls overlay: shown on entry and on any key, auto-hides while playing.
    var paused by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(false) }
    var controlsTick by remember { mutableStateOf(0) }
    fun showControls() { controlsVisible = true; controlsTick++ }

    // The player tunes ONLY when the preview channel changes (OK press / last-played restore) —
    // never on focus movement. ExoPlayer calls are main-looper-bound and non-blocking.
    // previewPlayback carries the (DoH-rewritten when the playlist opts in) URL + Host header.
    fun tunePreview(url: String, headers: Map<String, String>) {
        previewPlayer.setMediaSource(previewSourceFactory.createMediaSource(context, url, headers))
        previewPlayer.prepare()
        previewPlayer.play()
    }

    val previewPlayback = uiState.previewPlayback
    LaunchedEffect(previewPlayback) {
        val prepared = previewPlayback ?: return@LaunchedEffect
        paused = false
        tunePreview(prepared.url, prepared.headers)
    }

    // Pause holds the frame; resume reloads instead of unpausing (a paused live buffer goes
    // stale, and rejoining the live edge is the expected zap behavior).
    fun togglePause() {
        val prepared = uiState.previewPlayback ?: return
        val target = !paused
        paused = target
        if (target) previewPlayer.pause() else tunePreview(prepared.url, prepared.headers)
        showControls()
    }

    // Entering fullscreen peeks the controls; leaving it rejoins the live edge if paused.
    LaunchedEffect(fullscreen) {
        if (fullscreen) showControls()
        else {
            controlsVisible = false
            if (paused) togglePause()
        }
    }
    // Auto-hide after 4s of playback; stay up while paused so a frozen frame is explained.
    LaunchedEffect(fullscreen, controlsTick, paused) {
        if (fullscreen && controlsVisible && !paused) {
            delay(4_000)
            controlsVisible = false
        }
    }

    // Backgrounding: stop burning the decoder on STOP; rejoin the live edge on START (a paused
    // live buffer goes stale, so reload instead of unpause).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> previewPlayer.stop()
                Lifecycle.Event.ON_START -> {
                    val prepared = uiState.previewPlayback ?: return@LifecycleEventObserver
                    paused = false
                    tunePreview(prepared.url, prepared.headers)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Fullscreen key handling. Focus stays locked on the (hidden) channel row, so keys
            // arrive there — intercept them in the preview phase before the row's clickable.
            // BACK is NOT consumed (BackHandler collapses). (ponytail: no channel zap yet — wire
            // DirectionUp/Down to prev/next channel if requested.)
            .onPreviewKeyEvent { event ->
                if (!fullscreen) return@onPreviewKeyEvent false
                val handled = when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause,
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
                if (!handled) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> togglePause()
                        Key.MediaPlay -> if (paused) togglePause() else showControls()
                        Key.MediaPause -> if (!paused) togglePause() else showControls()
                        else -> showControls()
                    }
                }
                true // consume KeyUp of handled keys too, so the locked row never clicks
            }
    ) {
        Row(Modifier.fillMaxSize()) {
            // Category column
            LazyColumn(
                modifier = Modifier.width(CATEGORY_COL_WIDTH).fillMaxHeight().focusRestorer(),
                contentPadding = PaddingValues(vertical = NuvioTheme.spacing.sm)
            ) {
                itemsIndexed(uiState.categories, key = { _, it -> it.id }) { index, cat ->
                    GuideCategoryRow(
                        label = cat.name,
                        selected = cat.id == uiState.selectedCategoryId,
                        rightFocus = channelListFocus,
                        // First category routes UP back to the active tab so the tabs stay reachable.
                        upFocus = if (index == 0) selectedTabRequester else null,
                        onFocused = { viewModel.selectCategory(cat.id) }
                    )
                }
            }

            // TiviMate-style right side: video (left slot, overlay below covers it) + program
            // info, then a half-hour time header over the channel/EPG timeline grid.
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(PREVIEW_PANE_HEIGHT)) {
                    Spacer(Modifier.fillMaxHeight().aspectRatio(16f / 9f))
                    PreviewInfoPane(
                        channelName = uiState.focusedChannel?.name,
                        epg = uiState.focusedChannel?.let { uiState.epg[it.streamId] },
                        nowMs = nowMs,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }

                GuideTimeHeader(windowStartMs = windowStartMs)

                when {
                    uiState.loadingChannels -> StatusLine("Loading channels…")
                    uiState.error != null -> StatusLine(uiState.error!!)
                    uiState.channels.isEmpty() -> StatusLine("No channels here")
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize()
                            .focusRequester(channelListFocus)
                            .focusRestorer(firstChannelFocus),
                        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
                    ) {
                        itemsIndexed(uiState.channels, key = { _, it -> it.contentId }) { index, ch ->
                            val isPlaying = ch.contentId == uiState.previewChannel?.contentId
                            GuideChannelRow(
                                focusRequester = if (index == 0) firstChannelFocus else null,
                                clampUp = index == 0,
                                number = index + 1,
                                // ▶ marks what the preview player is tuned to.
                                name = (if (isPlaying) "▶ " else "") + ch.name,
                                logo = ch.logo,
                                epg = uiState.epg[ch.streamId],
                                windowStartMs = windowStartMs,
                                nowMs = nowMs,
                                isFavorite = ch.contentId in favoriteIds,
                                // While fullscreen, focus is locked in place behind the video;
                                // keys are intercepted by the root onPreviewKeyEvent (controls
                                // overlay + play/pause), BACK collapses.
                                lockFocus = fullscreen,
                                onFocused = { viewModel.onChannelFocused(ch, index) },
                                // OK: tune the preview; OK on the tuned channel: go fullscreen.
                                onClick = {
                                    if (isPlaying) onFullscreenChange(true)
                                    else viewModel.playPreview(ch)
                                },
                                onLongClick = { viewModel.toggleFavorite(ch) }
                            )
                        }
                    }
                }
            }
        }

        // The single reused player surface: pane-sized normally (top-left of the guide column,
        // TiviMate-style), the whole screen when fullscreen. Same composition slot either way,
        // so the SurfaceView (and player) survive the toggle.
        Box(
            modifier = (
                if (fullscreen) Modifier.fillMaxSize()
                else Modifier
                    .align(Alignment.TopStart)
                    .padding(start = CATEGORY_COL_WIDTH)
                    .height(PREVIEW_PANE_HEIGHT)
                    .aspectRatio(16f / 9f)
                ).background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        enableComposeSurfaceSyncWorkaroundIfAvailable()
                        player = previewPlayer
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (fullscreen && controlsVisible) {
                LiveControlsOverlay(
                    channel = uiState.previewChannel,
                    epg = uiState.previewChannel?.let { uiState.epg[it.streamId] },
                    paused = paused
                )
            }
        }
    }
}

/** Fullscreen live controls: bottom scrim with channel + now/next EPG and the play state. */
@Composable
private fun BoxScope.LiveControlsOverlay(
    channel: GuideChannel?,
    epg: GuideEpg?,
    paused: Boolean,
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
            .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            AsyncImage(
                model = channel?.logo,
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = channel?.name ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                epg?.now?.let { now ->
                    Text(
                        text = "${timeRange(now)}  ${now.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1
                    )
                }
                epg?.next?.let { next ->
                    Text(
                        text = "Next: ${timeRange(next)}  ${next.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1
                    )
                }
            }
            Text(
                text = if (paused) "❙❙ Paused" else "▶ Live",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (paused) Color.White else NuvioTheme.colors.Primary
            )
        }
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        Text(
            text = "OK play/pause · BACK exit fullscreen",
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun StatusLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(NuvioTheme.spacing.xxxl)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary)
    }
}

@Composable
private fun GuideCategoryRow(
    label: String,
    selected: Boolean,
    rightFocus: FocusRequester,
    upFocus: FocusRequester? = null,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    LaunchedEffect(focused) { if (focused) latestOnFocused() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties {
                right = rightFocus
                if (upFocus != null) up = upFocus
            }
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    focused -> NuvioTheme.colors.Primary
                    selected -> NuvioTheme.colors.BackgroundElevated
                    else -> Color.Transparent
                }
            )
            // ponytail: onFocusChanged MUST precede the focus target to observe it. And use
            // clickable (not plain focusable) — plain focusable() didn't reliably take D-pad focus
            // in this LazyColumn, so the category never highlighted and selectCategory never fired
            // (stayed on "All channels"). clickable focuses reliably; Enter selects, and moving
            // focus selects via the LaunchedEffect above. Matches GuideChannelRow.
            .onFocusChanged { focused = it.isFocused }
            .clickable { latestOnFocused() }
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused || selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GuideChannelRow(
    number: Int,
    name: String,
    logo: String?,
    epg: GuideEpg?,
    windowStartMs: Long,
    nowMs: Long,
    isFavorite: Boolean,
    lockFocus: Boolean,
    clampUp: Boolean = false,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    LaunchedEffect(isFocused) { if (isFocused) latestOnFocused() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GUIDE_ROW_HEIGHT)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            // Focus = Primary fill + border (the app's D-pad vocabulary) without hiding the cells.
            .background(if (isFocused) NuvioTheme.colors.Primary.copy(alpha = 0.22f) else Color.Transparent)
            .border(
                if (isFocused) 2.dp else 0.dp,
                if (isFocused) NuvioTheme.colors.Primary else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusProperties {
                if (lockFocus) {
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                } else if (clampUp) {
                    // Top of the channel list stops here — never escapes to the tab row.
                    up = FocusRequester.Cancel
                }
            }
            .onFocusChanged { isFocused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Fixed label block: number | logo | name (+★) — the timeline cells fill the rest.
        Row(
            modifier = Modifier.width(CHANNEL_LABEL_WIDTH).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.width(30.dp),
                maxLines = 1
            )
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(4.dp))
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (isFavorite) {
                Text("★", style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.Primary)
            }
        }
        ProgrammeCells(
            programmes = epg?.programmes ?: emptyList(),
            windowStartMs = windowStartMs,
            nowMs = nowMs
        )
    }
}

/**
 * Duration-proportional programme cells for one channel over the guide window. Display-only —
 * the row is the focusable unit. (ponytail: no horizontal cell browsing yet; add per-cell focus
 * if users ask for it.)
 */
@Composable
private fun RowScope.ProgrammeCells(
    programmes: List<XtreamProgram>,
    windowStartMs: Long,
    nowMs: Long,
) {
    val windowEndMs = windowStartMs + GUIDE_WINDOW_MS
    val visible = programmes
        .filter { it.endMs > windowStartMs && it.startMs < windowEndMs && it.endMs > it.startMs }
        .sortedBy { it.startMs }
    Row(
        modifier = Modifier.weight(1f).fillMaxHeight().padding(start = NuvioTheme.spacing.sm, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (visible.isEmpty()) {
            ProgrammeCell("No information", weightMs = GUIDE_WINDOW_MS, live = false, filler = true)
            return
        }
        var cursor = windowStartMs
        for (p in visible) {
            val start = maxOf(p.startMs, windowStartMs)
            val end = minOf(p.endMs, windowEndMs)
            if (start > cursor) ProgrammeCell(null, weightMs = start - cursor, live = false, filler = true)
            ProgrammeCell(p.title, weightMs = end - start, live = nowMs in p.startMs until p.endMs, filler = false)
            cursor = end
        }
        if (cursor < windowEndMs) ProgrammeCell(null, weightMs = windowEndMs - cursor, live = false, filler = true)
    }
}

@Composable
private fun RowScope.ProgrammeCell(title: String?, weightMs: Long, live: Boolean, filler: Boolean) {
    Box(
        modifier = Modifier
            .weight(weightMs.coerceAtLeast(60_000L).toFloat())
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    live -> NuvioTheme.colors.Primary.copy(alpha = 0.20f)
                    filler -> NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.4f)
                    else -> NuvioTheme.colors.BackgroundElevated
                }
            )
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (live) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Half-hour tick labels over the timeline area, aligned with the rows' cell region. */
@Composable
private fun GuideTimeHeader(windowStartMs: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.md + NuvioTheme.spacing.sm, vertical = 2.dp)
    ) {
        Spacer(Modifier.width(CHANNEL_LABEL_WIDTH + NuvioTheme.spacing.sm))
        val slots = (GUIDE_WINDOW_MS / GUIDE_SLOT_MS).toInt()
        repeat(slots) { i ->
            Text(
                text = hhmm(windowStartMs + i * GUIDE_SLOT_MS),
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
        }
    }
}

/** Right of the video: focused channel's current programme, TiviMate-style — title, time range
 *  with a progress bar and minutes remaining, then the description. */
@Composable
private fun PreviewInfoPane(
    channelName: String?,
    epg: GuideEpg?,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md)
    ) {
        Text(
            text = channelName ?: "",
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.Primary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val now = epg?.now
        if (now != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = now.title,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeRange(now),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                Spacer(Modifier.width(NuvioTheme.spacing.md))
                // Progress through the current programme (TiviMate's ── ● ── bar).
                val durationMs = (now.endMs - now.startMs).coerceAtLeast(1L)
                val progress = ((nowMs - now.startMs).toFloat() / durationMs).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(NuvioTheme.colors.Border)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(NuvioTheme.colors.Primary)
                    )
                }
                Spacer(Modifier.width(NuvioTheme.spacing.md))
                val remainingMin = ((now.endMs - nowMs) / 60_000L).coerceAtLeast(0L)
                Text(
                    text = "$remainingMin min left",
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
            }
            if (now.description.isNotBlank()) {
                Spacer(Modifier.height(NuvioTheme.spacing.sm))
                Text(
                    text = now.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "No information",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        Spacer(Modifier.weight(1f))
        epg?.next?.let { next ->
            Text(
                text = "Next: ${timeRange(next)}  ${next.title}",
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "OK preview · OK again fullscreen · hold OK favorite",
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.7f),
            maxLines = 1
        )
    }
}

private fun timeRange(p: XtreamProgram): String = "${hhmm(p.startMs)}–${hhmm(p.endMs)}"

private fun hhmm(ms: Long): String {
    if (ms <= 0L) return ""
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

private val PREVIEW_PANE_HEIGHT = 180.dp
private val CATEGORY_COL_WIDTH = 220.dp
private val CHANNEL_LABEL_WIDTH = 230.dp
private val GUIDE_ROW_HEIGHT = 44.dp
private const val GUIDE_WINDOW_MS = 2 * 60 * 60 * 1000L   // 2h visible timeline
private const val GUIDE_SLOT_MS = 30 * 60 * 1000L         // half-hour header ticks
