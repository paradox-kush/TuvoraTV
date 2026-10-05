@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.roundToInt

/**
 * F36 manual zoom on TV: width, height and position on top of the aspect mode, one D-pad row per
 * axis. A Dialog (like the speed picker) so it owns focus and Back; the window is NOT dimmed and the
 * card sits at the bottom, because the whole point is watching the picture change while adjusting.
 */
@Composable
internal fun VideoZoomDialog(
    zoom: VideoZoom,
    onAdjust: (VideoZoomAxis, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier = Modifier
                    .padding(bottom = NuvioTheme.spacing.xl)
                    .width(560.dp)
                    .clip(RoundedCornerShape(NuvioTheme.radii.xl))
                    .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.94f))
                    .padding(NuvioTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.player_zoom_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = VideoZoomPolicy.scaleLabel(zoom),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                    )
                }
                ZoomRow(
                    label = stringResource(R.string.player_zoom_both),
                    value = VideoZoomPolicy.scaleLabel(zoom),
                    onDecrease = { onAdjust(VideoZoomAxis.Both, -1) },
                    onIncrease = { onAdjust(VideoZoomAxis.Both, +1) },
                    decreaseModifier = Modifier.focusRequester(firstFocus),
                )
                ZoomRow(
                    label = stringResource(R.string.player_zoom_width),
                    value = percent(zoom.scaleX),
                    onDecrease = { onAdjust(VideoZoomAxis.Width, -1) },
                    onIncrease = { onAdjust(VideoZoomAxis.Width, +1) },
                )
                ZoomRow(
                    label = stringResource(R.string.player_zoom_height),
                    value = percent(zoom.scaleY),
                    onDecrease = { onAdjust(VideoZoomAxis.Height, -1) },
                    onIncrease = { onAdjust(VideoZoomAxis.Height, +1) },
                )
                ZoomRow(
                    label = stringResource(R.string.player_zoom_position_horizontal),
                    value = signedPercent(zoom.panX),
                    onDecrease = { onAdjust(VideoZoomAxis.PanX, -1) },
                    onIncrease = { onAdjust(VideoZoomAxis.PanX, +1) },
                )
                ZoomRow(
                    label = stringResource(R.string.player_zoom_position_vertical),
                    value = signedPercent(zoom.panY),
                    onDecrease = { onAdjust(VideoZoomAxis.PanY, -1) },
                    onIncrease = { onAdjust(VideoZoomAxis.PanY, +1) },
                )
                Spacer(Modifier.size(NuvioTheme.spacing.xs))
                Card(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    ),
                    border = CardDefaults.border(
                        focusedBorder = androidx.tv.material3.Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(10.dp),
                        ),
                    ),
                    shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
                ) {
                    Text(
                        text = stringResource(R.string.subtitle_style_reset),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseModifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        ZoomStepButton(icon = Icons.Default.Remove, onClick = onDecrease, modifier = decreaseModifier)
        Box(
            modifier = Modifier
                .widthIn(min = 96.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = value, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1)
        }
        ZoomStepButton(icon = Icons.Default.Add, onClick = onIncrease)
    }
}

/** D-pad focus must be unmistakable on TV: brighter fill AND a focus-ring border. */
@Composable
private fun ZoomStepButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(NuvioTheme.spacing.xxl)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) {
                    Modifier.border(2.dp, NuvioTheme.colors.FocusRing, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                },
            ),
        colors = IconButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.16f),
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        shape = IconButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(NuvioTheme.spacing.lg))
    }
}

private fun percent(value: Float): String = "${(value * 100).roundToInt()}%"

private fun signedPercent(value: Float): String {
    val p = (value * 100).roundToInt()
    return if (p > 0) "+$p%" else "$p%"
}
