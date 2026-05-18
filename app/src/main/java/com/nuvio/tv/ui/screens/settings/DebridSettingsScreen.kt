@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.addon.QrCodeOverlay
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun DebridSettingsContent(
    viewModel: DebridSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var activeApiKeyDialog by remember { mutableStateOf<DebridApiKeyDialogProvider?>(null) }
    var showPrepareCountDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(uiState.serverError) {
        val error = uiState.serverError ?: return@LaunchedEffect
        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.debrid_title),
            subtitle = stringResource(R.string.debrid_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val state = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "debrid_notice") {
                        DebridInfoText(text = stringResource(R.string.debrid_experimental_notice))
                    }

                    item(key = "debrid_enabled") {
                        SettingsToggleRow(
                            title = stringResource(R.string.debrid_enable_title),
                            subtitle = stringResource(R.string.debrid_enable_subtitle),
                            checked = uiState.enabled && uiState.hasAnyApiKey,
                            onToggle = { viewModel.onEvent(DebridSettingsEvent.ToggleEnabled(!uiState.enabled)) },
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .then(
                                    if (initialFocusRequester != null) {
                                        Modifier.focusRequester(initialFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                ),
                            enabled = uiState.hasAnyApiKey
                        )
                    }

                    if (!uiState.hasAnyApiKey) {
                        item(key = "debrid_add_key_first") {
                            DebridInfoText(text = stringResource(R.string.debrid_add_key_first))
                        }
                    }

                    item(key = "debrid_account_section") {
                        DebridSectionLabel(text = stringResource(R.string.debrid_section_account))
                    }

                    item(key = "debrid_torbox_api_key") {
                        SettingsActionRow(
                            title = stringResource(R.string.debrid_api_key_title),
                            subtitle = stringResource(R.string.debrid_api_key_subtitle),
                            value = maskDebridApiKey(uiState.torboxApiKey, stringResource(R.string.debrid_not_set)),
                            onClick = { activeApiKeyDialog = DebridApiKeyDialogProvider.TORBOX },
                            enabled = true
                        )
                    }

                    item(key = "debrid_instant_section") {
                        DebridSectionLabel(text = stringResource(R.string.debrid_section_instant_playback))
                    }

                    item(key = "debrid_prepare_links") {
                        val prepareEnabled = uiState.enabled && uiState.instantPlaybackPreparationLimit > 0
                        SettingsToggleRow(
                            title = stringResource(R.string.debrid_prepare_instant_playback),
                            subtitle = stringResource(R.string.debrid_prepare_instant_playback_description),
                            checked = prepareEnabled,
                            onToggle = { viewModel.setInstantPlaybackPreparationEnabled(!prepareEnabled) },
                            enabled = uiState.enabled && uiState.hasAnyApiKey
                        )
                    }

                    if (uiState.enabled && uiState.instantPlaybackPreparationLimit > 0) {
                        item(key = "debrid_prepare_count") {
                            SettingsActionRow(
                                title = stringResource(R.string.debrid_prepare_stream_count),
                                subtitle = null,
                                value = prepareCountLabel(uiState.instantPlaybackPreparationLimit),
                                onClick = { showPrepareCountDialog = true },
                                enabled = true
                            )
                        }
                    }

                    item(key = "debrid_formatting_section") {
                        DebridSectionLabel(text = stringResource(R.string.debrid_section_formatting))
                    }

                    item(key = "debrid_formatter") {
                        SettingsActionRow(
                            title = stringResource(R.string.debrid_formatter_title),
                            subtitle = stringResource(R.string.debrid_formatter_subtitle),
                            value = stringResource(R.string.debrid_formatter_configure),
                            onClick = { viewModel.startFormatterQrMode() },
                            enabled = uiState.enabled
                        )
                    }

                    item(key = "debrid_formatter_reset") {
                        SettingsActionRow(
                            title = stringResource(R.string.debrid_formatter_reset_title),
                            subtitle = stringResource(R.string.debrid_formatter_reset_subtitle),
                            value = stringResource(R.string.layout_reset_default),
                            onClick = { viewModel.resetFormatterTemplates() },
                            enabled = uiState.enabled
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = state)
            }
        }
    }

    activeApiKeyDialog?.let { provider ->
        when (provider) {
            DebridApiKeyDialogProvider.TORBOX -> DebridApiKeyDialog(
                title = stringResource(R.string.debrid_dialog_title),
                subtitle = stringResource(R.string.debrid_dialog_subtitle),
                placeholder = stringResource(R.string.debrid_dialog_placeholder),
                currentValue = uiState.torboxApiKey,
                viewModel = viewModel,
                onSave = { value, onSaved -> viewModel.validateAndSaveTorboxApiKey(value, onSaved) },
                onSaved = { activeApiKeyDialog = null },
                onClear = {
                    viewModel.validateAndSaveTorboxApiKey("") {}
                    activeApiKeyDialog = null
                },
                onDismiss = { activeApiKeyDialog = null }
            )
        }
    }

    if (showPrepareCountDialog) {
        DebridPrepareCountDialog(
            selectedLimit = uiState.instantPlaybackPreparationLimit,
            onLimitSelected = { limit ->
                viewModel.setInstantPlaybackPreparationLimit(limit)
                showPrepareCountDialog = false
            },
            onDismiss = { showPrepareCountDialog = false }
        )
    }

    if (uiState.isFormatterQrModeActive) {
        QrCodeOverlay(
            qrBitmap = uiState.formatterQrCodeBitmap,
            serverUrl = uiState.formatterServerUrl,
            instruction = stringResource(R.string.debrid_formatter_qr_instruction),
            onClose = { viewModel.stopFormatterQrMode() }
        )
    }
}

