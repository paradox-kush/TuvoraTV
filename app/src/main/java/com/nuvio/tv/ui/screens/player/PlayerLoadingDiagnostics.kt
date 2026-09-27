package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.Player
import com.nuvio.tv.data.repository.PlaybackIssueLoadingEventInput
import com.nuvio.tv.data.repository.PlaybackIssueLoadingInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val LOADING_ISSUE_REPORT_DELAY_MS = 45_000L
private const val LOADING_EVENT_LIMIT = 80
private const val LOADING_RAW_EVENT_LIMIT = 120

internal data class PlayerLoadingDiagnosticEvent(
    val timeMs: Long,
    val elapsedMs: Long,
    val phase: String,
    val message: String?,
    val progress: Float?,
    val detail: String?
)

internal fun PlayerRuntimeController.resetLoadingDiagnostics(
    phase: String,
    message: String? = null,
    progress: Float? = null
) {
    val now = SystemClock.elapsedRealtime()
    loadingDiagnosticsStartedAtMs = now
    currentLoadingPhase = phase
    currentLoadingPhaseStartedAtMs = now
    currentLoadingMessageForReport = message
    currentLoadingProgressForReport = progress
    lastLoadingDiagnosticSignature = ""
    startupPhaseSequence = 0
    loadingDiagnosticEvents.clear()
    loadingDiagnosticRawEventLines.clear()
    recordLoadingDiagnosticEvent(phase = phase, message = message, progress = progress)
    scheduleLoadingIssueReportAvailability()
    scheduleStartOverOffer()
}

internal fun PlayerRuntimeController.setLoadingStatus(
    phase: String,
    message: String?,
    progress: Float? = null,
    showOverlay: Boolean? = null,
    detail: String? = null
) {
    recordLoadingDiagnosticEvent(phase = phase, message = message, progress = progress, detail = detail)
    _uiState.update { state ->
        state.copy(
            showLoadingOverlay = showOverlay ?: state.showLoadingOverlay,
            loadingMessage = message,
            loadingProgress = progress
        )
    }
}

internal fun PlayerRuntimeController.recordLoadingDiagnosticEvent(
    phase: String,
    message: String? = null,
    progress: Float? = null,
    detail: String? = null
) {
    val now = SystemClock.elapsedRealtime()
    val timeMs = System.currentTimeMillis()
    if (loadingDiagnosticsStartedAtMs == 0L) {
        loadingDiagnosticsStartedAtMs = now
        currentLoadingPhaseStartedAtMs = now
    }
    if (phase != currentLoadingPhase) {
        currentLoadingPhase = phase
        currentLoadingPhaseStartedAtMs = now
    }
    currentLoadingMessageForReport = message
    currentLoadingProgressForReport = progress
    val normalizedProgress = progress?.let { "%.3f".format(it.coerceIn(0f, 1f)) }.orEmpty()
    val signature = "$phase|${message.orEmpty()}|$normalizedProgress|${detail.orEmpty()}"
    if (signature == lastLoadingDiagnosticSignature) return
    lastLoadingDiagnosticSignature = signature
    startupPhaseSequence += 1
    val elapsedMs = (now - loadingDiagnosticsStartedAtMs).coerceAtLeast(0L)
    val phaseElapsedMs = (now - currentLoadingPhaseStartedAtMs).coerceAtLeast(0L)
    val clickElapsedMs = launchStartedAtElapsedMs?.let { (now - it).coerceAtLeast(0L) }
    recordLoadingDiagnosticRawEventLine(
        "STARTUP_STAGE: seq=$startupPhaseSequence phase=$phase elapsedMs=$elapsedMs " +
            "phaseElapsedMs=$phaseElapsedMs clickElapsedMs=${clickElapsedMs ?: -1L} " +
            "engine=$currentInternalPlayerEngine host=${currentStreamUrl.safePlaybackRawHost()} " +
            "message=${message?.compactTraceValue() ?: "n/a"} progress=${progress ?: -1f} " +
            "detail=${detail?.compactTraceValue() ?: "n/a"}"
    )
    loadingDiagnosticEvents.addLast(
        PlayerLoadingDiagnosticEvent(
            timeMs = timeMs,
            elapsedMs = elapsedMs,
            phase = phase,
            message = message,
            progress = progress,
            detail = detail
        )
    )
    while (loadingDiagnosticEvents.size > LOADING_EVENT_LIMIT) {
        loadingDiagnosticEvents.removeFirst()
    }
}

