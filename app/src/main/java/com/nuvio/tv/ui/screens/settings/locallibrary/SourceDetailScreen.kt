@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioColors
import java.text.DateFormat
import java.util.Date

@Composable
fun SourceDetailScreen(
    sourceId: String,
    onBackPress: () -> Unit,
    onNavigateToManualMatch: (sourceId: String) -> Unit,
    viewModel: LocalLibrarySettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val config = state.sources.firstOrNull { it.id == sourceId }
    if (config == null) {
        SettingsStandaloneScaffold(title = "Source", subtitle = "") {
            Text("Source not found.", color = NuvioColors.TextSecondary)
        }
        return
    }

    val progress = state.progress[sourceId]
    val lastScan = config.lastScanAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
        ?: "Never"

    SettingsStandaloneScaffold(
        title = config.displayName,
        subtitle = viewModel.kindLabel(config.kind)
    ) {
        SettingsDetailHeader(
            title = "Status",
            subtitle = "Last scan: $lastScan · ${config.itemCount} items indexed"
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsToggleRow(
                title = "Enabled",
                subtitle = "Show this source's content in catalogs and search.",
                checked = config.enabled,
                onToggle = { viewModel.setEnabled(sourceId, !config.enabled) }
            )
        }

        if (progress != null) {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatProgress(progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { viewModel.rescan(sourceId) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.FocusRing,
                    contentColor = Color.Black,
                    focusedContainerColor = NuvioColors.FocusRing,
                    focusedContentColor = Color.Black
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.TextPrimary),
                        shape = RoundedCornerShape(50)
                    )
                )
            ) { Text("Rescan now", color = Color.Black) }
            Button(
                onClick = { onNavigateToManualMatch(sourceId) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.Background,
                    contentColor = NuvioColors.TextPrimary,
                    focusedContainerColor = NuvioColors.Background,
                    focusedContentColor = NuvioColors.TextPrimary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(50)
                    )
                )
            ) { Text("Manual match", color = NuvioColors.TextPrimary) }
            Button(
                onClick = {
                    viewModel.removeSource(sourceId)
                    onBackPress()
                },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.Background,
                    contentColor = Color(0xFFFF6B6B),
                    focusedContainerColor = NuvioColors.Background,
                    focusedContentColor = Color(0xFFFF6B6B)
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(50)
                    )
                )
            ) { Text("Remove source", color = Color(0xFFFF6B6B)) }
        }
    }
}

private fun formatProgress(progress: com.nuvio.tv.data.locallibrary.LocalLibraryManager.ScanProgress): String =
    when (progress) {
        is com.nuvio.tv.data.locallibrary.LocalLibraryManager.ScanProgress.Idle ->
            "Idle (${progress.itemCount} items)"
        is com.nuvio.tv.data.locallibrary.LocalLibraryManager.ScanProgress.Scanning ->
            "Scanning… ${progress.itemsFound} found so far"
        is com.nuvio.tv.data.locallibrary.LocalLibraryManager.ScanProgress.Matching ->
            "Matching ${progress.matched}/${progress.total}"
        is com.nuvio.tv.data.locallibrary.LocalLibraryManager.ScanProgress.Failed ->
            "Failed: ${progress.reason}"
    }
