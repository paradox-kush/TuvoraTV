package com.nuvio.tv.updater.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import com.nuvio.tv.updater.ImmersivePlaybackGate
import com.nuvio.tv.updater.UpdateBannerVisibilityPolicy
import com.nuvio.tv.updater.UpdateViewModel

/**
 * The banner sits above the app in a Column. The update state is read in [UpdateBannerSection] only, so a download
 * progress tick recomposes the banner and nothing else: [content] is not re-run when the state changes (B274).
 */
@Composable
fun UpdateBannerHost(
    viewModel: UpdateViewModel,
    playerRouteActive: Boolean,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        UpdateBannerSection(viewModel = viewModel, playerRouteActive = playerRouteActive)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            content()
        }
    }
}

@Composable
private fun UpdateBannerSection(
    viewModel: UpdateViewModel,
    playerRouteActive: Boolean
) {
    val rawState by viewModel.uiState.collectAsState()
    val state = if (playerRouteActive && rawState.showBanner) rawState.copy(showBanner = false) else rawState
    val onDismissBanner = viewModel::dismissBanner
    val onDownload = viewModel::downloadUpdate
    val onInstall = viewModel::installUpdateOrRequestPermission
    val onDismissUnknownSources = viewModel::dismissUnknownSourcesDialog
    val onOpenUnknownSources = viewModel::openUnknownSourcesSettings
    val onFeedbackShown = viewModel::consumeFeedbackMessage
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showReleaseNotes by remember { mutableStateOf(false) }

    LaunchedEffect(state.update?.tag) {
        showReleaseNotes = false
    }

    LaunchedEffect(state.feedbackMessage) {
        val message = state.feedbackMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        onFeedbackShown()
    }

    DisposableEffect(lifecycleOwner, state.showUnknownSourcesDialog) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && state.showUnknownSourcesDialog) {
                onInstall()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A newer release takes the banner; otherwise it can report the release that was just installed automatically.
    val update = state.update ?: state.installedUpdate
    // The banner is a Column sibling of the whole app (below), so it SHRINKS the content rather
    // than floating over it — over the player that letterboxes the picture. Ask the policy.
    val playerOnScreen by ImmersivePlaybackGate.isActive.collectAsState()
    val showBanner = UpdateBannerVisibilityPolicy.mayOccupyLayout(
        hasUpdate = update != null,
        bannerRequested = state.showBanner,
        playerOnScreen = playerOnScreen
    )

    AnimatedVisibility(
        visible = showBanner,
        enter = expandVertically(
            expandFrom = Alignment.Top,
            animationSpec = tween(durationMillis = 300)
        ) + fadeIn(animationSpec = tween(durationMillis = 180)),
        exit = shrinkVertically(
            shrinkTowards = Alignment.Top,
            animationSpec = tween(durationMillis = 240)
        ) + fadeOut(animationSpec = tween(durationMillis = 150))
    ) {
        update?.let {
            UpdateBanner(
                state = state,
                update = it,
                isInstalledUpdate = state.update == null,
                autoDismissPaused = showReleaseNotes,
                onDownload = onDownload,
                onInstall = onInstall,
                onShowReleaseNotes = { showReleaseNotes = true },
                onDismiss = onDismissBanner
            )
        }
    }

    if (showReleaseNotes && update != null) {
        UpdateReleaseNotesDialog(
            update = update,
            onDismiss = { showReleaseNotes = false }
        )
    }

    if (state.showUnknownSourcesDialog) {
        UpdateUnknownSourcesDialog(
            onOpenSettings = onOpenUnknownSources,
            onDismiss = onDismissUnknownSources
        )
    }
}
