@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.mediaserver

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.api.MediaServerType
import com.nuvio.tv.core.mediaserver.flow.AddError
import com.nuvio.tv.core.mediaserver.flow.AddServerState
import com.nuvio.tv.core.mediaserver.flow.AddStage
import com.nuvio.tv.core.mediaserver.policy.QuickConnectPolicy
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsChoiceChip
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * Add a server / sign an existing entry in on this device (design 5.3 / 5.4). One screen, four steps driven by
 * [com.nuvio.tv.core.mediaserver.flow.AddServerController]: address -> how to sign in -> (Quick Connect code | username +
 * password) -> name it. Quick Connect is the TV's main road (no typing): the code is shown big and the user approves
 * it from a phone or the Jellyfin web UI; the poll runs only while this screen is resumed (CLAUDE.md recurring-network
 * rule). BACK steps back through the stages before leaving.
 */
@Composable
internal fun MediaServerAddScreen(
    onBack: () -> Unit,
    onFinished: () -> Unit,
    viewModel: MediaServerAddViewModel = hiltViewModel(),
) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()

    // The stages are a small stack: BACK goes to the previous stage; from the first one (or the finished one) it leaves.
    BackHandler {
        when (state.stage) {
            AddStage.ADDRESS, AddStage.DONE -> onBack()
            AddStage.CHOOSE_SIGN_IN -> if (viewModel.signingInExisting) onBack() else controller.backToAddress()
            AddStage.QUICK_CONNECT, AddStage.PASSWORD -> controller.backToChoice()
        }
    }

    // Quick Connect polls only while this screen is on top and resumed.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state.stage) {
        if (state.stage == AddStage.QUICK_CONNECT) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { controller.runQuickConnect() }
        }
    }

    SettingsStandaloneScaffold(title = stringResource(R.string.ms_add_title), subtitle = "") {
        when (state.stage) {
            AddStage.ADDRESS -> AddressStep(state, controller::setAddress, controller::selectType, controller::connect, viewModel.signingInExisting)
            AddStage.CHOOSE_SIGN_IN -> ChooseStep(state, controller::startQuickConnect, controller::usePassword, controller::backToAddress, viewModel.signingInExisting)
            AddStage.QUICK_CONNECT -> QuickConnectStep(state, controller::usePassword, controller::backToChoice)
            AddStage.PASSWORD -> PasswordStep(state, controller::submitPassword, controller::backToChoice)
            AddStage.DONE -> state.signedIn?.let { entry ->
                if (viewModel.signingInExisting) {
                    LaunchedEffect(entry.key) { onFinished() }
                } else {
                    NameStep(entry) { name, recent -> viewModel.finish(entry, name, recent, onFinished) }
                }
            }
        }
    }

    state.certPrompt?.let { prompt ->
        NuvioDialog(onDismiss = controller::declineCertificate, title = stringResource(R.string.ms_add_cert_title)) {
            Text(stringResource(R.string.ms_add_cert_body), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
            Text(prompt.authority, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary)
            Text(stringResource(R.string.ms_add_cert_fingerprint), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary)
            Text(prompt.fingerprint.chunked(2).joinToString(":"), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextPrimary)
            val cancelFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { cancelFocus.requestFocusAfterFrames(frames = 2) }
            SettingsDialogActionRow {
                Box(Modifier.focusRequester(cancelFocus)) {
                    SettingsDialogActionButton(text = stringResource(R.string.ms_add_cert_cancel), onClick = controller::declineCertificate)
                }
                SettingsDialogActionButton(text = stringResource(R.string.ms_add_cert_trust), onClick = controller::trustCertificate, primary = true)
            }
        }
    }
}

@Composable
private fun StepColumn(
    title: String,
    subtitle: String?,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(title = title, subtitle = subtitle.orEmpty())
        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

@Composable
private fun errorText(error: AddError?, state: AddServerState): String? = when (error) {
    null -> state.typeCorrectedFrom?.let { stringResource(R.string.ms_add_type_corrected, it.productName, state.selectedType?.productName.orEmpty()) }
    AddError.INVALID_ADDRESS -> stringResource(R.string.ms_add_error_invalid_address)
    AddError.NOT_A_MEDIA_SERVER -> stringResource(R.string.ms_add_error_not_a_server)
    AddError.UNREACHABLE -> stringResource(R.string.ms_add_error_unreachable)
    AddError.WRONG_CREDENTIALS -> stringResource(R.string.ms_add_error_wrong_credentials)
    AddError.QUICK_CONNECT_FAILED -> stringResource(R.string.ms_add_error_quick_connect)
    AddError.SIGN_IN_FAILED -> stringResource(R.string.ms_add_error_sign_in_failed)
    AddError.DIFFERENT_SERVER -> stringResource(R.string.ms_add_error_different_server)
    AddError.NOT_SAVED -> stringResource(R.string.ms_add_error_not_saved)
    AddError.UNUSABLE_SERVER -> stringResource(R.string.ms_add_error_unusable)
}

@Composable
private fun StatusLine(text: String?, isError: Boolean) {
    if (text == null) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
        modifier = Modifier.fillMaxWidth().padding(top = NuvioTheme.spacing.sm),
    )
}

