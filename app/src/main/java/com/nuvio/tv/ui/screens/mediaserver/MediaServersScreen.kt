@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.mediaserver

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.nuvio.tv.core.mediaserver.flow.ServerRowModel
import com.nuvio.tv.core.mediaserver.flow.ServerStatus
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Settings -> Media servers: the servers on this account and this device's sign-in state for each. Rows follow the
 * settings design system (focus ring on every row, OK opens). Initial focus lands on the first server, or on "Add a
 * server" when there is none (jellyfin-androidtv forces focus to its add button on an empty list, the same call).
 */
@Composable
internal fun MediaServersScreen(
    onBack: () -> Unit,
    onAddServer: () -> Unit,
    onOpenServer: (key: String) -> Unit,
    viewModel: MediaServersViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onShown() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.ms_settings_title),
        subtitle = stringResource(R.string.ms_list_subtitle),
    ) {
        MediaServersContent(rows = rows, onAddServer = onAddServer, onOpenServer = onOpenServer)
    }
}

@Composable
private fun MediaServersContent(
    rows: List<ServerRowModel>,
    onAddServer: () -> Unit,
    onOpenServer: (key: String) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    // The first server when there is one, else the add row: whichever the requester is attached to.
    LaunchedEffect(rows.isEmpty()) { firstFocus.requestFocusAfterFrames(frames = 2) }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.ms_settings_title),
            subtitle = stringResource(R.string.ms_settings_hub_subtitle),
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (rows.isNotEmpty()) {
                        items(rows, key = { it.entry.key }) { row ->
                            val first = row == rows.first()
                            SettingsActionRow(
                                title = row.entry.name,
                                subtitle = serverSubtitle(row),
                                value = statusLabel(row),
                                leadingIcon = Icons.Default.Dns,
                                onClick = { onOpenServer(row.entry.key) },
                                modifier = if (first) Modifier.focusRequester(firstFocus) else Modifier,
                            )
                        }
                    } else {
                        item(key = "empty") {
                            Text(
                                text = stringResource(R.string.ms_empty_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item(key = "add") {
                        SettingsActionRow(
                            title = stringResource(R.string.ms_add_row_title),
                            subtitle = stringResource(R.string.ms_add_row_description),
                            leadingIcon = Icons.Default.Add,
                            onClick = onAddServer,
                            modifier = if (rows.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun serverSubtitle(row: ServerRowModel): String =
    listOfNotNull(row.entry.type.productName, row.entry.address?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/')).joinToString(" · ")

@Composable
private fun statusLabel(row: ServerRowModel): String = when {
    row.checking && row.status == ServerStatus.SIGNED_IN -> stringResource(R.string.ms_status_checking)
    else -> when (row.status) {
        ServerStatus.SIGNED_IN -> stringResource(R.string.ms_status_signed_in)
        ServerStatus.SIGN_IN_AGAIN -> stringResource(R.string.ms_status_sign_in_again)
        ServerStatus.NEEDS_SIGN_IN -> stringResource(R.string.ms_status_needs_sign_in)
        ServerStatus.OFFLINE -> stringResource(R.string.ms_status_offline)
        ServerStatus.DISABLED -> stringResource(R.string.ms_status_disabled)
    }
}
