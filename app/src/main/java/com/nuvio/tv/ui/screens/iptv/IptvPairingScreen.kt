@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * P5 "Add from phone" screen. A not-signed-in TV shows a QR + a short code; the user opens the web
 * form on their phone, enters a playlist, and it lands here. Mirrors the tv-login QR screens' layout
 * (QR block + code + expiry) but built entirely from the design system (NuvioTheme + tv.material3),
 * and its terminal action is saving the received playlist rather than signing in.
 */
@Composable
fun IptvPairingScreen(
    onBackPress: () -> Unit = {},
    onPaired: () -> Unit = {},
    viewModel: IptvPairingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler { onBackPress() }

    // Live countdown for the expiry line (recomputed each second against the session expiry).
    val nowMillis by produceState(initialValue = System.currentTimeMillis(), uiState.code) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val remainingMillis = uiState.expiresAtMillis?.let { (it - nowMillis).coerceAtLeast(0L) } ?: 0L

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth(0.7f)
                .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(20.dp))
                .padding(NuvioTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (uiState.status) {
                IptvPairingStatus.SUCCESS -> PairingSuccess(
                    accountName = uiState.savedAccountName,
                    onDone = onPaired
                )
                IptvPairingStatus.EXPIRED -> PairingTerminalMessage(
                    title = stringResource(R.string.iptv_pairing_expired_title),
                    message = stringResource(R.string.iptv_pairing_expired_message),
                    primaryLabel = stringResource(R.string.iptv_pairing_retry),
                    onPrimary = viewModel::startPairing,
                    onBack = onBackPress
                )
                IptvPairingStatus.ERROR -> PairingTerminalMessage(
                    title = stringResource(R.string.iptv_pairing_error_title),
                    message = uiState.errorMessage
                        ?: stringResource(R.string.iptv_pairing_expired_message),
                    primaryLabel = stringResource(R.string.iptv_pairing_retry),
                    onPrimary = viewModel::startPairing,
                    onBack = onBackPress
                )
                else -> PairingActive(
                    uiState = uiState,
                    remainingMillis = remainingMillis,
                    onBack = onBackPress
                )
            }
        }
    }
}

@Composable
private fun PairingActive(
    uiState: IptvPairingUiState,
    remainingMillis: Long,
    onBack: () -> Unit
) {
    val backFocus = remember { FocusRequester() }
    Text(
        text = stringResource(R.string.iptv_pairing_title),
        style = MaterialTheme.typography.headlineSmall,
        color = NuvioTheme.colors.TextPrimary,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(NuvioTheme.spacing.sm))
    Text(
        text = stringResource(R.string.iptv_pairing_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 520.dp)
    )
    Spacer(Modifier.height(NuvioTheme.spacing.xl))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxl)
    ) {
        PairingQrBlock(uiState = uiState)
        PairingCodeColumn(uiState = uiState, remainingMillis = remainingMillis)
    }

    Spacer(Modifier.height(NuvioTheme.spacing.xl))
    Button(
        onClick = onBack,
        modifier = Modifier.focusRequester(backFocus),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.Primary,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.OnPrimary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(50))
    ) {
        Text(stringResource(R.string.iptv_pairing_back), modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md))
    }
}

