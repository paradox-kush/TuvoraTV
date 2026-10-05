package com.nuvio.tv.ui.screens.player.clean.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.playback.core.PlaybackTrackId
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.player.DialogButton
import com.nuvio.tv.ui.theme.NuvioTheme

/** Which picker the live overlay has open. */
internal enum class LivePanel { SUBTITLES, AUDIO, STREAM_INFO, ZOOM }

/**
 * F28: the live subtitle/audio picker — the app's dialog with the VOD player's [DialogButton] rows,
 * the selected track drawn as the primary button and focused first.
 */
@Composable
internal fun LiveTrackDialog(
    title: String,
    choices: List<LiveTrackChoices.Choice>,
    onPick: (PlaybackTrackId?) -> Unit,
    onDismiss: () -> Unit,
    /** T6: why some rows can't be picked here, shown above them. */
    note: String? = null,
) {
    val selectedFocus = remember { FocusRequester() }
    NuvioDialog(onDismiss = onDismiss, title = title, scrollable = true) {
        note?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
        }
        choices.forEach { choice ->
            DialogButton(
                text = choice.label,
                onClick = { onPick(choice.id) },
                isPrimary = choice.selected,
                enabled = choice.enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (choice.selected) Modifier.focusRequester(selectedFocus) else Modifier),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { selectedFocus.requestFocus() } }
}

/** F28: stream info for support — the clean session's own facts, never a URL. */
@Composable
internal fun LiveStreamInfoDialog(lines: List<Pair<String, String>>, onDismiss: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.cd_stream_info)) {
        lines.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary)
            }
        }
        DialogButton(
            text = stringResource(R.string.live_action_close),
            onClick = onDismiss,
            isPrimary = true,
            modifier = Modifier.fillMaxWidth().focusRequester(closeFocus),
        )
    }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
}
