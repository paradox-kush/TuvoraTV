package com.nuvio.tv.ui.screens.player.clean

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.PreviewAvailability
import com.nuvio.tv.playback.core.StreamAvailability
import com.nuvio.tv.playback.ui.LivePlaybackUiErrorCode
import com.nuvio.tv.playback.ui.LivePlaybackUiState
import com.nuvio.tv.playback.ui.LivePlaybackUiStatusCode
import com.nuvio.tv.ui.util.findActivity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Navigation/Compose owner for the isolated clean fullscreen destination.
 *
 * Navigation is allowed to pop only after [CleanLivePlayerViewModel.releaseBeforeExit] confirms the
 * provider, engine, surface, MediaSession, lifecycle, and output release barrier. Process-restored
 * or already-consumed tokens fail closed and leave no transport material in the back stack.
 */
@Composable
internal fun CleanLivePlayerRoute(
    routeToken: String?,
    onReleasedExit: () -> Unit,
    viewModel: CleanLivePlayerViewModel = hiltViewModel(),
) {
    val routeState by viewModel.routeState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val latestReleasedExit by rememberUpdatedState(onReleasedExit)
    val exitGate = remember { CleanLiveExitGate() }

    fun requestReleaseAndExit() {
        if (!exitGate.tryStart()) return
        scope.launch {
            withContext(NonCancellable) {
                val released = runCatching { viewModel.releaseBeforeExit() }.isSuccess
                if (released) {
                    latestReleasedExit()
                } else {
                    // Keep the destination alive so the user can retry the release barrier.
                    exitGate.resetAfterFailure()
                }
            }
        }
    }

    LaunchedEffect(routeState) {
        val rejected = routeState as? CleanLivePlayerRouteState.Rejected
        if (rejected != null && rejected.reason != CleanLivePlayerRejection.RELEASE_FAILED) {
            requestReleaseAndExit()
        }
    }
    LaunchedEffect(activity, routeToken) {
        if (activity == null || routeToken.isNullOrBlank()) requestReleaseAndExit()
    }

    val ready = routeState as? CleanLivePlayerRouteState.Ready
    val releaseFailed = (routeState as? CleanLivePlayerRouteState.Rejected)?.reason ==
        CleanLivePlayerRejection.RELEASE_FAILED
    val uiState = when {
        ready != null -> ready.presentation
        releaseFailed -> RELEASE_FAILED_UI_STATE
        else -> INITIALIZING_UI_STATE
    }

    CleanLivePlayerScreen(
        sanitizedTitle = ready?.metadata?.title ?: DEFAULT_TITLE,
        sanitizedSubtitle = ready?.metadata?.subtitle,
        sanitizedStation = ready?.metadata?.station,
        uiState = uiState,
        onSurfaceOwnerReady = surfaceReady@{ surfaceOwner ->
            val hostActivity = activity ?: return@surfaceReady
            val token = routeToken?.takeIf(String::isNotBlank) ?: return@surfaceReady
            viewModel.attachDestination(
                routeToken = token,
                activity = hostActivity,
                lifecycle = lifecycle,
                surfaceOwner = surfaceOwner,
            )
        },
        onPause = { if (!exitGate.isStarted()) scope.launch { viewModel.pause() } },
        onResume = { if (!exitGate.isStarted()) scope.launch { viewModel.resume() } },
        onRetry = {
            when {
                releaseFailed -> requestReleaseAndExit()
                !exitGate.isStarted() -> scope.launch { viewModel.retry() }
            }
        },
        // The destination is registered before live callers are switched. Zap is wired with the
        // relative identity resolver in the same atomic ingress cutover, before this route is used.
        onZapPrevious = {},
        onZapNext = {},
        onExitRequested = ::requestReleaseAndExit,
    )
}

/** Synchronous destination-local gate: back, UI exit, and rejection may race in the same frame. */
internal class CleanLiveExitGate {
    private val lock = Any()
    private var started = false

    fun tryStart(): Boolean = synchronized(lock) {
        if (started) {
            false
        } else {
            started = true
            true
        }
    }

    fun isStarted(): Boolean = synchronized(lock) { started }

    fun resetAfterFailure() = synchronized(lock) {
        started = false
    }
}

private const val DEFAULT_TITLE = "Tuvora"

private val INITIALIZING_UI_STATE = LivePlaybackUiState(
    spinnerVisible = true,
    bottomStatusCode = LivePlaybackUiStatusCode.STARTING,
    bottomErrorCode = null,
    controlsEnabled = false,
    openFullscreenEnabled = false,
    playWhenReady = true,
    isPlaying = false,
    isPaused = false,
    previewAvailability = PreviewAvailability.Unknown,
    streamAvailability = StreamAvailability.Unknown,
)

private val RELEASE_FAILED_UI_STATE = INITIALIZING_UI_STATE.copy(
    spinnerVisible = false,
    bottomStatusCode = null,
    bottomErrorCode = LivePlaybackUiErrorCode.PlaybackFailed(FailureCode.RESOURCE_RELEASE_FAILED),
    playWhenReady = false,
)
