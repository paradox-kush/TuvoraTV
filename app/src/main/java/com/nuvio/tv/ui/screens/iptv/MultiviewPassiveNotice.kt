package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * "Added to Multiview" confirmation. Unlike [com.nuvio.tv.ui.components.NuvioUndoToast] it is PASSIVE:
 * no button, no focus grab, no key or BACK interception. The channel row (or the fullscreen anchor) it
 * was raised from is still on screen, so it keeps D-pad focus and OK keeps acting on it. Dismisses itself
 * after [durationMillis]; the timer restarts whenever [key] changes (a new result replaces the shown one).
 */
@Composable
fun MultiviewPassiveNotice(
    message: String,
    onDismiss: () -> Unit,
    durationMillis: Long,
    modifier: Modifier = Modifier,
    key: Any? = message,
) {
    val latestDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(key) {
        delay(durationMillis)
        latestDismiss()
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextPrimary,
        modifier = modifier
            .widthIn(max = 720.dp)
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(NuvioTheme.colors.BackgroundElevated)
            .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(NuvioTheme.radii.md))
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
    )
}
