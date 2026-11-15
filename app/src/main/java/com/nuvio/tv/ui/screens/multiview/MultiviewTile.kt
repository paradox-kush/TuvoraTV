package com.nuvio.tv.ui.screens.multiview

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.multiview.TileAction
import com.nuvio.tv.core.multiview.TileLoadingPhase
import com.nuvio.tv.core.multiview.TileLoadingPolicy
import com.nuvio.tv.core.multiview.TileStatus
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * One tile: its video surface, its state (loading, failed, empty), its label, the sound badge and the focus ring.
 * No decisions here: keys go to [onKey], focus to [onFocused], and everything shown comes from [tile].
 *
 * Three signals, three shapes: the ring says where the highlight is, the speaker badge says where the sound is, and a
 * filled pin badge with words says the sound is kept. The label strip fades with [chromeAlpha]; the ring, the badge and
 * the tile's own content (loading, failed, empty) never do. [small] tiles (under 300 dp wide) show the name only.
 *
 * Full screen is the picture edge to edge: no ring, no rounded corners, no badge.
 */
@Composable
internal fun MultiviewTile(
    tile: MultiviewTileUi,
    surfaceOwner: FrameLayout?,
    focusRequester: FocusRequester,
    canFocus: Boolean,
    fullscreen: Boolean,
    small: Boolean,
    /** The tile a channel is being picked for: dashed ring (and "Picking a channel…" while it is empty). */
    picking: Boolean,
    chromeAlpha: Float,
    onFocused: () -> Unit,
    onKey: (KeyEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val latestOnFocused by rememberUpdatedState(onFocused)
    val shape = RoundedCornerShape(if (fullscreen) 0.dp else NuvioTheme.radii.sm)
    var hasFocus by remember { mutableStateOf(false) }
    val ring = !fullscreen && (tile.focused || hasFocus)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onFocusChanged {
                hasFocus = it.isFocused
                if (it.isFocused) latestOnFocused()
            }
            .focusProperties { this.canFocus = canFocus }
            .onPreviewKeyEvent(onKey)
            .focusable(),
    ) {
        if (surfaceOwner != null && tile.status != TileStatus.EMPTY) {
            AndroidView(factory = { surfaceOwner }, modifier = Modifier.fillMaxSize())
        }
        val spinnerBeforeFirstFrame = tile.status == TileStatus.LOADING && !tile.reconnecting
        when {
            tile.status == TileStatus.EMPTY -> EmptyTileBody(picking, small)
            spinnerBeforeFirstFrame -> LoadingTileBody(tile, small)
            tile.status == TileStatus.FAILED -> FailedTileBody(tile, small)
            else -> Unit
        }
        if (tile.status != TileStatus.EMPTY && !spinnerBeforeFirstFrame) {
            TileLabel(tile, small, Modifier.align(Alignment.BottomStart).graphicsLayer { alpha = chromeAlpha })
        }
        if (tile.reconnecting && tile.status != TileStatus.FAILED) {
            ReconnectingPill(Modifier.align(Alignment.TopStart).padding(NuvioTheme.spacing.sm))
        }
        val showsPicture = tile.status == TileStatus.PLAYING || (tile.status == TileStatus.LOADING && tile.reconnecting)
        if (!fullscreen && showsPicture) {
            when {
                tile.pinned -> AudioBadge(AudioBadgeKind.PINNED, small, Modifier.align(Alignment.TopEnd).padding(NuvioTheme.spacing.sm))
                tile.audible -> AudioBadge(AudioBadgeKind.HEARD, small, Modifier.align(Alignment.TopEnd).padding(NuvioTheme.spacing.sm))
                tile.mutedByPin -> AudioBadge(AudioBadgeKind.MUTED, small, Modifier.align(Alignment.TopEnd).padding(NuvioTheme.spacing.sm))
            }
        }
        if (!fullscreen) {
            val dashColor = NuvioTheme.colors.FocusRing
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (picking) Modifier.drawBehind {
                            drawRoundRect(
                                color = dashColor,
                                cornerRadius = CornerRadius(8.dp.toPx()),
                                style = Stroke(
                                    width = 3.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 8.dp.toPx())),
                                ),
                            )
                        } else Modifier,
                    )
                    .border(
                        width = if (ring) 4.dp else 1.dp,
                        color = if (ring) NuvioTheme.colors.FocusRing else Color.White.copy(alpha = 0.12f),
                        shape = shape,
                    ),
            )
        }
    }
}

@Composable
private fun BoxScope.EmptyTileBody(picking: Boolean, small: Boolean) {
    Column(
        modifier = Modifier.align(Alignment.Center).padding(NuvioTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.size(if (small) 28.dp else 40.dp),
        )
        Text(
            text = stringResource(if (picking) R.string.multiview_picking else R.string.multiview_add_tile),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
        )
    }
}

