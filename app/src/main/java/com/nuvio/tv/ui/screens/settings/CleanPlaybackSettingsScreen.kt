@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.playback.settings.CleanPlaybackSettingField
import com.nuvio.tv.playback.settings.CleanPlaybackSettingFieldUi
import com.nuvio.tv.playback.settings.CleanPlaybackSettingInputKind
import com.nuvio.tv.playback.settings.PlaybackPreferenceGroup
import com.nuvio.tv.playback.core.ChangeImpact
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun CleanPlaybackSettingsScreen(
    viewModel: CleanPlaybackSettingsViewModel,
    onBackPress: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CleanPlaybackSettingsScreen(
        state = state,
        onUpdate = viewModel::update,
        onReset = viewModel::reset,
        onRetry = viewModel::retry,
        onBackPress = onBackPress,
    )
}

@Composable
fun CleanPlaybackSettingsScreen(
    state: CleanPlaybackSettingsUiState,
    onUpdate: (CleanPlaybackSettingField, String) -> Unit,
    onReset: (PlaybackPreferenceGroup) -> Unit,
    onRetry: () -> Unit,
    onBackPress: () -> Unit = {},
) {
    BackHandler(onBack = onBackPress)
    SettingsStandaloneScaffold(
        title = "Clean playback settings",
        subtitle = "Requested intent and current device-effective behavior",
    ) {
        when (state) {
            CleanPlaybackSettingsUiState.Loading -> StatusMessage("Loading playback settings…")
            is CleanPlaybackSettingsUiState.Failed -> {
                Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                    StatusMessage(state.message)
                    SettingsActionRow(
                        title = "Retry",
                        subtitle = "Load the active profile again",
                        onClick = onRetry,
                    )
                }
            }
            is CleanPlaybackSettingsUiState.Content -> CleanPlaybackSettingsContent(
                state = state,
                onUpdate = onUpdate,
                onReset = onReset,
            )
        }
    }
}

@Composable
private fun CleanPlaybackSettingsContent(
    state: CleanPlaybackSettingsUiState.Content,
    onUpdate: (CleanPlaybackSettingField, String) -> Unit,
    onReset: (PlaybackPreferenceGroup) -> Unit,
) {
    val presentation = state.presentation
    val listState = rememberLazyListState()
    var editing by remember { mutableStateOf<CleanPlaybackSettingFieldUi?>(null) }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        ) {
            item(key = "clean-playback-header") {
                SettingsDetailHeader(
                    title = "Profile ${presentation.profileId}",
                    subtitle = "Revision ${presentation.revision}",
                )
            }
            if (presentation.readOnly || presentation.warnings.isNotEmpty() || state.notice != null) {
                item(key = "clean-playback-warnings") {
                    SettingsGroupCard(
                        modifier = Modifier.fillMaxWidth(),
                        title = if (presentation.readOnly) "Read-only settings" else "Settings notice",
                    ) {
                        if (presentation.readOnly) {
                            Text(
                                text = "This profile uses a newer settings schema. Values are shown but cannot be changed.",
                                color = NuvioTheme.colors.TextPrimary,
                            )
                        }
                        if (presentation.warnings.isNotEmpty()) {
                            Text(
                                text = "Decode warnings: ${presentation.warnings.joinToString { it.name }}",
                                color = NuvioTheme.colors.TextSecondary,
                            )
                        }
                        if (presentation.preservedUnknownValueCount > 0) {
                            Text(
                                text = "Preserved unknown values: ${presentation.preservedUnknownValueCount}",
                                color = NuvioTheme.colors.TextSecondary,
                            )
                        }
                        state.notice?.let { Text(it, color = NuvioTheme.colors.TextPrimary) }
                    }
                }
            }
            items(presentation.groups, key = { it.group.name }) { group ->
                SettingsGroupCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = group.title,
                    subtitle = "Changes: ${group.fields.maxImpactLabel()}",
                ) {
                    group.fields.forEach { field ->
                        SettingsActionRow(
                            title = field.title,
                            subtitle = field.explanation(),
                            value = field.requestedValue.ifBlank { "Default" },
                            onClick = { editing = field },
                            enabled = !presentation.readOnly && !state.operationInProgress && field.editable,
                        )
                    }
                    SettingsActionRow(
                        title = "Reset ${group.title}",
                        subtitle = "Restore recommended requested values for this group",
                        onClick = { onReset(group.group) },
                        enabled = !presentation.readOnly && !state.operationInProgress,
                    )
                }
            }
        }
        SettingsVerticalScrollIndicators(state = listState)
    }

    editing?.let { field ->
        CleanPlaybackSettingEditorDialog(
            field = field,
            onSave = { value ->
                onUpdate(field.key, value)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun CleanPlaybackSettingEditorDialog(
    field: CleanPlaybackSettingFieldUi,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(field.key, field.requestedValue) { mutableStateOf(field.requestedValue) }
    NuvioDialog(
        onDismiss = onDismiss,
        title = field.title,
        subtitle = "Requested: ${field.requestedValue.ifBlank { "Default" }} · Effective: ${field.effectiveValue?.ifBlank { "Default" } ?: "Unavailable"}",
    ) {
        if (field.choices.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                field.choices.forEach { choice ->
                    SettingsChoiceChip(
                        label = choice,
                        selected = choice == field.requestedValue,
                        onClick = { onSave(choice) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(NuvioTheme.radii.md))
                    .border(
                        NuvioTheme.spacing.hairline,
                        NuvioTheme.colors.Border,
                        RoundedCornerShape(NuvioTheme.radii.md),
                    )
                    .padding(NuvioTheme.spacing.md),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = NuvioTheme.colors.TextPrimary),
                cursorBrush = SolidColor(NuvioTheme.colors.FocusBackground),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field.inputKind) {
                        CleanPlaybackSettingInputKind.INTEGER -> KeyboardType.Number
                        else -> KeyboardType.Text
                    },
                ),
            )
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = "Cancel", onClick = onDismiss)
                SettingsDialogActionButton(text = "Save", onClick = { onSave(text) }, primary = true)
            }
        }
    }
}

@Composable
private fun StatusMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.xl)) {
        Text(text = message, color = NuvioTheme.colors.TextPrimary)
    }
}

private fun CleanPlaybackSettingFieldUi.explanation(): String = buildList {
    add("Effective: ${effectiveValue?.ifBlank { "Default" } ?: "Unavailable"}")
    add("Reason: ${reason.name}")
    add("Authority: ${authority.name}")
    add("Availability: ${availability.name}")
    add("Impact: ${impact.name}")
    if (contributingReasons.isNotEmpty()) add("Also: ${contributingReasons.joinToString { it.name }}")
    if (conflicts.isNotEmpty()) add("Conflicts: ${conflicts.joinToString { it.code }}")
}.joinToString(" · ")

private fun List<CleanPlaybackSettingFieldUi>.maxImpactLabel(): String = when {
    any { it.impact == ChangeImpact.RESELECT_GRAPH } -> "may reselect the playback graph"
    any { it.impact == ChangeImpact.REBUILD_CURRENT_GRAPH } -> "may rebuild the current graph"
    any { it.impact == ChangeImpact.NEXT_SESSION_ONLY } -> "next session"
    else -> "applied in place"
}