@Composable
private fun AddressStep(
    state: AddServerState,
    onAddress: (String) -> Unit,
    onType: (MediaServerType?) -> Unit,
    onConnect: () -> Unit,
    signingInExisting: Boolean,
) {
    val addressFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { addressFocus.requestFocusAfterFrames(frames = 2) }
    StepColumn(
        title = if (signingInExisting) stringResource(R.string.ms_sign_in_to_title, state.address.ifBlank { "" }).trim() else stringResource(R.string.ms_add_title),
        subtitle = stringResource(R.string.ms_add_subtitle),
    ) {
        if (!signingInExisting) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = NuvioTheme.spacing.xs)) {
                SettingsChoiceChip(stringResource(R.string.ms_add_type_auto), state.selectedType == null, { onType(null) })
                SettingsChoiceChip(stringResource(R.string.ms_add_type_jellyfin), state.selectedType == MediaServerType.JELLYFIN, { onType(MediaServerType.JELLYFIN) })
                SettingsChoiceChip(stringResource(R.string.ms_add_type_emby), state.selectedType == MediaServerType.EMBY, { onType(MediaServerType.EMBY) })
            }
        }
        MediaServerTextField(
            label = stringResource(R.string.ms_add_address_label),
            value = state.address,
            onValueChange = onAddress,
            hint = stringResource(R.string.ms_add_address_hint),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            focusRequester = addressFocus,
            onImeAction = onConnect,
        )
        SettingsActionRow(
            title = if (state.busy) stringResource(R.string.ms_add_checking) else stringResource(R.string.ms_add_connect),
            subtitle = null,
            enabled = !state.busy,
            onClick = onConnect,
            modifier = Modifier.padding(top = NuvioTheme.spacing.md),
        )
        StatusLine(errorText(state.error, state), state.error != null)
    }
}

@Composable
private fun ChooseStep(
    state: AddServerState,
    onQuickConnect: () -> Unit,
    onPassword: () -> Unit,
    onChangeAddress: () -> Unit,
    signingInExisting: Boolean,
) {
    val found = state.found
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusAfterFrames(frames = 2) }
    StepColumn(
        title = stringResource(R.string.ms_add_choose_title),
        subtitle = found?.let { stringResource(R.string.ms_add_connected, it.info.name, found.info.version?.let { v -> "${found.type.productName} $v" } ?: found.type.productName) },
    ) {
        val listState = rememberLazyListState()
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.quickConnectAvailable) {
                item(key = "qc") {
                    SettingsActionRow(
                        title = stringResource(R.string.ms_add_quick_connect_row),
                        subtitle = stringResource(R.string.ms_add_quick_connect_row_description),
                        leadingIcon = Icons.Default.PhoneAndroid,
                        onClick = onQuickConnect,
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                }
            }
            item(key = "pw") {
                SettingsActionRow(
                    title = stringResource(R.string.ms_add_password_row),
                    subtitle = stringResource(R.string.ms_add_password_row_description),
                    leadingIcon = Icons.Default.Key,
                    onClick = onPassword,
                    modifier = if (state.quickConnectAvailable) Modifier else Modifier.focusRequester(firstFocus),
                )
            }
            if (!signingInExisting) {
                item(key = "addr") {
                    SettingsActionRow(
                        title = stringResource(R.string.ms_add_change_address),
                        subtitle = null,
                        onClick = onChangeAddress,
                    )
                }
            }
        }
        StatusLine(errorText(state.error, state), state.error != null)
    }
}