internal fun PlayerRuntimeController.finishLoadingDiagnostics(phase: String) {
    recordLoadingDiagnosticEvent(phase = phase, message = null, progress = 1f)
    startupLoadingReportJob?.cancel()
    startupLoadingReportJob = null
    startOverOfferJob?.cancel()
    startOverOfferJob = null
    _uiState.update {
        it.copy(
            loadingIssueReportVisible = false,
            loadingIssueElapsedMs = 0L,
            startOverOfferPositionMs = null
        )
    }
}

/**
 * A resume opens the file mid-way, and some providers take a minute to serve that jump (seen:
 * ~1m46s on Onn for an IPTV MP4 resumed at 13:42 that starts instantly from 0:00). Once a resume has
 * shown no frame for [ResumeLoadPolicy.START_OVER_OFFER_AFTER_MS], offer "Start from beginning".
 */
internal fun PlayerRuntimeController.scheduleStartOverOffer() {
    startOverOfferJob?.cancel()
    if (_uiState.value.startOverOfferPositionMs != null) {
        _uiState.update { it.copy(startOverOfferPositionMs = null) }
    }
    startOverOfferJob = scope.launch {
        delay(ResumeLoadPolicy.START_OVER_OFFER_AFTER_MS)
        val state = _uiState.value
        // The target lives in whichever slot the engine path used; the player's own position reads
        // the seek target while a mid-file load is still pending.
        val resumeAt = listOfNotNull(
            pendingResumeProgress?.position,
            state.pendingSeekPosition,
            currentPlaybackPositionMs(),
        ).maxOrNull() ?: 0L
        val offer = ResumeLoadPolicy.offerStartOver(
            isResumeLoad = ResumeLoadPolicy.isResumeLoad(resumeAt, isLive = isLiveContent()),
            firstFrameShown = hasRenderedFirstFrame,
            loadingForMs = ResumeLoadPolicy.START_OVER_OFFER_AFTER_MS,
        )
        if (offer && state.error == null && state.showLoadingOverlay) {
            _uiState.update { it.copy(startOverOfferPositionMs = resumeAt) }
        }
    }
}

/** "Start from beginning": drop the resume target and reopen the current source at 0:00. */
internal fun PlayerRuntimeController.startOverFromBeginning() {
    val url = currentStreamUrl ?: return
    pendingResumeProgress = null
    requestedStartPositionMs = 0L
    startOverOfferJob?.cancel()
    _uiState.update { it.copy(pendingSeekPosition = null, startOverOfferPositionMs = null) }
    val view = mpvView
    if (view != null && isUsingMpvEngine()) {
        // mpv rejects a seek before playback is initialised; reopen at 0 instead (loadfile replace).
        hasRenderedFirstFrame = false
        runCatching {
            view.setMedia(url, currentHeaders, startPositionMs = 0L)
            view.setPaused(false)
        }
    } else {
        // ExoPlayer honours a seek during preparation: it drops the pending mid-file request.
        _exoPlayer?.let { player ->
            player.seekTo(0L)
            player.playWhenReady = true
        }
    }
}

