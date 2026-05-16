@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.locallibrary.LocalLibraryManager
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun LocalLibrarySettingsScreen(
    onBackPress: () -> Unit,
    onNavigateToAddSource: () -> Unit,
    onNavigateToSourceDetail: (sourceId: String) -> Unit,
    viewModel: LocalLibrarySettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsStandaloneScaffold(
        title = "Local sources",
        subtitle = "Connect Jellyfin servers, SMB shares, or on-device folders."
    ) {
        SettingsDetailHeader(
            title = "Sources",
            subtitle = "Each source is scanned and matched to TMDB so it appears alongside addon content."
        )

        val anyScanning = state.progress.values.any {
            it is LocalLibraryManager.ScanProgress.Scanning ||
                it is LocalLibraryManager.ScanProgress.Matching
        }

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            SettingsActionRow(
                title = "Add source…",
                subtitle = "Jellyfin / SMB share / On-device folder",
                value = null,
                onClick = onNavigateToAddSource
            )
            SettingsActionRow(
                title = "Rescan all sources",
                subtitle = if (anyScanning) "Scanning in progress…"
                    else "Re-index every configured source",
                value = null,
                enabled = state.sources.isNotEmpty() && !anyScanning,
                onClick = { viewModel.rescanAllSources() }
            )
        }

        if (state.sources.isEmpty()) {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "No sources yet. Add one to start scanning.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
            }
        } else {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.sources, key = { it.id }) { source ->
                            SourceRow(
                                config = source,
                                progress = state.progress[source.id],
                                kindLabel = viewModel.kindLabel(source.kind),
                                onClick = { onNavigateToSourceDetail(source.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    config: LocalLibrarySourceConfig,
    progress: LocalLibraryManager.ScanProgress?,
    kindLabel: String,
    onClick: () -> Unit
) {
    val statusText = when (progress) {
        is LocalLibraryManager.ScanProgress.Scanning -> "Scanning… ${progress.itemsFound} found"
        is LocalLibraryManager.ScanProgress.Matching -> "Matching ${progress.matched}/${progress.total}"
        is LocalLibraryManager.ScanProgress.Failed -> "Failed: ${progress.reason}"
        is LocalLibraryManager.ScanProgress.Idle -> "${progress.itemCount} items"
        null -> "${config.itemCount} items"
    }
    val disabledHint = if (!config.enabled) " · Disabled" else ""

    SettingsActionRow(
        title = config.displayName,
        subtitle = "$kindLabel · $statusText$disabledHint",
        value = null,
        onClick = onClick
    )
}