@Composable
private fun DebridInfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = NuvioColors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
    )
}

@Composable
private fun DebridSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = NuvioColors.TextPrimary,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp)
    )
}

@Composable
private fun prepareCountLabel(limit: Int): String {
    return if (limit == 1) {
        stringResource(R.string.debrid_prepare_count_one)
    } else {
        stringResource(R.string.debrid_prepare_count_many, limit)
    }
}

@Composable
private fun DebridPrepareCountDialog(
    selectedLimit: Int,
    onLimitSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(1, 2, 3, 5)

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.debrid_prepare_stream_count),
        options = options.map { limit ->
            SettingsPickerOption(limit, prepareCountLabel(limit))
        },
        selectedValue = selectedLimit,
        onOptionSelected = onLimitSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 280.dp
    )
}

@Composable
private fun DebridApiKeyDialog(
    title: String,
    subtitle: String,
    placeholder: String,
    currentValue: String,
    viewModel: DebridSettingsViewModel,
    onSave: (String, () -> Unit) -> Unit,
    onSaved: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val validating by viewModel.validating.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val submit = {
        if (!validating) {
            focusManager.clearFocus()
            keyboardController?.hide()
            onSave(value, onSaved)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.validationError.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = 700.dp,
        suppressFirstKeyUp = false
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundElevated,
                focusedContainerColor = NuvioColors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, NuvioColors.Border),
                    shape = RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            when {
                                native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                    native.action == KeyEvent.ACTION_DOWN -> true
                                (native.keyCode == KeyEvent.KEYCODE_ENTER ||
                                    native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                                    native.action == KeyEvent.ACTION_DOWN -> {
                                    submit()
                                    true
                                }
                                else -> false
                            }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { submit() }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioColors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NuvioColors.Primary else Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioColors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundElevated,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundElevated,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_clear))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { submit() },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(if (validating) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
            }
        }
    }
}

private fun maskDebridApiKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "****" else "******${trimmed.takeLast(4)}"
}

private enum class DebridApiKeyDialogProvider {
    TORBOX
}
