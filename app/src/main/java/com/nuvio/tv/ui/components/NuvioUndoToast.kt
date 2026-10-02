package com.nuvio.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * A transient TV notice with one action (Undo), the 10-foot counterpart of a phone snackbar. The
 * action takes D-pad focus when the notice appears — the thing that held focus has usually just
 * gone (a hidden channel or group) — so OK runs it. BACK or any arrow key dismisses the notice and
 * hands focus back via [onDismiss]; so does the [durationMillis] timeout. The caller places it
 * (pass an aligned [modifier]) and restores focus to its own content in [onDismiss] / [onAction].
 */
@Composable
fun NuvioUndoToast(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    durationMillis: Long,
    modifier: Modifier = Modifier,
    /** Changes when a new notice replaces the shown one, restarting the timer and the focus grab. */
    key: Any? = message,
) {
    val actionFocus = remember { FocusRequester() }
    val latestDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(key) {
        runCatching { actionFocus.requestFocus() }
        delay(durationMillis)
        latestDismiss()
    }
    BackHandler { latestDismiss() }
    Row(
        modifier = modifier
            .widthIn(max = 720.dp)
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(NuvioTheme.colors.BackgroundElevated)
            .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(NuvioTheme.radii.md))
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.weight(1f, fill = false),
        )
        Button(
            onClick = onAction,
            modifier = Modifier
                .focusRequester(actionFocus)
                // Arrows leave the notice rather than wander the screen behind it.
                .onPreviewKeyEvent { event ->
                    val arrow = event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
                        event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                    if (!arrow) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) latestDismiss()
                    true
                },
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary,
                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                focusedContentColor = NuvioTheme.colors.TextPrimary,
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(NuvioTheme.radii.sm),
                ),
            ),
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm)),
        ) {
            Text(text = actionLabel, style = MaterialTheme.typography.titleSmall)
        }
    }
}
