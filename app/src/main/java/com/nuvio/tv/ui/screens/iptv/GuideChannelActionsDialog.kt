package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.GuideLayoutPreset
import com.nuvio.tv.ui.components.NuvioDialog

private const val FOCUS_ATTEMPTS = 10

/** Menu entry is harmless; personalization requires choosing a labeled action. */
@Composable
internal fun GuideChannelActionsDialog(
    channelName: String,
    favorite: Boolean,
    actions: List<GuideChannelMenuPolicy.Action>,
    onFavorite: () -> Unit,
    /** Non-null when the channel keeps an archive and a programme is airing: replay it from its start. */
    onStartOver: (() -> Unit)? = null,
    onAction: (GuideChannelMenuPolicy.Action) -> Unit,
    onGuideSize: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    NuvioDialog(onDismiss = onDismiss, title = channelName, width = 420.dp, scrollable = true) {
        Button(onClick = onFavorite, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
            Text(stringResource(if (favorite) R.string.iptv_guide_remove_favorite else R.string.iptv_guide_add_favorite))
        }
        onStartOver?.let {
            Button(onClick = it, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.iptv_guide_start_over)) }
        }
        actions.filter { it != GuideChannelMenuPolicy.Action.REMOVE_FAVORITE }.forEach { action ->
            Button(onClick = { onAction(action) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(when (action) {
                    GuideChannelMenuPolicy.Action.MOVE_UP -> R.string.iptv_guide_move_up
                    GuideChannelMenuPolicy.Action.MOVE_DOWN -> R.string.iptv_guide_move_down
                    GuideChannelMenuPolicy.Action.HIDE -> R.string.iptv_guide_hide_channel
                    GuideChannelMenuPolicy.Action.REMOVE_FAVORITE -> R.string.iptv_guide_remove_favorite
                }))
            }
        }
        Button(onClick = onGuideSize, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.iptv_guide_size)) }
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
internal fun GuideLayoutDialog(selected: GuideLayoutPreset, onSelect: (GuideLayoutPreset) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.iptv_guide_size), width = 420.dp) {
        // The dialog window settles its own first-child focus after we first ask, so keep asking
        // (a few frames at most) until the size in use actually holds focus.
        LaunchedEffect(Unit) {
            repeat(FOCUS_ATTEMPTS) {
                withFrameNanos { }
                if (focused) return@LaunchedEffect
                focus.requestFocus()
            }
        }
        GuideLayoutPreset.entries.forEach { preset ->
            Button(onClick = { onSelect(preset) }, modifier = Modifier.fillMaxWidth()
                .then(if (preset == selected) Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused } else Modifier)) {
                Text(stringResource(when (preset) {
                    GuideLayoutPreset.COMPACT -> R.string.iptv_guide_size_compact
                    GuideLayoutPreset.STANDARD -> R.string.iptv_guide_size_standard
                    GuideLayoutPreset.LARGE -> R.string.iptv_guide_size_large
                }))
            }
        }
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
    }
}