@Composable
private fun QuickConnectStep(
    state: AddServerState,
    onUsePassword: () -> Unit,
    onBack: () -> Unit,
) {
    val view = state.quickConnect
    val serverName = state.found?.info?.name.orEmpty()
    // A one-second tick for the countdown only (display, no network).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(view?.startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusAfterFrames(frames = 3) }
    StepColumn(title = stringResource(R.string.ms_add_qc_title), subtitle = stringResource(R.string.ms_add_qc_steps, serverName)) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTheme.spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = view?.displayCode ?: stringResource(R.string.ms_add_qc_getting_code),
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 8.sp),
                color = NuvioTheme.colors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (view == null) "" else stringResource(
                    R.string.ms_add_qc_expires,
                    QuickConnectPolicy.countdownLabel(QuickConnectPolicy.remainingMs(view.startedAtMs, now)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(top = NuvioTheme.spacing.sm),
            )
            Text(
                text = stringResource(R.string.ms_add_qc_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs),
            )
        }
        SettingsActionRow(
            title = stringResource(R.string.ms_add_use_password),
            subtitle = null,
            leadingIcon = Icons.Default.Key,
            onClick = onUsePassword,
            modifier = Modifier.focusRequester(firstFocus),
        )
        SettingsActionRow(title = stringResource(R.string.ms_add_back), subtitle = null, onClick = onBack)
        StatusLine(errorText(state.error, state), state.error != null)
    }
}

@Composable
private fun PasswordStep(
    state: AddServerState,
    onSubmit: (username: String, password: String) -> Unit,
    onBack: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { (if (state.publicUsers.isEmpty()) usernameFocus else passwordFocus).requestFocusAfterFrames(frames = 2) }
    StepColumn(title = stringResource(R.string.ms_add_password_row), subtitle = stringResource(R.string.ms_add_password_note)) {
        val listState = rememberLazyListState()
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.publicUsers.isNotEmpty()) {
                // The server's own user list (GET /Users/Public): picking a name beats typing one with a remote.
                item(key = "users-label") {
                    Text(stringResource(R.string.ms_add_users_label), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary)
                }
                items(state.publicUsers.chunked(4), key = { row -> row.first().id }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { user ->
                            SettingsChoiceChip(user.name, username == user.name, {
                                username = user.name
                                passwordFocus.requestFocusSafe()
                            })
                        }
                    }
                }
            }
            item(key = "username") {
                MediaServerTextField(
                    label = stringResource(R.string.ms_add_username_label),
                    value = username,
                    onValueChange = { username = it },
                    imeAction = ImeAction.Next,
                    focusRequester = usernameFocus,
                    onImeAction = { passwordFocus.requestFocusSafe() },
                )
            }
            item(key = "password") {
                MediaServerTextField(
                    label = stringResource(R.string.ms_add_password_label),
                    value = password,
                    onValueChange = { password = it },
                    isPassword = true,
                    imeAction = ImeAction.Done,
                    focusRequester = passwordFocus,
                    onImeAction = { onSubmit(username, password) },
                )
            }
            item(key = "submit") {
                SettingsActionRow(
                    title = if (state.busy) stringResource(R.string.ms_add_signing_in) else stringResource(R.string.ms_add_sign_in),
                    subtitle = null,
                    enabled = !state.busy,
                    onClick = { onSubmit(username, password) },
                    modifier = Modifier.padding(top = NuvioTheme.spacing.md),
                )
            }
            item(key = "back") {
                SettingsActionRow(title = stringResource(R.string.ms_add_back), subtitle = null, onClick = onBack)
            }
            item(key = "error") { StatusLine(errorText(state.error, state), state.error != null) }
        }
    }
}

private fun FocusRequester.requestFocusSafe() { runCatching { requestFocus() } }

@Composable
private fun NameStep(entry: MediaServerEntry, onDone: (name: String, recentlyAdded: Boolean) -> Unit) {
    var name by remember(entry.key) { mutableStateOf(entry.name) }
    var recent by remember { mutableStateOf(true) }
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { nameFocus.requestFocusAfterFrames(frames = 2) }
    StepColumn(title = stringResource(R.string.ms_done_title), subtitle = stringResource(R.string.ms_done_subtitle)) {
        MediaServerTextField(
            label = stringResource(R.string.ms_done_name_label),
            value = name,
            onValueChange = { name = it },
            focusRequester = nameFocus,
            onImeAction = { onDone(name, recent) },
        )
        SettingsToggleRow(
            title = stringResource(R.string.ms_done_recent),
            subtitle = stringResource(R.string.ms_done_recent_hint),
            checked = recent,
            onToggle = { recent = !recent },
            modifier = Modifier.padding(top = NuvioTheme.spacing.md),
        )
        SettingsActionRow(
            title = stringResource(R.string.ms_done_button),
            subtitle = null,
            onClick = { onDone(name, recent) },
            modifier = Modifier.padding(top = NuvioTheme.spacing.md),
        )
    }
}
