package com.nuvio.tv.ui.screens.player.clean.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.nuvio.tv.core.contracts.LivePausePhase
import com.nuvio.tv.core.contracts.LivePauseView
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.player.ControlButton
import com.nuvio.tv.ui.components.player.rememberRawSvgPainter
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme

private const val BLANK_LINE = "\u00A0"

/** What the live overlay names: the channel (the live "title") and its programme. */
internal data class LiveOverlayInfo(
    val channelName: String,
    val logoUrl: String? = null,
    /** Keep the logo's place even for a channel without one, so zapping doesn't shift the name. */
    val reserveLogoSlot: Boolean = false,
    /** A secondary line under the name when there is no programme (station, competition). */
    val subtitle: String? = null,
    val nowTitle: String? = null,
    val nowStartMs: Long? = null,
    val nowEndMs: Long? = null,
    /** Pre-formatted "Next: 21:00 News". */
    val nextLine: String? = null,
    val status: LiveControlsPolicy.Status = LiveControlsPolicy.Status.LIVE,
    val errorText: String? = null,
    /** Live pause / rewind: why the saved part could not start or ended (shown with Retry + Live). */
    val pauseErrorText: String? = null,
)

/** Every action the live overlay can take; null = that control is not offered here. */
internal class LiveOverlayActions(
    val onPlayPause: () -> Unit,
    val onZapPrevious: (() -> Unit)?,
    val onZapNext: (() -> Unit)?,
    val onRetry: () -> Unit,
    val onSubtitles: () -> Unit,
    val onAudio: () -> Unit,
    val onAspect: (() -> Unit)?,
    val onChannelList: (() -> Unit)?,
    val onToggleFavourite: (() -> Unit)?,
    val onStreamInfo: (() -> Unit)?,
    /** Lane F's manual zoom (VideoZoomDialog), in "More" as in the VOD player. */
    val onZoom: (() -> Unit)? = null,
    val onToggleMore: () -> Unit,
    /** Any focus move on the controls: restart the shared auto-hide. */
    val onKeepAlive: () -> Unit,
    /** DOWN off the button row hides the controls, as in the VOD player. */
    val onHide: () -> Unit,
    /** Live pause / rewind: scrub inside the saved window (ms on the window's timeline). */
    val onSeekWindow: ((Long) -> Unit)? = null,
    /** Live pause / rewind: leave the saved part and go back to the live edge. */
    val onGoLive: (() -> Unit)? = null,
)

/**
 * F28: the ONE live overlay — guide fullscreen and the clean live player both draw this, from the
 * VOD player's own pieces (its scrims, [ControlButton], the `ic_player_*` icons, its title type).
 * The VOD timeline is replaced by a non-seekable line for the airing programme; seek, FF/REW,
 * speed, episodes and sources are not offered on live (owner keep/drop list, 2026-10-03).
 */
