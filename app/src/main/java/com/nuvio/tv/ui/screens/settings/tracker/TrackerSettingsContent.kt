@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.tracker

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.domain.model.TrackerListStatus
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders a single anime-tracker subscreen (MyAnimeList / AniList / Kitsu).
 *
 * Kept deliberately composable-over-the-state: the three per-tracker
 * ViewModels produce identical [TrackerSettingsUiState] shapes so this file
 * is the only place the UI lives. Subscreen-specific wiring (which auth
 * service, which settings store) is confined to the ViewModels.
 *
 * Event callbacks are passed via [TrackerSettingsCallbacks] so every button
 * goes through the VM — no direct service access from Compose.
 */
data class TrackerSettingsCallbacks(
    val onConnect: () -> Unit,
    val onCancelConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onSendProgressToggled: (Boolean) -> Unit,
    val onStatusToggled: (TrackerListStatus, Boolean) -> Unit,
    val onDismissMessage: () -> Unit,
    /**
     * Opens a tracker-specific "local debug auth" dialog. Rendered only in
     * debug builds; wired by each [*SettingsContent] wrapper — no-op default
     * so release builds can reuse the same callbacks data class.
     */
    val onDebugConnect: () -> Unit = {}
)

@Composable
internal fun TrackerSettingsContent(
    state: TrackerSettingsUiState,
    callbacks: TrackerSettingsCallbacks,
    initialFocusRequester: FocusRequester? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = state.serviceName,
            subtitle = "Sign in to render your lists as catalog rows and sync episode progress."
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "connection_row") {
                    ConnectionRow(
                        state = state,
                        callbacks = callbacks,
                        focusRequester = initialFocusRequester
                    )
                }

                // Debug builds get a local-only auth path that skips the
                // Supabase phone-pair flow. Never rendered in release.
                if (BuildConfig.IS_DEBUG_BUILD && !state.isConnected) {
                    item(key = "debug_connect_row") {
                        SettingsActionRow(
                            title = "Debug: sign in locally",
                            subtitle = "Bypass phone-pair (skip QR flow) — debug builds only",
                            value = "Open",
                            onClick = callbacks.onDebugConnect
                        )
                    }
                }

                if (state.isConnected) {
                    item(key = "send_progress") {
                        SettingsToggleRow(
                            title = "Send watch progress",
                            subtitle = "Update your ${state.serviceName} list when you watch an episode",
                            checked = state.sendProgressEnabled,
                            onToggle = { callbacks.onSendProgressToggled(!state.sendProgressEnabled) }
                        )
                    }

                    item(key = "catalog_header") {
                        Text(
                            text = "Home catalogs",
                            style = MaterialTheme.typography.titleSmall,
                            color = NuvioColors.TextPrimary,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                        )
                    }

                    items(state.availableStatuses, key = { "status_${it.name}" }) { status ->
                        SettingsToggleRow(
                            title = status.displayName,
                            subtitle = "Show \"${status.displayName}\" as a row on Home",
                            checked = status in state.enabledStatuses,
                            onToggle = {
                                callbacks.onStatusToggled(status, status !in state.enabledStatuses)
                            }
                        )
                    }
                }
            }
        }

        state.errorMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.Error
            )
        }
        state.transientMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary
            )
        }
    }

    if (state.isConnecting) {
        val challenge = (state.connection as TrackerSettingsUiState.Connection.AwaitingPhone)
        TrackerConnectQrDialog(
            serviceName = state.serviceName,
            code = challenge.code,
            webUrl = challenge.webUrl,
            onDismiss = callbacks.onCancelConnect
        )
    }
}

@Composable
private fun ConnectionRow(
    state: TrackerSettingsUiState,
    callbacks: TrackerSettingsCallbacks,
    focusRequester: FocusRequester?
) {
    when (val connection = state.connection) {
        is TrackerSettingsUiState.Connection.Connected -> {
            SettingsActionRow(
                title = "Signed in",
                subtitle = connection.username ?: state.serviceName,
                value = "Disconnect",
                onClick = callbacks.onDisconnect,
                modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            )
        }
        else -> {
            SettingsActionRow(
                title = "Connect ${state.serviceName}",
                subtitle = if (state.isConnecting) "Complete sign-in on your phone" else "Sign in with a phone QR code",
                value = if (state.isConnecting) "Cancel" else "Connect",
                onClick = if (state.isConnecting) callbacks.onCancelConnect else callbacks.onConnect,
                modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            )
        }
    }
}

@Composable
private fun TrackerConnectQrDialog(
    serviceName: String,
    code: String,
    webUrl: String,
    onDismiss: () -> Unit
) {
    val qrBitmap by produceState<Bitmap?>(initialValue = null, webUrl) {
        value = withContext(Dispatchers.Default) {
            runCatching { QrCodeGenerator.generate(webUrl, size = 512) }.getOrNull()
        }
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = "Connect $serviceName",
        subtitle = "Scan the QR with your phone or open the link and enter the code below.",
        width = 560.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NuvioColors.BackgroundElevated),
                contentAlignment = Alignment.Center
            ) {
                qrBitmap?.let {
                    Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(200.dp))
                } ?: Text("Generating QR…", color = NuvioColors.TextSecondary)
            }
            Text(
                text = code,
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = webUrl,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundElevated,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}
