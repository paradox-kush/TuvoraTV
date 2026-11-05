package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.player.DialogButton
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * The "you watched further on your server" choice (media-servers design D3), shown over the playing picture for a
 * few seconds once the first frame is up: "Continue at hh:mm?" with the jump focused (OK takes it), and the viewer
 * who ignores it simply keeps watching from where Tuvora's own record started them. Never applied silently.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ServerResumeOfferAction(
    positionLabel: String,
    onJump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.lg))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.ms_resume_from_server_message, positionLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.86f),
            textAlign = TextAlign.Center,
        )
        DialogButton(
            text = stringResource(R.string.ms_resume_from_server_action),
            onClick = onJump,
            isPrimary = true,
            modifier = Modifier.focusRequester(focusRequester),
        )
    }
}

internal const val SERVER_RESUME_OFFER_VISIBLE_MS = 10_000L
private const val SERVER_RESUME_ASK_TIMEOUT_MS = 2_000L

/**
 * Called while the player prepares (before it starts): asks the owning source for its resume position ONCE - one item
 * fetch, bounded by [SERVER_RESUME_ASK_TIMEOUT_MS] so a slow server never delays playback - and applies
 * [ServerResumePolicy]. Returns the position the player should start from instead of Tuvora's (auto-resume, nothing local),
 * or null; an offer is remembered and shown by [showPendingServerResumeOffer] once the first frame is up.
 */
internal suspend fun PlayerRuntimeController.resolveServerResume(localPositionMs: Long?, localUpdatedAtMs: Long?): Long? {
    pendingServerResumeOfferMs = null
    val decision = withTimeoutOrNull(SERVER_RESUME_ASK_TIMEOUT_MS) { askSourceForResume(localPositionMs, localUpdatedAtMs) }
        ?: ServerResumePolicy.Decision.None
    return when (decision) {
        is ServerResumePolicy.Decision.AutoResume -> decision.positionMs
        is ServerResumePolicy.Decision.Offer -> {
            pendingServerResumeOfferMs = decision.positionMs
            null
        }
        ServerResumePolicy.Decision.None -> null
    }
}

internal fun PlayerRuntimeController.showPendingServerResumeOffer() {
    val offer = pendingServerResumeOfferMs ?: return
    pendingServerResumeOfferMs = null
    // an offer next to where the viewer already is would be noise
    val here = currentPlaybackPositionMs() ?: 0L
    if (abs(offer - here) < ServerResumePolicyThresholds.MIN_DIFFERENCE_MS) return
    _uiState.update { it.copy(serverResumeOfferMs = offer) }
    serverResumeOfferJob?.cancel()
    serverResumeOfferJob = scope.launch {
        delay(SERVER_RESUME_OFFER_VISIBLE_MS)
        _uiState.update { it.copy(serverResumeOfferMs = null) }
    }
}

internal fun PlayerRuntimeController.applyServerResumeOffer() {
    val target = _uiState.value.serverResumeOfferMs ?: return
    serverResumeOfferJob?.cancel()
    _uiState.update { it.copy(serverResumeOfferMs = null) }
    seekPlaybackTo(target, androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
    updatePlaybackTimeline(currentPosition = target)
    scheduleProgressSyncAfterSeek()
}

internal fun PlayerRuntimeController.dismissServerResumeOffer() {
    serverResumeOfferJob?.cancel()
    pendingServerResumeOfferMs = null
    if (_uiState.value.serverResumeOfferMs != null) _uiState.update { it.copy(serverResumeOfferMs = null) }
}

internal object ServerResumePolicyThresholds {
    const val MIN_DIFFERENCE_MS = 30_000L
}