@Composable
internal fun BoxScope.LiveControlsOverlay(
    info: LiveOverlayInfo,
    nowMs: Long,
    buttons: LiveControlsPolicy.Buttons,
    paused: Boolean,
    favourite: Boolean,
    moreOpen: Boolean,
    primaryFocus: FocusRequester,
    actions: LiveOverlayActions,
    pause: LivePauseView = LivePauseView.OFF,
) {
    // Time-shifted: the scrub bar takes the default focus (as the VOD timeline does), the buttons follow.
    val scrubbing = pause.phase == LivePausePhase.SHIFTED && actions.onSeekWindow != null
    val playPainter = rememberRawSvgPainter(R.raw.ic_player_play)
    val pausePainter = rememberRawSvgPainter(R.raw.ic_player_pause)
    val subtitlePainter = rememberRawSvgPainter(R.raw.ic_player_subtitles)
    val audioPainter = rememberRawSvgPainter(R.raw.ic_player_audio_filled)
    val aspectPainter = rememberRawSvgPainter(R.raw.ic_player_aspect_ratio)

    Box(Modifier.matchParentSize()) {
        // The VOD player's scrims, same heights and alphas.
        Box(
            Modifier.fillMaxWidth().height(150.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))),
        )
        Box(
            Modifier.fillMaxWidth().height(200.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = NuvioTheme.spacing.xxl, vertical = NuvioTheme.spacing.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                if (info.logoUrl != null) {
                    AsyncImage(
                        model = info.logoUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(NuvioTheme.radii.sm)),
                    )
                } else if (info.reserveLogoSlot) {
                    Spacer(Modifier.size(48.dp))
                }
                Text(
                    text = info.channelName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                LiveStatusBadge(info.status)
            }
            // Both lines always take their place (a non-breaking space when empty): the aimed
            // channel changes on every zap press, and a line that comes and goes moves the name.
            val programme = info.nowTitle?.takeIf { it.isNotBlank() }
                ?: info.subtitle?.takeIf { it.isNotBlank() }
            Text(
                text = programme ?: BLANK_LINE,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = info.nextLine ?: BLANK_LINE,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (info.status == LiveControlsPolicy.Status.FAILED && info.errorText != null) {
                Text(text = info.errorText, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.Error, maxLines = 2)
            }
            if (info.pauseErrorText != null) {
                Text(text = info.pauseErrorText, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.Error, maxLines = 2)
            }

            Spacer(Modifier.height(NuvioTheme.spacing.md))
            // The airing programme's elapsed share where VOD has its seek bar. Not focusable.
            if (scrubbing) {
                LiveScrubBar(pause, primaryFocus, actions.onSeekWindow!!, actions.onKeepAlive)
            } else {
                ProgrammeLine(LiveControlsPolicy.programmeProgress(info.nowStartMs, info.nowEndMs, nowMs))
            }
            Spacer(Modifier.height(NuvioTheme.spacing.lg))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    // The D-pad never leaves the button row: behind fullscreen video there is only the
                    // guide, and focus landing there would re-aim it. DOWN hides (VOD behaviour).
                    modifier = Modifier
                        .focusGroup()
                        .focusProperties { onExit = { cancelFocusChange() } },
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (buttons.retry) {
                        ControlButton(
                            icon = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.clean_live_action_retry),
                            onClick = actions.onRetry,
                            focusRequester = primaryFocus.takeUnless { scrubbing },
                            onDownKey = actions.onHide,
                            onFocused = actions.onKeepAlive,
                        )
                    }
                    ControlButton(
                        icon = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        iconPainter = if (paused) playPainter else pausePainter,
                        contentDescription = stringResource(if (paused) R.string.cd_play else R.string.cd_pause),
                        onClick = actions.onPlayPause,
                        focusRequester = if (buttons.retry || scrubbing) null else primaryFocus,
                        onDownKey = actions.onHide,
                        onFocused = actions.onKeepAlive,
                    )
                    // "LIVE" becomes "-mm:ss · Live" once the viewer is behind: focusable, back to the edge.
                    if (pause.phase != LivePausePhase.OFF) {
                        actions.onGoLive?.let { goLive ->
                            LiveChip(
                                label = if (pause.phase == LivePausePhase.SHIFTED) {
                                    stringResource(R.string.live_pause_chip_behind, LiveControlsPolicy.behindLabel(pause.behindLiveMs))
                                } else {
                                    stringResource(R.string.live_pause_chip_go_live)
                                },
                                onClick = goLive,
                                onFocused = actions.onKeepAlive,
                                onDownKey = actions.onHide,
                            )
                        }
                    }
                    actions.onZapPrevious?.let {
                        ControlButton(
                            icon = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.clean_live_action_previous_channel),
                            onClick = it, onDownKey = actions.onHide, onFocused = actions.onKeepAlive,
                        )
                    }
                    actions.onZapNext?.let {
                        ControlButton(
                            icon = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.clean_live_action_next_channel),
                            onClick = it, onDownKey = actions.onHide, onFocused = actions.onKeepAlive,
                        )
                    }
                    if (buttons.subtitles) {
                        ControlButton(
                            icon = Icons.Default.ClosedCaption, iconPainter = subtitlePainter,
                            contentDescription = stringResource(R.string.cd_subtitles),
                            onClick = actions.onSubtitles, onDownKey = actions.onHide, onFocused = actions.onKeepAlive,
                        )
                    }
                    if (buttons.audio) {
                        ControlButton(
                            icon = Icons.AutoMirrored.Filled.VolumeUp, iconPainter = audioPainter,
                            contentDescription = stringResource(R.string.cd_audio_tracks),
                            onClick = actions.onAudio, onDownKey = actions.onHide, onFocused = actions.onKeepAlive,
                        )
                    }
                    actions.onAspect?.let {
                        ControlButton(
                            icon = Icons.Default.AspectRatio, iconPainter = aspectPainter,
                            contentDescription = stringResource(R.string.cd_aspect_ratio),
                            onClick = it, onDownKey = actions.onHide, onFocused = actions.onKeepAlive,
                        )
                    }
                    if (buttons.channelList) {
                        actions.onChannelList?.let {
                            ControlButton(
                                icon = Icons.AutoMirrored.Filled.List,
                                contentDescription = stringResource(R.string.iptv_guide_channel_list_title),
                                onClick = it, onDownKey = actions.onHide, onFocused = actions.onKeepAlive,
                            )
                        }
                    }
                    // "More": the VOD player's slide-out row, holding the less frequent actions.
                    AnimatedVisibility(
                        visible = moreOpen,
                        enter = slideInHorizontally(tween(NuvioMotion.tokens.durations.fast)) { it / 2 } +
                            fadeIn(tween(NuvioMotion.tokens.durations.fast)),
                        exit = slideOutHorizontally(tween(160)) { it / 2 } + fadeOut(tween(160)),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                            if (buttons.favourite) {
                                actions.onToggleFavourite?.let {
                                    ControlButton(
                                        icon = if (favourite) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = stringResource(
                                            if (favourite) R.string.live_action_remove_favourite else R.string.live_action_add_favourite,
                                        ),
                                        onClick = it, onDownKey = actions.onHide, onFocused = actions.onKeepAlive,
                                    )
                                }
                            }
                            actions.onZoom?.let {
                                ControlButton(
                                    icon = Icons.Default.ZoomIn,
                                    contentDescription = stringResource(R.string.player_zoom_title),
                                    onClick = it, onDownKey = actions.onHide, onFocused = actions.onKeepAlive,
                                )
                            }
                            actions.onStreamInfo?.let {
                                ControlButton(
                                    icon = Icons.Default.Info,
                                    contentDescription = stringResource(R.string.cd_stream_info),
                                    onClick = it, onDownKey = actions.onHide, onFocused = actions.onKeepAlive,
                                )
                            }
                            // TODO(F28-next): switch engine, open in external player, report issue —
                            // they need clean-host plumbing (see the lane A report). Start over goes
                            // here too once F35 catch-up is verified on device.
                        }
                    }
                    ControlButton(
                        icon = if (moreOpen) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(if (moreOpen) R.string.cd_close_more_actions else R.string.cd_more_actions),
                        onClick = actions.onToggleMore, onDownKey = actions.onHide, onFocused = actions.onKeepAlive,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveStatusBadge(status: LiveControlsPolicy.Status) {
    // Time-shifted playback shows its offset on the chip in the button row, not a second badge here.
    if (status == LiveControlsPolicy.Status.SHIFTED) return
    val (label, color) = when (status) {
        LiveControlsPolicy.Status.SHIFTED -> "" to Color.White
        LiveControlsPolicy.Status.PREPARING -> stringResource(R.string.live_pause_status_preparing) to Color.White
        LiveControlsPolicy.Status.LIVE -> stringResource(R.string.iptv_guide_live) to NuvioTheme.colors.Primary
        LiveControlsPolicy.Status.PAUSED -> stringResource(R.string.iptv_guide_paused) to Color.White
        LiveControlsPolicy.Status.TUNING -> stringResource(R.string.iptv_guide_tuning) to Color.White
        LiveControlsPolicy.Status.RECONNECTING -> stringResource(R.string.clean_live_status_reconnecting) to Color.White
        LiveControlsPolicy.Status.FAILED -> stringResource(R.string.iptv_guide_unavailable) to NuvioTheme.colors.Error
    }
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = if (status == LiveControlsPolicy.Status.LIVE) Color.Black else color,
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.xs))
            .background(if (status == LiveControlsPolicy.Status.LIVE) color else Color.White.copy(alpha = 0.16f))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 2.dp),
    )
}