internal fun PlayerRuntimeController.scheduleLoadingIssueReportAvailability() {
    startupLoadingReportJob?.cancel()
    startupLoadingReportJob = scope.launch {
        delay(LOADING_ISSUE_REPORT_DELAY_MS)
        val state = _uiState.value
        if (state.playbackIssueReportsEnabled && !hasRenderedFirstFrame && state.error == null && state.showLoadingOverlay) {
            val elapsedMs = (SystemClock.elapsedRealtime() - loadingDiagnosticsStartedAtMs).coerceAtLeast(0L)
            _uiState.update {
                it.copy(
                    loadingIssueReportVisible = true,
                    loadingIssueElapsedMs = elapsedMs
                )
            }
        }
    }
}

internal fun PlayerRuntimeController.buildPlaybackIssueLoadingInput(reportReason: String): PlaybackIssueLoadingInput {
    val now = SystemClock.elapsedRealtime()
    val state = _uiState.value
    val player = _exoPlayer
    val elapsedMs = if (loadingDiagnosticsStartedAtMs > 0L) now - loadingDiagnosticsStartedAtMs else 0L
    val phaseElapsedMs = if (currentLoadingPhaseStartedAtMs > 0L) now - currentLoadingPhaseStartedAtMs else 0L
    return PlaybackIssueLoadingInput(
        phase = currentLoadingPhase.ifBlank { "unknown" },
        message = state.loadingMessage ?: currentLoadingMessageForReport,
        progress = state.loadingProgress ?: currentLoadingProgressForReport,
        elapsedMs = elapsedMs.coerceAtLeast(0L),
        phaseElapsedMs = phaseElapsedMs.coerceAtLeast(0L),
        reportReason = reportReason,
        loadingOverlayVisible = state.showLoadingOverlay,
        loadingStatusVisible = state.showPlayerLoadingStatus,
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        exoPlayerCreated = player != null,
        exoPlaybackState = player?.playbackState,
        exoPlaybackStateName = player?.playbackState?.playbackStateName(),
        exoIsLoading = player?.isLoading,
        exoPlayWhenReady = player?.playWhenReady,
        mpvAttached = mpvView != null,
        startupRetryCount = startupRetryCount,
        errorRetryCount = errorRetryCount,
        timeoutRecoveryAttempts = timeoutRecoveryAttempts,
        isLoadingAddonSubtitles = state.isLoadingAddonSubtitles,
        addonSubtitlesCount = state.addonSubtitles.size,
        isLoadingSourceStreams = state.isLoadingSourceStreams,
        isLoadingEpisodeStreams = state.isLoadingEpisodeStreams,
        torrentDownloadSpeed = state.torrentDownloadSpeed,
        torrentPeers = state.torrentPeers,
        torrentSeeds = state.torrentSeeds,
        rawEventLines = loadingDiagnosticRawEventLines.toList(),
        events = loadingDiagnosticEvents.map {
            PlaybackIssueLoadingEventInput(
                timeMs = it.timeMs,
                elapsedMs = it.elapsedMs,
                phase = it.phase,
                message = it.message,
                progress = it.progress,
                detail = it.detail
            )
        }
    )
}

private fun PlayerRuntimeController.recordLoadingDiagnosticRawEventLine(line: String) {
    loadingDiagnosticRawEventLines.addLast(line.rawLoadingEventLine())
    while (loadingDiagnosticRawEventLines.size > LOADING_RAW_EVENT_LIMIT) {
        loadingDiagnosticRawEventLines.removeFirst()
    }
}

private fun Int.playbackStateName(): String =
    when (this) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }

private fun String.safePlaybackRawHost(): String {
    return runCatching {
        Uri.parse(this).host ?: substringBefore("://").takeIf { it.isNotBlank() } ?: "unknown"
    }.getOrDefault("unknown")
}

private fun String.compactTraceValue(): String =
    replace('\n', ' ')
        .replace('\r', ' ')
        .take(160)

private fun String.rawLoadingEventLine(): String =
    replace('\n', ' ')
        .replace('\r', ' ')
        .take(2000)
