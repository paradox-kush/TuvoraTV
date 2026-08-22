package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.flow.update

/**
 * Fork-only: in-place live-channel switch (D-pad zap / catch-up dialect retune), extracted from the
 * shared PlayerRuntimeControllerStreams so that file stays takeable from upstream
 * (research/tv-player-mpv-engine-ownership.md, Part B). Same package = callers (CatchUp, ViewModel.zapLive)
 * call it unqualified.
 */
/** Zap to another live channel by swapping the stream on the RUNNING mpv instance
 *  (`loadfile replace`) — no releasePlayer/destroy. Destroying a stuck decoder (e.g. a 4K
 *  channel the device can't decode) would block the main thread; loadfile-replace aborts the
 *  current file and loads the new one cleanly, which is also the smoothest zap. */
internal fun PlayerRuntimeController.switchToLiveChannel(
    name: String,
    url: String,
    extraHeaders: Map<String, String> = emptyMap(),
    videoId: String? = null
) {
    // Keep identity current so a mid-watch expired link re-resolves the RIGHT channel,
    // and give the new channel its own refresh shot.
    if (videoId != null) currentVideoId = videoId
    hasAttemptedIptvLinkRefresh = false
    pendingIptvLinkRefreshReinit = false
    // Additive (e.g. a DoH Host header) — merged over the live stream's existing headers.
    val headers = if (extraHeaders.isEmpty()) currentHeaders else currentHeaders + extraHeaders
    val view = mpvView
    if (view == null || !isUsingMpvEngine()) {
        // Fallback for the unexpected non-mpv case: full source switch.
        switchToSourceStream(
            com.nuvio.tv.domain.model.Stream(
                name = name, title = name, description = null, url = url, ytId = null,
                infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
                addonName = "Xtream IPTV", addonLogo = null
            )
        )
        return
    }
    flushPlaybackSnapshotForSwitchOrExit()
    currentStreamUrl = url
    resetLoadingOverlayForNewStream()
    _uiState.update {
        it.copy(
            title = name,
            contentName = name,
            currentStreamName = name,
            currentStreamUrl = url,
            isBuffering = true,
            error = null,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1
        )
    }
    hasRenderedFirstFrame = false
    runCatching {
        view.setMedia(url, headers)
        view.setPaused(false)
    }
    emitScrobbleStart()
}