/** The "-mm:ss · Live" chip: a button in the row, white fill + black text when focused (like [ControlButton]). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveChip(label: String, onClick: () -> Unit, onFocused: () -> Unit, onDownKey: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        modifier = Modifier
            .height(NuvioTheme.spacing.xxxl)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) { onDownKey(); true } else false
            },
    ) {
        Box(Modifier.padding(horizontal = NuvioTheme.spacing.md), contentAlignment = Alignment.Center) {
            Text(text = label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The saved window as a bar the viewer scrubs with LEFT / RIGHT. It is the default focus while
 * time-shifted, so a press of LEFT right after pausing rewinds at once; DOWN reaches the buttons.
 * Focused it grows and shows its knob (the visible-focus rule).
 */
@Composable
private fun LiveScrubBar(
    pause: LivePauseView,
    focusRequester: FocusRequester,
    onSeek: (Long) -> Unit,
    onKeepAlive: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val span = (pause.windowEndMs - pause.windowStartMs).coerceAtLeast(1L)
    val fraction = ((pause.positionMs - pause.windowStartMs).toFloat() / span).coerceIn(0f, 1f)
    val description = stringResource(R.string.live_pause_scrub_cd)
    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .semantics { contentDescription = description }
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onKeepAlive() }
            .onPreviewKeyEvent { event ->
                val left = event.key == Key.DirectionLeft
                val right = event.key == Key.DirectionRight
                if (!left && !right) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    val step = LiveControlsPolicy.scrubStepMs(event.nativeKeyEvent.repeatCount)
                    onSeek(
                        LiveControlsPolicy.scrubTarget(
                            pause.positionMs, pause.windowStartMs, pause.windowEndMs,
                            if (left) LiveControlsPolicy.ScrubDirection.BACK else LiveControlsPolicy.ScrubDirection.FORWARD,
                            step,
                        ),
                    )
                    onKeepAlive()
                }
                true
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val barHeight = if (focused) 10.dp else 4.dp
        Box(
            Modifier.fillMaxWidth().height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(Color.White.copy(alpha = if (focused) 0.35f else 0.2f)),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(NuvioTheme.colors.Primary))
        }
        if (focused) {
            Box(
                Modifier
                    .padding(start = 0.dp)
                    .fillMaxWidth(fraction.coerceAtLeast(0.001f))
                    .height(14.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(Color.White))
            }
        }
    }
}

@Composable
private fun ProgrammeLine(fraction: Float?) {
    Box(
        Modifier.fillMaxWidth().height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.2f)),
    ) {
        if (fraction != null) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(fraction)
                    .background(NuvioTheme.colors.Primary),
            )
        }
    }
}

/**
 * F28: names the aspect mode just chosen, briefly, as the VOD player's aspect indicator does — the
 * mode cycles on one button, so without it the viewer can't tell where they are in the cycle.
 */
@Composable
internal fun BoxScope.LiveAspectIndicator(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = NuvioTheme.spacing.xxl)
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
    )
}
