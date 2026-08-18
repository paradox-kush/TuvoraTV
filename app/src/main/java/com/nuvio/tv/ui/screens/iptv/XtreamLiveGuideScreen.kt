package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.placeholderCardShimmer
import com.nuvio.tv.ui.components.rememberPlaceholderShimmerOffsetState
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import com.nuvio.tv.core.analytics.Breadcrumbs
import com.nuvio.tv.core.analytics.LivePlaybackFreezeReporter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.enableComposeSurfaceSyncWorkaroundIfAvailable
import com.nuvio.tv.ui.screens.player.findInvalidResponseCodeException
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

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
    /**
     * Launches a catch-up replay in the full player. Catch-up does NOT play in the guide's preview
     * surface: the three behaviours a recording must not have (channel zapping, live-edge resume,
     * the freeze watchdog) and the scrub bar all live in PlayerScreen, so the flag that turns them
     * off has to be carried there.
     */
    onPlayCatchUp: (url: String, title: String, contentId: String, startMs: Long, endMs: Long) -> Unit = { _, _, _, _, _ -> },
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
    LaunchedEffect(nowMs) { viewModel.onMinuteTick(nowMs) }
    val windowStartMs = uiState.windowStartMs

    // RIGHT from a category must land on the channel list — without this, focus search looks
    // rightward at the (non-focusable) preview pane and jumps up to the tabs instead.
    val channelListFocus = remember { FocusRequester() }

    // Moving right off the category list hands the whole width to the guide: the category column
    // collapses and the timeline gains roughly two more half-hour columns. LEFT brings it back.
    //
    // The column is never merely hidden — it animates to zero width and the way back is an
    // explicit key handler rather than Compose focus search, because a zero-width LazyColumn has
    // nothing focusable for a focus search to find. Expand first, then request focus.
    val categoryListFocus = remember { FocusRequester() }
    var inCategory by remember(uiState.selectedCategoryId) { mutableStateOf(false) }
    var pendingCategoryFocus by remember { mutableStateOf(false) }
    val categoryColWidth by animateDpAsState(
        targetValue = if (inCategory) 0.dp else CATEGORY_COL_WIDTH,
        label = "guideCategoryColumnWidth"
    )
    LaunchedEffect(pendingCategoryFocus, inCategory) {
        if (pendingCategoryFocus && !inCategory) {
            runCatching { categoryListFocus.requestFocus() }
            pendingCategoryFocus = false
        }
    }
    fun exitCategory() {
        inCategory = false
        pendingCategoryFocus = true
    }
    // focusRestorer with no saved child fails the enter and focus wanders — fall back to row 1.
    val firstChannelFocus = remember { FocusRequester() }

    // Focus inside the timeline: which channel's cells the cursor is in, and which edge to restore
    // to after the window scrolls (the cell that was focused no longer exists once it rebuilds).
    var timelineChannelId by remember { mutableStateOf<String?>(null) }
    var pendingEdgeFocus by remember { mutableStateOf(0) }
    val leadingCellFocus = remember { FocusRequester() }
    val trailingCellFocus = remember { FocusRequester() }
    val channelRowFocus = remember { FocusRequester() }
    // Shown only for the airing programme on an archive channel — the one state with two
    // reasonable destinations (see GuideCellIntent).
    var sheetProgramme by remember { mutableStateOf<XtreamProgram?>(null) }

    // Launch a replay in the full player, once.
    val replayLaunch = uiState.replayLaunch
    LaunchedEffect(replayLaunch) {
        val launch = replayLaunch ?: return@LaunchedEffect
        viewModel.consumeReplayLaunch()
        onPlayCatchUp(launch.url, launch.title, launch.contentId, launch.programmeStartMs, launch.programmeEndMs)
    }

    // Restore the cursor to the window's new edge after travelling, so holding LEFT keeps going.
    LaunchedEffect(windowStartMs, pendingEdgeFocus) {
        if (pendingEdgeFocus == 0) return@LaunchedEffect
        val target = if (pendingEdgeFocus < 0) leadingCellFocus else trailingCellFocus
        // The new window can be empty of actionable cells (an archive that ends here); dropping
        // back to the channel row is the honest answer rather than trapping the cursor.
        if (runCatching { target.requestFocus() }.isFailure) {
            runCatching { channelRowFocus.requestFocus() }
            timelineChannelId = null
        }
        pendingEdgeFocus = 0
    }

    // BACK leaves the timeline (and returns the guide to now) before it collapses fullscreen or
    // exits the guide — LEFT/RIGHT are spent on travelling, so BACK is the way out.
    BackHandler(enabled = !fullscreen && timelineChannelId != null) {
        timelineChannelId = null
        sheetProgramme = null
        viewModel.resetWindowToLive()
        if (runCatching { channelRowFocus.requestFocus() }.isFailure) {
            runCatching { firstChannelFocus.requestFocus() }
        }
    }

    // ...and then BACK unwinds the category depth before it leaves the guide. Entering a category
    // is now a navigation step (the column collapses), so BACK has to undo that step rather than
    // exiting the screen from underneath it. Guarded against the timeline handler above (and
    // against fullscreen, whose own handler collapses the video) so exactly one is ever enabled.
    BackHandler(enabled = !fullscreen && timelineChannelId == null && inCategory) {
        exitCategory()
    }

    // Moving off the row (DOWN out of the timeline lands on the next channel) leaves the old row's
    // cells focusable behind the cursor — clear the timeline when the focused channel changes.
    LaunchedEffect(uiState.focusedChannelId) {
        if (timelineChannelId != null && timelineChannelId != uiState.focusedChannelId) {
            timelineChannelId = null
            sheetProgramme = null
        }
    }

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
                    /**
                     * The freeze that raises no error.
                     *
                     * A provider closing the socket mid-stream surfaces as ENDED (or IDLE), not as
                     * a PlaybackException — so [onPlayerError] never fires and, before this, the
                     * preview simply stopped on a frozen frame with nothing detecting it. Caught on
                     * an Onn 4K, 2026-08-18. PlayerScreen has had this rule for its own surface
                     * since 2026-08-17 (PlayerLiveSamplingPolicy); the guide never got it.
                     */
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                            viewModel.onPreviewPlaybackStalled(playbackState)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // No engine failover here — OK on the row re-tunes. Log for field triage.
                        Log.w("LiveGuide", "preview playback error: ${error.errorCodeName}")
                        // Token-shaped HTTP failures (Stalker create_link TTL, session rotated by
                        // another device) get one fresh link + in-place re-tune from the VM.
                        val authStatus = error.findInvalidResponseCodeException()?.responseCode
                        if (authStatus != null) {
                            viewModel.onPreviewAuthError(authStatus)
                            return
                        }
                        // The panel answered our `.ts` request with a container that isn't TS
                        // (typically a 302 to an HLS playlist). One re-tune forcing HLS.
                        if (PlayerMediaSourceFactory.isContainerMismatch(error)) {
                            viewModel.onPreviewContainerMismatch()
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        // Only does anything after a container retry — that is when the guess the
                        // retry made becomes a fact worth remembering for the rest of the session.
                        viewModel.onPreviewContainerRetryPlayed()
                        // Start this tune's playing clock; the budget is restored only if it goes
                        // on to play for MIN_STABLE_PLAYBACK_MS (see GuidePreviewFreezePolicy).
                        viewModel.onPreviewFramePlayed()
                    }
                })
            }
    }
    DisposableEffect(Unit) {
        onDispose {
            Breadcrumbs.playbackStopped()
            previewPlayer.release()
        }
    }
    BackHandler(enabled = fullscreen) { onFullscreenChange(false) }

    // Fullscreen controls overlay: shown on entry and on any key, auto-hides while playing.
    var paused by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(false) }
    var controlsTick by remember { mutableStateOf(0) }
    fun showControls() { controlsVisible = true; controlsTick++ }

    // Channel zapping while fullscreen. UP/DOWN move a *pending* selection immediately — the
    // overlay names it right away so the remote feels instant — and the actual tune is debounced,
    // so holding the key walks ten channels without opening ten provider connections (panels cap
    // concurrent connections, often at 1, and each tune is a fresh handshake).
    var pendingZap by remember { mutableStateOf<GuideChannel?>(null) }
    fun zap(delta: Int) {
        val channels = uiState.channels
        val current = pendingZap ?: uiState.previewChannel
        val currentIndex = channels.indexOfFirst { it.contentId == current?.contentId }
        val target = LiveChannelZapPolicy.targetIndex(currentIndex, delta, channels.size) ?: return
        pendingZap = channels.getOrNull(target) ?: return
        showControls()
    }
    // Commit the pending zap once the viewer stops moving. Re-keyed on pendingZap, so every further
    // press cancels the previous wait and restarts it.
    LaunchedEffect(pendingZap?.contentId) {
        val target = pendingZap ?: return@LaunchedEffect
        if (target.contentId == uiState.previewChannel?.contentId) return@LaunchedEffect
        delay(LiveChannelZapPolicy.COMMIT_DELAY_MS)
        viewModel.playPreview(target)
    }
    // The pending marker has done its job the moment the preview actually carries that channel.
    LaunchedEffect(uiState.previewChannel?.contentId, pendingZap?.contentId) {
        if (pendingZap != null && pendingZap?.contentId == uiState.previewChannel?.contentId) {
            pendingZap = null
        }
    }

    // The player tunes ONLY when the preview channel changes (OK press / last-played restore) —
    // never on focus movement. ExoPlayer calls are main-looper-bound and non-blocking.
    // previewPlayback carries the (DoH-rewritten when the playlist opts in) URL + Host header.
    fun tunePreview(url: String, headers: Map<String, String>, mimeOverride: String? = null) {
        // Tune intent rather than first frame: the guide's preview player has no snapshot loop,
        // and for crash attribution "a live preview was up" is the fact that matters.
        Breadcrumbs.playbackStarted(
            kind = "live",
            engine = "exoplayer",
            surface = "live_guide",
            container = LivePlaybackFreezeReporter.streamContainerOf(url),
            nowMs = System.currentTimeMillis(),
        )
        previewPlayer.setMediaSource(
            previewSourceFactory.createMediaSource(context, url, headers, mimeTypeOverride = mimeOverride)
        )
        previewPlayer.prepare()
        previewPlayer.play()
    }

    val previewPlayback = uiState.previewPlayback
    val previewMimeOverride = uiState.previewMimeOverride
    LaunchedEffect(previewPlayback, previewMimeOverride) {
        val prepared = previewPlayback ?: return@LaunchedEffect
        paused = false
        tunePreview(prepared.url, prepared.headers, previewMimeOverride)
    }

    // Pause holds the frame; resume reloads instead of unpausing (a paused live buffer goes
    // stale, and rejoining the live edge is the expected zap behavior).
    fun togglePause() {
        val prepared = uiState.previewPlayback ?: return
        val target = !paused
        paused = target
        if (target) previewPlayer.pause() else tunePreview(prepared.url, prepared.headers, previewMimeOverride)
        showControls()
    }

    // Entering fullscreen peeks the controls; leaving it drops any half-walked zap and rejoins the
    // live edge if paused.
    LaunchedEffect(fullscreen) {
        if (fullscreen) showControls()
        else {
            controlsVisible = false
            pendingZap = null
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
                    tunePreview(prepared.url, prepared.headers, uiState.previewMimeOverride)
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
            // BACK is NOT consumed (BackHandler collapses). UP/DOWN zap channels.
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
                        // The live-TV remote split: UP/DOWN are the channel keys.
                        Key.DirectionUp -> zap(-1)
                        Key.DirectionDown -> zap(1)
                        else -> showControls()
                    }
                }
                true // consume KeyUp of handled keys too, so the locked row never clicks
            }
    ) {
        // Breathing room: the guide's left edge sits on the same 52dp content gutter the rails
        // use (none while fullscreen — the video must cover the whole screen).
        Row(
            Modifier
                .fillMaxSize()
                .then(
                    if (fullscreen) Modifier
                    else Modifier.padding(start = GUIDE_START_PADDING, end = NuvioTheme.spacing.xl)
                ),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            // Category column — collapses to zero width once focus moves into the guide.
            LazyColumn(
                modifier = Modifier
                    .width(categoryColWidth)
                    .fillMaxHeight()
                    .focusRequester(categoryListFocus)
                    .focusRestorer(),
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

                GuideTimeHeaderWithDay(
                    windowStartMs = windowStartMs,
                    nowMs = nowMs,
                    labelWidth = CHANNEL_LABEL_WIDTH,
                )

                when {
                    uiState.loadingChannels -> GuideChannelListSkeleton()
                    uiState.error != null -> ErrorState(
                        message = uiState.error!!,
                        // Retry = re-run the current category load (same path as re-selecting it).
                        onRetry = { viewModel.selectCategory(uiState.selectedCategoryId, force = true) }
                    )
                    uiState.channels.isEmpty() -> EmptyScreenState(
                        title = stringResource(R.string.iptv_guide_no_channels),
                        height = 280.dp
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize()
                            .focusRequester(channelListFocus)
                            // Focus arriving anywhere in the guide means the viewer has committed
                            // to this category — give the grid the width.
                            .onFocusChanged { if (it.hasFocus) inCategory = true }
                            .focusRestorer(firstChannelFocus),
                        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
                    ) {
                        itemsIndexed(uiState.channels, key = { _, it -> it.contentId }) { index, ch ->
                            val isPlaying = ch.contentId == uiState.previewChannel?.contentId
                            val isTimelineRow = timelineChannelId == ch.contentId
                            GuideChannelRow(
                                focusRequester = when {
                                    // Row 0 keeps the restorer's fallback requester; any OTHER
                                    // focused row carries the one BACK-out-of-the-timeline uses.
                                    index == 0 -> firstChannelFocus
                                    ch.contentId == uiState.focusedChannelId -> channelRowFocus
                                    else -> null
                                },
                                clampUp = index == 0,
                                number = index + 1,
                                name = ch.name,
                                // A play glyph icon marks what the preview player is tuned to.
                                isPlaying = isPlaying,
                                logo = ch.logo,
                                epg = uiState.epg[ch.streamId],
                                windowStartMs = windowStartMs,
                                nowMs = nowMs,
                                isFavorite = ch.contentId in favoriteIds,
                                // While fullscreen, focus is locked in place behind the video;
                                // keys are intercepted by the root onPreviewKeyEvent (controls
                                // overlay + play/pause + zapping), BACK collapses.
                                lockFocus = fullscreen,
                                onFocused = { viewModel.onChannelFocused(ch, index) },
                                // OK: tune the preview; OK on the tuned channel: go fullscreen.
                                onClick = {
                                    if (isPlaying) onFullscreenChange(true)
                                    else viewModel.playPreview(ch)
                                },
                                onLongClick = { viewModel.toggleFavorite(ch) },
                                channel = ch,
                                catchUpSupported = uiState.catchUpSupported,
                                timelineActive = isTimelineRow,
                                onExitCategory = if (inCategory) ({ exitCategory() }) else null,
                                // RIGHT steps off the channel into its timeline (the artifact's
                                // model); only the focused row's cells are focusable, so the tree
                                // never carries thousands of targets.
                                onEnterTimeline = { timelineChannelId = ch.contentId },
                                onLeaveTimeline = {
                                    timelineChannelId = null
                                    viewModel.resetWindowToLive()
                                },
                                onTravel = { slots ->
                                    pendingEdgeFocus = slots
                                    viewModel.travelWindow(slots)
                                },
                                onProgrammeClick = { programme ->
                                    when (GuideCellIntent.forAction(
                                        guideActionFor(programme, ch, nowMs, uiState.catchUpSupported)
                                    )) {
                                        GuideCellIntent.Intent.REPLAY -> viewModel.startReplay(ch, programme)
                                        GuideCellIntent.Intent.OPEN_SHEET -> sheetProgramme = programme
                                        GuideCellIntent.Intent.PLAY_LIVE ->
                                            if (isPlaying) onFullscreenChange(true) else viewModel.playPreview(ch)
                                        GuideCellIntent.Intent.NONE -> Unit
                                    }
                                },
                                leadingEdgeFocus = leadingCellFocus,
                                trailingEdgeFocus = trailingCellFocus,
                                nowFraction = if (GuideTimeTravel.containsNow(windowStartMs, nowMs)) {
                                    GuideTimeTravel.nowFraction(windowStartMs, nowMs)
                                } else null,
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
                    // The guide row is inset by the content gutter and the category column gap —
                    // offset the overlay by the same amounts so it lands exactly on the video slot.
                    // Tracks the animated column so the overlay stays on the video slot as the
                    // category column collapses.
                    .padding(start = GUIDE_START_PADDING + categoryColWidth + NuvioTheme.spacing.md)
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
                // A half-walked zap names the channel the viewer is ON, not the one still playing.
                val overlayChannel = pendingZap ?: uiState.previewChannel
                LiveControlsOverlay(
                    channel = overlayChannel,
                    epg = overlayChannel?.let { uiState.epg[it.streamId] },
                    paused = paused,
                    tuning = pendingZap != null,
                )
            }
        }

        // START_OVER is the only state with two destinations, so it is the only one that asks.
        val sheet = sheetProgramme
        val sheetChannel = uiState.focusedChannel
        if (sheet != null && sheetChannel != null && !fullscreen) {
            GuideStartOverSheet(
                channelName = sheetChannel.name,
                programme = sheet,
                onStartOver = {
                    sheetProgramme = null
                    viewModel.startReplay(sheetChannel, sheet)
                },
                onWatchLive = {
                    sheetProgramme = null
                    viewModel.playPreview(sheetChannel)
                },
            )
        }
    }
}

/** Fullscreen live controls: bottom scrim with channel + now/next EPG and the play state. */
@Composable
private fun BoxScope.LiveControlsOverlay(
    channel: GuideChannel?,
    epg: GuideEpg?,
    paused: Boolean,
    /** A zap is walking ahead of the stream — the channel named here is not on screen yet. */
    tuning: Boolean = false,
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
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(NuvioTheme.radii.sm))
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
                        text = stringResource(R.string.iptv_guide_next_programme, timeRange(next), next.title),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                // Tuning outranks paused: it is the newer fact, and the frame on screen still
                // belongs to the old channel either way.
                val stateColor = if (paused && !tuning) Color.White else NuvioTheme.colors.Primary
                Icon(
                    imageVector = if (paused && !tuning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = stateColor,
                    modifier = Modifier.size(NuvioTheme.spacing.xl)
                )
                Text(
                    text = stringResource(
                        when {
                            tuning -> R.string.iptv_guide_tuning
                            paused -> R.string.iptv_guide_paused
                            else -> R.string.iptv_guide_live
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = stateColor
                )
            }
        }
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        Text(
            text = stringResource(R.string.iptv_guide_fullscreen_hint),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

/** Shimmer stand-in for the channel list while a category loads — same row geometry as
 *  [GuideChannelRow] (label block + timeline cells) so the real rows land without a shift. */
@Composable
private fun GuideChannelListSkeleton() {
    val shimmerOffsetState = rememberPlaceholderShimmerOffsetState(label = "guideChannelSkeleton")
    Column(Modifier.fillMaxSize()) {
        repeat(GUIDE_SKELETON_ROW_COUNT) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GUIDE_ROW_HEIGHT)
                    .padding(horizontal = NuvioTheme.spacing.md, vertical = 1.dp)
                    .padding(horizontal = NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.width(CHANNEL_LABEL_WIDTH).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    GuideSkeletonBlock(width = 30.dp, height = 12.dp, shimmerOffsetState = shimmerOffsetState)
                    GuideSkeletonBlock(
                        width = 30.dp, height = 30.dp,
                        cornerRadius = NuvioTheme.radii.xs,
                        shimmerOffsetState = shimmerOffsetState
                    )
                    GuideSkeletonBlock(width = 140.dp, height = 12.dp, shimmerOffsetState = shimmerOffsetState)
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = NuvioTheme.spacing.sm, top = 3.dp, bottom = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf(1f, 1.6f, 1.2f).forEach { cellWeight ->
                        Box(
                            modifier = Modifier
                                .weight(cellWeight)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                                .placeholderCardShimmer(
                                    shimmerOffsetState = shimmerOffsetState,
                                    backgroundColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.4f)
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideSkeletonBlock(
    width: Dp,
    height: Dp,
    shimmerOffsetState: androidx.compose.runtime.State<Float>,
    cornerRadius: Dp = NuvioTheme.radii.xxs
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .placeholderCardShimmer(
                shimmerOffsetState = shimmerOffsetState,
                backgroundColor = NuvioTheme.colors.SurfaceVariant
            )
    )
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
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            // Focused = solid Primary fill (the app's D-pad vocabulary); the selected-but-unfocused
            // category keeps the muted elevated fill so the two states never read the same.
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
            // OK enters the category, the same as RIGHT. Selection already happened on focus, so
            // re-running it was a no-op — pressing OK on a category appeared to do nothing at all.
            .clickable {
                latestOnFocused()
                runCatching { rightFocus.requestFocus() }
            }
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                focused -> NuvioTheme.colors.OnPrimary
                selected -> NuvioTheme.colors.TextPrimary
                else -> NuvioTheme.colors.TextSecondary
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GuideChannelRow(
    number: Int,
    name: String,
    isPlaying: Boolean,
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
    channel: GuideChannel,
    catchUpSupported: Boolean,
    timelineActive: Boolean,
    onEnterTimeline: () -> Unit,
    /** LEFT off the channel list re-opens the collapsed category column. Null while it is open. */
    onExitCategory: (() -> Unit)? = null,
    onLeaveTimeline: () -> Unit,
    onTravel: (Int) -> Unit,
    onProgrammeClick: (XtreamProgram) -> Unit,
    leadingEdgeFocus: FocusRequester? = null,
    trailingEdgeFocus: FocusRequester? = null,
    nowFraction: Float? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    LaunchedEffect(isFocused) { if (isFocused) latestOnFocused() }
    // The cells only become focus targets once the viewer steps into the timeline, so the request
    // has to wait for that recomposition.
    LaunchedEffect(timelineActive) {
        if (timelineActive) {
            leadingEdgeFocus?.let { requester ->
                if (runCatching { requester.requestFocus() }.isFailure) onLeaveTimeline()
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GUIDE_ROW_HEIGHT)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = 1.dp)
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            // Focus = Primary fill + 2dp Primary border (the app's D-pad vocabulary) without
            // hiding the cells; the currently-tuned channel is marked by the play icon instead.
            .background(if (isFocused) NuvioTheme.colors.Primary.copy(alpha = 0.22f) else Color.Transparent)
            .border(
                if (isFocused) NuvioTheme.spacing.xxs else 0.dp,
                if (isFocused) NuvioTheme.colors.Primary else Color.Transparent,
                RoundedCornerShape(NuvioTheme.radii.sm)
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
            // RIGHT steps off the channel and into its timeline — the artifact's TV model. The
            // channel row itself keeps its own OK semantics untouched (preview, then fullscreen,
            // hold to favourite); the catch-up OK rule belongs to the CELLS, one level in.
            //
            // lockFocus short-circuits everything: while fullscreen the row is a hidden focus
            // anchor and every key belongs to the root handler (play/pause, zapping).
            .onPreviewKeyEvent { event ->
                if (lockFocus || timelineActive || !isFocused) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> {
                        onEnterTimeline()
                        true
                    }
                    // LEFT re-opens the category column. Handled here rather than left to focus
                    // search: the column is collapsed to zero width at this point, so there is
                    // nothing for a search to land on.
                    Key.DirectionLeft -> {
                        val exit = onExitCategory
                        if (exit == null) false else { exit(); true }
                    }
                    else -> false
                }
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Fixed label block: number | logo | name (+favorite star) — the timeline cells fill the rest.
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
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(NuvioTheme.radii.xs))
            )
            if (isPlaying) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = NuvioTheme.colors.Primary,
                    modifier = Modifier.size(NuvioTheme.spacing.lg)
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (isFavorite) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = NuvioTheme.colors.Primary,
                    modifier = Modifier.size(NuvioTheme.spacing.md)
                )
            }
        }
        GuideProgrammeCells(
            programmes = epg?.programmes ?: emptyList(),
            channel = channel,
            windowStartMs = windowStartMs,
            nowMs = nowMs,
            catchUpSupported = catchUpSupported,
            interactive = timelineActive,
            onProgrammeClick = onProgrammeClick,
            onTravel = onTravel,
            leadingEdgeFocus = leadingEdgeFocus,
            trailingEdgeFocus = trailingEdgeFocus,
            nowFraction = nowFraction,
        )
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
                        .clip(RoundedCornerShape(NuvioTheme.radii.xxs))
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
                    text = stringResource(R.string.iptv_guide_min_left, remainingMin),
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
                text = stringResource(R.string.iptv_guide_no_information),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        Spacer(Modifier.weight(1f))
        epg?.next?.let { next ->
            Text(
                text = stringResource(R.string.iptv_guide_next_programme, timeRange(next), next.title),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = stringResource(R.string.iptv_guide_hint),
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.7f),
            maxLines = 1
        )
    }
}

private fun timeRange(p: XtreamProgram): String = "${guideHhMm(p.startMs)}–${guideHhMm(p.endMs)}"

private val PREVIEW_PANE_HEIGHT = 180.dp
private val CATEGORY_COL_WIDTH = 220.dp
private val CHANNEL_LABEL_WIDTH = 230.dp
private val GUIDE_ROW_HEIGHT = 44.dp
private val GUIDE_START_PADDING = 52.dp                   // the app-wide content gutter (Modern rails)
private const val GUIDE_SKELETON_ROW_COUNT = 10