/** Before the first picture: the channel name and what is happening, never a bare spinner. */
@Composable
private fun BoxScope.LoadingTileBody(tile: MultiviewTileUi, small: Boolean) {
    // One wake-up when the provider has been quiet for a while; the 20 s stall deadline itself is the watch's.
    var slow by remember(tile.tileId) { mutableStateOf(false) }
    LaunchedEffect(tile.tileId) {
        delay(TileLoadingPolicy.STILL_TRYING_AFTER_MS)
        slow = true
    }
    val phase = TileLoadingPolicy.phase(if (slow) TileLoadingPolicy.STILL_TRYING_AFTER_MS else 0L, tile.rejoining)
    Column(
        modifier = Modifier.align(Alignment.Center).padding(NuvioTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
    ) {
        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(if (small) 20.dp else 28.dp), strokeWidth = 3.dp)
        Text(
            text = tile.name ?: stringResource(R.string.multiview_tile_unnamed, tile.slot + 1),
            fontSize = if (small) 14.sp else 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                when (phase) {
                    TileLoadingPhase.TUNING_IN -> R.string.multiview_tile_tuning_in
                    TileLoadingPhase.STILL_TRYING -> R.string.multiview_tile_still_trying
                    TileLoadingPhase.REJOINING -> R.string.multiview_tile_rejoining
                },
            ),
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/**
 * The reason, as a short title (what fits on a small tile) and the fact in one sentence, on a dimmed card. Only the
 * focused tile shows the key cap with its main action, so the screen never has two "press OK" prompts.
 */
@Composable
private fun BoxScope.FailedTileBody(tile: MultiviewTileUi, small: Boolean) {
    val failure = tile.failure ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.84f))
            .padding(NuvioTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = failure.shortTitle,
            fontSize = if (small) 14.sp else 17.sp,
            fontWeight = FontWeight.Bold,
            color = NuvioTheme.colors.Error,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!small) {
            Text(
                text = failure.text,
                fontSize = 13.sp,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs),
            )
        }
        if (tile.focused) {
            Row(
                modifier = Modifier.padding(top = NuvioTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyCap(stringResource(R.string.multiview_key_ok))
                Text(
                    text = stringResource(failure.primary.labelRes()),
                    fontSize = if (small) 12.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun TileAction.labelRes(): Int = when (this) {
    TileAction.RETRY -> R.string.multiview_menu_retry
    TileAction.REPLACE -> R.string.multiview_menu_replace
    TileAction.OPEN_IN_PLAYER -> R.string.multiview_menu_open_in_player
    TileAction.REMOVE -> R.string.multiview_menu_remove
}

/** A remote key drawn as a small outlined cap. */
@Composable
internal fun KeyCap(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier
            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun ReconnectingPill(modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.multiview_tile_reconnecting),
            fontSize = 12.sp,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** Line 1 is the channel name; line 2 the programme airing now, and only when there is one. Small tiles: the name only. */
@Composable
private fun TileLabel(tile: MultiviewTileUi, small: Boolean, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
    ) {
        Text(
            text = tile.name ?: stringResource(R.string.multiview_tile_unnamed, tile.slot + 1),
            fontSize = if (small) 14.sp else 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!small) {
            tile.nowPlaying?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private enum class AudioBadgeKind { HEARD, PINNED, MUTED }

/**
 * Heard: a dark pill with a speaker. Kept: a wider filled `Secondary` pill with a pin, the speaker and words (the words
 * drop on small tiles). Muted: a crossed speaker on the focused tile while the sound is kept on another one.
 */
@Composable
private fun AudioBadge(kind: AudioBadgeKind, small: Boolean, modifier: Modifier) {
    val description = stringResource(
        when (kind) {
            AudioBadgeKind.HEARD -> R.string.multiview_audio_heard
            AudioBadgeKind.PINNED -> R.string.multiview_audio_pinned
            AudioBadgeKind.MUTED -> R.string.multiview_audio_muted
        },
    )
    val pinned = kind == AudioBadgeKind.PINNED
    val iconSize = if (small) 14.dp else 18.dp
    val content = if (pinned) NuvioTheme.colors.OnSecondary else if (kind == AudioBadgeKind.MUTED) NuvioTheme.colors.TextSecondary else Color.White
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (pinned) NuvioTheme.colors.Secondary else Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = if (small) 6.dp else 8.dp, vertical = if (small) 4.dp else 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pinned) {
            Icon(imageVector = Icons.Default.PushPin, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
        }
        Icon(
            imageVector = if (kind == AudioBadgeKind.MUTED) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
            contentDescription = description,
            tint = content,
            modifier = Modifier.size(iconSize),
        )
        if (pinned && !small) {
            Text(
                text = stringResource(R.string.multiview_audio_pinned_label),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 1,
            )
        }
    }
}
