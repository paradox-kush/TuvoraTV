package com.nuvio.tv.ui.screens.multiview

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.multiview.TileStatus
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * One tile: its video surface, its state (loading, failed, empty), its name, the audio badge, and the focus ring.
 * No decisions here: keys go to [onKey], focus to [onFocused], and everything shown comes from [tile].
 *
 * The focus ring marks the selected tile and stays while a panel is open over the grid; it is separate from the
 * audio badge (a speaker, or a pin when the sound is kept here).
 */
@Composable
internal fun MultiviewTile(
    tile: MultiviewTileUi,
    surfaceOwner: FrameLayout?,
    focusRequester: FocusRequester,
    canFocus: Boolean,
    onFocused: () -> Unit,
    onKey: (KeyEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val latestOnFocused by rememberUpdatedState(onFocused)
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    var hasFocus by remember { mutableStateOf(false) }
    val ring = tile.focused || hasFocus
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
        when (tile.status) {
            TileStatus.EMPTY -> EmptyTileBody()
            TileStatus.LOADING -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).size(36.dp),
            )
            TileStatus.FAILED -> FailedTileBody(tile)
            TileStatus.PLAYING -> Unit
        }
        if (tile.status != TileStatus.EMPTY) TileLabel(tile, Modifier.align(Alignment.BottomStart))
        if (tile.audible || tile.pinned) AudioBadge(tile.pinned, Modifier.align(Alignment.TopEnd).padding(NuvioTheme.spacing.sm))
        Box(
            Modifier
                .fillMaxSize()
                .border(
                    width = if (ring) 4.dp else 1.dp,
                    color = if (ring) NuvioTheme.colors.FocusRing else Color.White.copy(alpha = 0.12f),
                    shape = shape,
                ),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.EmptyTileBody() {
    Column(
        modifier = Modifier.align(Alignment.Center).padding(NuvioTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = stringResource(R.string.multiview_add_tile),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
        )
        Text(
            text = stringResource(R.string.multiview_add_tile_hint),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.FailedTileBody(tile: MultiviewTileUi) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(NuvioTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = tile.failure?.text.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.Error,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.multiview_failed_hint),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = NuvioTheme.spacing.xs),
        )
    }
}

@Composable
private fun TileLabel(tile: MultiviewTileUi, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
    ) {
        Text(
            text = tile.name ?: stringResource(R.string.multiview_tile_unnamed, tile.slot + 1),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        tile.nowPlaying?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AudioBadge(pinned: Boolean, modifier: Modifier) {
    val description = stringResource(if (pinned) R.string.multiview_audio_pinned else R.string.multiview_audio_heard)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.xs))
            .background(if (pinned) NuvioTheme.colors.Primary else Color.Black.copy(alpha = 0.66f))
            .padding(horizontal = NuvioTheme.spacing.xs, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.VolumeUp,
            contentDescription = description,
            tint = if (pinned) NuvioTheme.colors.OnPrimary else Color.White,
            modifier = Modifier.size(18.dp),
        )
        if (pinned) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                tint = NuvioTheme.colors.OnPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
