package com.nuvio.tv.ui.screens.player.clean.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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

/** What the live overlay names: the channel (the live "title") and its programme. */
internal data class LiveOverlayInfo(
    val channelName: String,
    val logoUrl: String? = null,
    /** A secondary line under the name when there is no programme (station, competition). */
    val subtitle: String? = null,
    val nowTitle: String? = null,
    val nowStartMs: Long? = null,
    val nowEndMs: Long? = null,
    /** Pre-formatted "Next: 21:00 News". */
    val nextLine: String? = null,
    val status: LiveControlsPolicy.Status = LiveControlsPolicy.Status.LIVE,
    val errorText: String? = null,
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
    val onToggleMore: () -> Unit,
    /** Any focus move on the controls: restart the shared auto-hide. */
    val onKeepAlive: () -> Unit,
    /** DOWN off the button row hides the controls, as in the VOD player. */
    val onHide: () -> Unit,
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
) {
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
            val programme = info.nowTitle?.takeIf { it.isNotBlank() }
            if (programme != null) {
                Text(
                    text = programme,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                info.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(text = it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            info.nextLine?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.68f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (info.status == LiveControlsPolicy.Status.FAILED && info.errorText != null) {
                Text(text = info.errorText, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.Error, maxLines = 2)
            }

            Spacer(Modifier.height(NuvioTheme.spacing.md))
            // The airing programme's elapsed share where VOD has its seek bar. Not focusable.
            ProgrammeLine(LiveControlsPolicy.programmeProgress(info.nowStartMs, info.nowEndMs, nowMs))
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
                            focusRequester = primaryFocus,
                            onDownKey = actions.onHide,
                            onFocused = actions.onKeepAlive,
                        )
                    }
                    ControlButton(
                        icon = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        iconPainter = if (paused) playPainter else pausePainter,
                        contentDescription = stringResource(if (paused) R.string.cd_play else R.string.cd_pause),
                        onClick = actions.onPlayPause,
                        focusRequester = if (buttons.retry) null else primaryFocus,
                        onDownKey = actions.onHide,
                        onFocused = actions.onKeepAlive,
                    )
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
    val (label, color) = when (status) {
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
