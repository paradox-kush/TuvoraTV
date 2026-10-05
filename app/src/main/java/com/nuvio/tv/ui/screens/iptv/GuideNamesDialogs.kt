package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.core.epg.ChannelNameCleaner
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.content.EpgGuideChannelRow
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.screens.settings.GuideAssignState
import com.nuvio.tv.ui.screens.settings.SettingsActionRow

/**
 * Lane G — the playlist details page's "Guide & channel names" card: the B10 coverage census, the
 * F14 manual guide-channel assignment, and the F10 channel-name clean-up. TV twin of the phone's
 * Guide / Channel names settings sections and its long-press "Choose guide channel" picker (the
 * TV guide's own keys belong to lane A's live-controls rework, so the TV entry point is here).
 */
@Composable
internal fun GuideNamesDialog(
    account: XtreamAccount,
    census: String?,
    onDismiss: () -> Unit,
    onAssign: () -> Unit,
    onToggleClean: () -> Unit,
    onEditTags: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val tags = ChannelNameCleaner.parseTags(account.channelNameTags)
    NuvioDialog(onDismiss = onDismiss, title = "Guide & channel names", subtitle = account.name, width = 620.dp, scrollable = true) {
        Text(census ?: "Checking the guide…")
        SettingsActionRow(
            title = "Assign a channel's guide",
            subtitle = "Pick the guide channel for a channel the automatic match missed or got wrong",
            onClick = onAssign,
            modifier = Modifier.focusRequester(first),
        )
        SettingsActionRow(
            title = "Clean up channel names",
            subtitle = "Hides country prefixes, quality tags and symbols — “UK: BBC One FHD ★” shows as “BBC One”",
            value = if (account.cleanChannelNames) "On" else "Off",
            onClick = onToggleClean,
        )
        SettingsActionRow(
            title = "Extra tags to remove",
            subtitle = if (tags.isEmpty()) "None. Words or symbols your provider adds, like VIP or |PRIME| — also used to match the guide"
            else tags.joinToString("  ·  "),
            onClick = onEditTags,
        )
    }
}

/** The tag list, typed once with the remote keyboard. */
@Composable
internal fun ChannelTagsDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    NuvioDialog(
        onDismiss = onDismiss,
        title = "Extra tags to remove",
        subtitle = "Separate tags with commas. Plain words are removed only as whole words (VIP won't touch VIPER).",
        width = 560.dp,
    ) {
        GuideTextField(value = text, onValueChange = { text = it }, onSubmit = { onSave(text) }, modifier = Modifier.focusRequester(field))
        Button(onClick = { onSave(text) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

/**
 * F14 on TV, two steps in one dialog: find the channel (search the playlist's lineup), then pick the
 * guide channel that feeds it (search the playlist's guide sources), or send it back to automatic.
 */
@Composable
internal fun GuideAssignDialog(
    account: XtreamAccount,
    state: GuideAssignState?,
    onSearchChannels: (String) -> Unit,
    onOpenChannel: (GuideAssignState.Channel) -> Unit,
    onSearchOptions: (GuideAssignState.Channel, String) -> Unit,
    onPick: (GuideAssignState.Channel, EpgGuideChannelRow?) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val picking = state?.picking
    if (picking == null) {
        var query by remember { mutableStateOf(state?.query.orEmpty()) }
        val field = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
        NuvioDialog(onDismiss = onDismiss, title = "Assign a channel's guide", subtitle = account.name, width = 640.dp, scrollable = true) {
            GuideTextField(
                value = query,
                onValueChange = { query = it },
                onSubmit = { onSearchChannels(query) },
                placeholder = "Channel name, like “bbc”",
                modifier = Modifier.focusRequester(field),
            )
            Button(onClick = { onSearchChannels(query) }, modifier = Modifier.fillMaxWidth()) { Text("Search channels") }
            val channels = state?.channels.orEmpty()
            if (state != null && channels.isEmpty()) Text("No channel in this playlist matches “${state.query}”.")
            channels.forEach { ch ->
                SettingsActionRow(
                    title = ch.name,
                    subtitle = when {
                        ch.current == null -> "No guide"
                        ch.manual -> "You picked: ${ch.current}"
                        else -> "Automatic: ${ch.current}"
                    },
                    onClick = { onOpenChannel(ch) },
                )
            }
        }
    } else {
        var query by remember(picking.sid) { mutableStateOf(state.optionsQuery) }
        val first = remember(picking.sid) { FocusRequester() }
        LaunchedEffect(picking.sid) { runCatching { first.requestFocus() } }
        NuvioDialog(onDismiss = onBack, title = "Guide for ${picking.name}", subtitle = picking.current?.let { "Now: $it" } ?: "No guide yet", width = 640.dp, scrollable = true) {
            GuideTextField(
                value = query,
                onValueChange = { query = it },
                onSubmit = { onSearchOptions(picking, query) },
                placeholder = "Search the guide",
                modifier = Modifier.focusRequester(first),
            )
            Button(onClick = { onSearchOptions(picking, query) }, modifier = Modifier.fillMaxWidth()) { Text("Search the guide") }
            if (picking.manual) {
                SettingsActionRow(title = "Automatic", subtitle = "Let Tuvora match this channel again", onClick = { onPick(picking, null) })
            }
            if (state.options.isEmpty()) {
                Text("No guide channel matches “${state.optionsQuery}”. If the playlist has no guide yet, open the Live TV guide once to load it.")
            }
            state.options.forEach { row ->
                SettingsActionRow(
                    title = if (row.guideId == picking.current) "✓  ${row.name}" else row.name,
                    subtitle = row.guideId + if (row.sourceIndex > 0) "  ·  EPG source ${row.sourceIndex + 1}" else "",
                    onClick = { onPick(picking, row) },
                )
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to channels") }
        }
    }
}

@Composable
private fun GuideTextField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = placeholder?.let { { Text(it) } },
        // A focused text field swallows D-pad UP/DOWN (device pass 2026-10-05: the dialog opens with
        // focus here, so the results below were unreachable). Same escape as the playlist form's
        // fields (B69, InputFieldKeys): UP/DOWN leave the field; LEFT/RIGHT stay with the cursor.
        modifier = modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            val direction = com.nuvio.tv.ui.screens.account.InputFieldKeys.exitDirection(
                isEditing = true,
                isKeyDown = native.action == android.view.KeyEvent.ACTION_DOWN,
                keyCode = native.keyCode,
            ) ?: return@onPreviewKeyEvent false
            keyboard?.hide()
            focusManager.moveFocus(direction)
            true
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
            autoCorrectEnabled = false,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() }),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = NuvioTheme.colors.BackgroundCard,
            unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
            focusedIndicatorColor = NuvioTheme.colors.FocusRing,
            unfocusedIndicatorColor = NuvioTheme.colors.Border,
            focusedTextColor = NuvioTheme.colors.TextPrimary,
            unfocusedTextColor = NuvioTheme.colors.TextPrimary,
            cursorColor = NuvioTheme.colors.FocusRing,
        ),
    )
}