@Composable
private fun PairingQrBlock(uiState: IptvPairingUiState) {
    val qr = uiState.qrBitmap
    if (qr != null) {
        Image(
            bitmap = qr.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_iptv_pairing_qr),
            modifier = Modifier
                .size(220.dp)
                .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp))
                .padding(10.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = Modifier
                .size(220.dp)
                .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(12.dp))
                .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (uiState.status == IptvPairingStatus.LOADING)
                    stringResource(R.string.iptv_pairing_generating)
                else stringResource(R.string.iptv_pairing_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PairingCodeColumn(uiState: IptvPairingUiState, remainingMillis: Long) {
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.widthIn(max = 320.dp)
    ) {
        if (uiState.status == IptvPairingStatus.LOADING || uiState.code == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = NuvioTheme.colors.Primary
                )
                Spacer(Modifier.width(NuvioTheme.spacing.sm))
                Text(
                    text = stringResource(R.string.iptv_pairing_preparing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            return
        }

        Text(
            text = stringResource(R.string.iptv_pairing_code_label),
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(Modifier.height(NuvioTheme.spacing.xs))
        Box(
            modifier = Modifier
                .background(NuvioTheme.colors.Primary.copy(alpha = 0.12f), RoundedCornerShape(NuvioTheme.radii.md))
                .border(1.dp, NuvioTheme.colors.Primary.copy(alpha = 0.5f), RoundedCornerShape(NuvioTheme.radii.md))
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(
                text = uiState.code,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp
                ),
                color = NuvioTheme.colors.Primary,
                maxLines = 1
            )
        }

        uiState.webUrl?.let { url ->
            Spacer(Modifier.height(NuvioTheme.spacing.lg))
            Text(
                text = stringResource(R.string.iptv_pairing_open_url),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            Spacer(Modifier.height(NuvioTheme.spacing.xxs))
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextPrimary
            )
        }

        Spacer(Modifier.height(NuvioTheme.spacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = NuvioTheme.colors.TextTertiary
            )
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            Text(
                text = if (uiState.status == IptvPairingStatus.SAVING)
                    stringResource(R.string.iptv_pairing_saving)
                else stringResource(R.string.iptv_pairing_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        if (uiState.expiresAtMillis != null && uiState.status != IptvPairingStatus.SAVING) {
            Spacer(Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = stringResource(R.string.iptv_pairing_expires, formatDuration(remainingMillis)),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary
            )
        }
    }
}

@Composable
private fun PairingSuccess(accountName: String?, onDone: () -> Unit) {
    val doneFocus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { doneFocus.requestFocus() } }
    Text(
        text = stringResource(R.string.iptv_pairing_success_title),
        style = MaterialTheme.typography.headlineSmall,
        color = NuvioTheme.colors.TextPrimary,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(NuvioTheme.spacing.sm))
    Text(
        text = accountName?.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.iptv_pairing_success_message, it) }
            ?: stringResource(R.string.iptv_pairing_success_generic),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 480.dp)
    )
    Spacer(Modifier.height(NuvioTheme.spacing.xl))
    Button(
        onClick = onDone,
        modifier = Modifier.focusRequester(doneFocus),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.Primary,
            focusedContainerColor = NuvioTheme.colors.PrimaryVariant,
            contentColor = NuvioTheme.colors.OnPrimary,
            focusedContentColor = NuvioTheme.colors.OnPrimary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(50))
    ) {
        Text(stringResource(R.string.iptv_pairing_done), modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md))
    }
}

@Composable
private fun PairingTerminalMessage(
    title: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onBack: () -> Unit
) {
    val primaryFocus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { primaryFocus.requestFocus() } }
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        color = NuvioTheme.colors.TextPrimary,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(NuvioTheme.spacing.sm))
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 480.dp)
    )
    Spacer(Modifier.height(NuvioTheme.spacing.xl))
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        Button(
            onClick = onPrimary,
            modifier = Modifier.focusRequester(primaryFocus),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.Primary,
                focusedContainerColor = NuvioTheme.colors.PrimaryVariant,
                contentColor = NuvioTheme.colors.OnPrimary,
                focusedContentColor = NuvioTheme.colors.OnPrimary
            ),
            shape = ButtonDefaults.shape(RoundedCornerShape(50))
        ) {
            Text(primaryLabel, modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md))
        }
        Button(
            onClick = onBack,
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.Primary,
                contentColor = NuvioTheme.colors.TextPrimary,
                focusedContentColor = NuvioTheme.colors.OnPrimary
            ),
            shape = ButtonDefaults.shape(RoundedCornerShape(50))
        ) {
            Text(stringResource(R.string.iptv_pairing_back), modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md))
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0L)
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
