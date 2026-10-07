@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.mediaserver

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow
import com.nuvio.tv.core.mediaserver.flow.ServerStatus
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * One server's screen (Settings -> Media servers -> a server): sign in on this device when needed, what shows on
 * Home (the server's own shelves are OFF until turned on here - design D2), libraries as Home rows, and managing
 * the entry (rename, off switch, address sync, sign out, remove). Every row is a focus-ringed settings row; remove
 * and sign-out confirm in a dialog whose default focus is the safe choice (Cancel).
 */
@Composable
internal fun MediaServerDetailsScreen(
    onBack: () -> Unit,
    onSignIn: (key: String) -> Unit,
    viewModel: MediaServerDetailsViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadLibraries() }
    LaunchedEffect(state.removed) { if (state.removed && state.entry == null) onBack() }

    SettingsStandaloneScaffold(title = stringResource(R.string.ms_settings_title), subtitle = "") {
        val entry = state.entry
        if (entry == null) {
            Text(stringResource(R.string.ms_details_gone), style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary)
            return@SettingsStandaloneScaffold
        }
        var renaming by remember { mutableStateOf(false) }
        var confirmSignOut by remember { mutableStateOf(false) }
        var confirmRemove by remember { mutableStateOf(false) }
        val firstFocus = remember { FocusRequester() }
        LaunchedEffect(entry.key) { firstFocus.requestFocusAfterFrames(frames = 3) }
        val needsSignIn = state.status == ServerStatus.NEEDS_SIGN_IN || state.status == ServerStatus.SIGN_IN_AGAIN
        val signedIn = !needsSignIn

        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsDetailHeader(
                title = entry.name,
                subtitle = listOfNotNull(entry.type.productName, entry.address?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/')).joinToString(" · "),
            )
            SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val listState = rememberLazyListState()
                // Signing out inserts the "Sign in" row ABOVE the scrolled position: bring it into view and focus it, or the
                // screen looks unchanged (the row is out of sight) while the sign-out has happened.
                var wasNeedingSignIn by remember { mutableStateOf(needsSignIn) }
                LaunchedEffect(needsSignIn) {
                    if (needsSignIn && !wasNeedingSignIn) {
                        listState.scrollToItem(0)
                        firstFocus.requestFocusAfterFrames(frames = 2)
                    }
                    wasNeedingSignIn = needsSignIn
                }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (needsSignIn) {
                        item(key = "sign-in") {
                            SettingsActionRow(
                                title = stringResource(R.string.ms_details_sign_in),
                                subtitle = stringResource(
                                    if (state.status == ServerStatus.SIGN_IN_AGAIN) R.string.ms_details_sign_in_hint_again else R.string.ms_details_sign_in_hint_new,
                                ),
                                leadingIcon = Icons.AutoMirrored.Filled.Login,
                                onClick = { onSignIn(entry.key) },
                                modifier = Modifier.focusRequester(firstFocus),
                            )
                        }
                    }
                    if (signedIn) {
                        item(key = "home-header") {
                            Text(stringResource(R.string.ms_details_section_home), style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary)
                        }
                        item(key = "home-cw") {
                            SettingsToggleRow(
                                title = stringResource(R.string.ms_details_home_continue),
                                subtitle = null,
                                checked = MediaServerHomeRow.CONTINUE_WATCHING in entry.homeRows,
                                onToggle = { viewModel.setHomeRow(MediaServerHomeRow.CONTINUE_WATCHING, MediaServerHomeRow.CONTINUE_WATCHING !in entry.homeRows) },
                                modifier = Modifier.focusRequester(firstFocus),
                            )
                        }
                        item(key = "home-next") {
                            SettingsToggleRow(
                                title = stringResource(R.string.ms_details_home_next_up),
                                subtitle = null,
                                checked = MediaServerHomeRow.NEXT_UP in entry.homeRows,
                                onToggle = { viewModel.setHomeRow(MediaServerHomeRow.NEXT_UP, MediaServerHomeRow.NEXT_UP !in entry.homeRows) },
                            )
                        }
                        item(key = "home-recent") {
                            SettingsToggleRow(
                                title = stringResource(R.string.ms_details_home_recent),
                                subtitle = stringResource(R.string.ms_details_home_hint),
                                checked = MediaServerHomeRow.RECENTLY_ADDED in entry.homeRows,
                                onToggle = { viewModel.setHomeRow(MediaServerHomeRow.RECENTLY_ADDED, MediaServerHomeRow.RECENTLY_ADDED !in entry.homeRows) },
                            )
                        }
                        item(key = "libraries-header") {
                            Text(
                                stringResource(R.string.ms_details_section_libraries),
                                style = MaterialTheme.typography.titleMedium,
                                color = NuvioTheme.colors.TextPrimary,
                                modifier = Modifier.padding(top = NuvioTheme.spacing.sm),
                            )
                        }
                        when (val libs = state.libraries) {
                            MediaServerDetailsViewModel.LibrariesState.Loading -> item(key = "libraries-loading") {
                                Text(stringResource(R.string.ms_add_checking), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                            }
                            MediaServerDetailsViewModel.LibrariesState.Failed -> item(key = "libraries-failed") {
                                Text(stringResource(R.string.ms_details_libraries_failed), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                            }
                            is MediaServerDetailsViewModel.LibrariesState.Loaded -> {
                                if (libs.libraries.isEmpty()) item(key = "libraries-empty") {
                                    Text(stringResource(R.string.ms_details_libraries_empty), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                                } else libs.libraries.forEach { library ->
                                    item(key = "library-${library.id}") {
                                        SettingsToggleRow(
                                            title = library.name,
                                            subtitle = stringResource(R.string.ms_details_libraries_hint),
                                            checked = library.id in entry.homeLibraries,
                                            onToggle = { viewModel.setHomeLibrary(library, library.id !in entry.homeLibraries) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item(key = "manage-header") {
                        Text(
                            stringResource(R.string.ms_details_section_manage),
                            style = MaterialTheme.typography.titleMedium,
                            color = NuvioTheme.colors.TextPrimary,
                            modifier = Modifier.padding(top = NuvioTheme.spacing.sm),
                        )
                    }
                    item(key = "rename") {
                        SettingsActionRow(
                            title = stringResource(R.string.ms_details_name),
                            subtitle = null,
                            value = entry.name,
                            leadingIcon = Icons.Default.Edit,
                            onClick = { renaming = true },
                        )
                    }
                    item(key = "enabled") {
                        SettingsToggleRow(
                            title = stringResource(R.string.ms_details_enabled),
                            subtitle = stringResource(R.string.ms_details_enabled_hint),
                            checked = entry.enabled,
                            onToggle = { viewModel.setEnabled(!entry.enabled) },
                        )
                    }
                    item(key = "sync-address") {
                        SettingsToggleRow(
                            title = stringResource(R.string.ms_details_sync_address),
                            subtitle = stringResource(R.string.ms_details_sync_address_hint),
                            checked = entry.syncAddress,
                            onToggle = { viewModel.setSyncAddress(!entry.syncAddress) },
                        )
                    }
                    entry.userName?.let { user ->
                        item(key = "user") {
                            SettingsActionRow(title = stringResource(R.string.ms_details_user), subtitle = null, value = user, onClick = {}, enabled = false)
                        }
                    }
                    if (signedIn) {
                        item(key = "sign-out") {
                            SettingsActionRow(
                                title = stringResource(R.string.ms_details_sign_out),
                                subtitle = null,
                                leadingIcon = Icons.AutoMirrored.Filled.Logout,
                                enabled = !state.busy,
                                onClick = { confirmSignOut = true },
                            )
                        }
                    }
                    item(key = "remove") {
                        SettingsActionRow(
                            title = stringResource(R.string.ms_details_remove),
                            subtitle = null,
                            leadingIcon = Icons.Default.Delete,
                            enabled = !state.busy,
                            onClick = { confirmRemove = true },
                        )
                    }
                }
            }
        }

        if (renaming) {
            var draft by remember { mutableStateOf(entry.name) }
            NuvioDialog(onDismiss = { renaming = false }, title = stringResource(R.string.ms_details_rename_title)) {
                val nameFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { nameFocus.requestFocusAfterFrames(frames = 2) }
                MediaServerTextField(
                    label = stringResource(R.string.ms_details_name),
                    value = draft,
                    onValueChange = { draft = it },
                    focusRequester = nameFocus,
                    onImeAction = { viewModel.rename(draft); renaming = false },
                )
                SettingsDialogActionRow {
                    SettingsDialogActionButton(text = stringResource(R.string.ms_dialog_cancel), onClick = { renaming = false })
                    SettingsDialogActionButton(text = stringResource(R.string.ms_details_rename_save), onClick = { viewModel.rename(draft); renaming = false }, primary = true)
                }
            }
        }
        if (confirmSignOut) {
            ConfirmDialog(
                title = stringResource(R.string.ms_details_sign_out_title),
                body = stringResource(R.string.ms_details_sign_out_body, entry.name),
                confirmLabel = stringResource(R.string.ms_details_sign_out_confirm),
                onDismiss = { confirmSignOut = false },
                onConfirm = { confirmSignOut = false; viewModel.signOut() },
            )
        }
        if (confirmRemove) {
            var purge by remember { mutableStateOf(false) }
            ConfirmDialog(
                title = stringResource(R.string.ms_details_remove_title),
                body = stringResource(R.string.ms_details_remove_body, entry.name),
                confirmLabel = stringResource(R.string.ms_details_remove_confirm),
                onDismiss = { confirmRemove = false },
                onConfirm = { confirmRemove = false; viewModel.remove(purgeSavedData = purge) },
                extra = {
                    SettingsToggleRow(
                        title = stringResource(R.string.ms_details_remove_purge),
                        subtitle = null,
                        checked = purge,
                        onToggle = { purge = !purge },
                    )
                },
            )
        }
    }
}

/** A confirm dialog whose initial focus is CANCEL: a stray OK press never signs out or removes. */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    NuvioDialog(onDismiss = onDismiss, title = title) {
        Text(body, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
        extra()
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocusAfterFrames(frames = 2) }
        SettingsDialogActionRow {
            Box(Modifier.focusRequester(cancelFocus)) {
                SettingsDialogActionButton(text = stringResource(R.string.ms_dialog_cancel), onClick = onDismiss)
            }
            SettingsDialogActionButton(text = confirmLabel, onClick = onConfirm, primary = true)
        }
    }
}
