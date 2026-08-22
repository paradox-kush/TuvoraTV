package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Fork-only: one-shot IPTV expired-link recovery, extracted out of the shared
 * PlayerRuntimeControllerErrorRecovery so that shared file stays takeable from upstream
 * (research/tv-player-mpv-engine-ownership.md, Part B). Same package = the shared callers
 * (Initialization.onPlayerError, Mpv.onMpvPlaybackEndedWithError) still call it unqualified and
 * never cross the firewall. Uses only ports (livePlayback) + StreamRepository + controller members.
 */
/**
 * Stream-side HTTP statuses a fresh create_link can plausibly fix: the play token expired or was
 * consumed by a reconnect (401/410), or the portal session was rotated — e.g. a second device
 * handshaking with the same MAC (403). Everything else (404 removed, 5xx provider down) stays on
 * the normal error path.
 */
internal fun isIptvRefreshableHttpStatus(code: Int): Boolean =
    code == 401 || code == 403 || code == 410

/**
 * The xtream id to re-resolve for the current playback, or null when this isn't IPTV content.
 * Live launches carry it as contentId (Screen.Player live routes pass no videoId); VOD/episode
 * plays carry it as videoId.
 */
internal fun PlayerRuntimeController.refreshableIptvVideoId(): String? =
    livePlayback.classifier.refreshableIptvId(listOf(currentVideoId, contentId))

/** ExoPlayer entry: refresh only for token-shaped HTTP failures (see [isIptvRefreshableHttpStatus]). */
internal fun PlayerRuntimeController.attemptIptvLinkRefresh(
    error: PlaybackException,
    detailedError: String
): Boolean {
    val code = error.findInvalidResponseCodeException()?.responseCode ?: return false
    if (!isIptvRefreshableHttpStatus(code)) return false
    return attemptIptvLinkRefresh(detailedError)
}

/**
 * One-shot recovery for IPTV streams whose tokenized link died mid-flight (Stalker create_link
 * TTL, single-use token consumed by a reconnect, session rotated by another device on the same
 * MAC): mint a FRESH link via the provider and swap it into whichever engine is active. Live
 * rejoins the live edge; VOD/episodes resume at the position where the link died. Returns false
 * when this playback isn't IPTV content or the one shot is already spent — callers then fall
 * through to the normal fatal-error path.
 */
internal fun PlayerRuntimeController.attemptIptvLinkRefresh(detailedError: String): Boolean {
    val refreshId = refreshableIptvVideoId()
    // TMDB-matched lane: the content id is a tmdb/imdb id, but the failing stream may still be an
    // iptv one — matched-lane streams carry the ACCOUNT NAME as addonName, which the repository
    // uses to decide (cheaply, no network for foreign labels) whether a re-match can mint a fresh
    // link. Without at least an addon label there is nothing to re-match against.
    val matchedLane = refreshId == null
    if (matchedLane && currentAddonName.isNullOrBlank()) return false
    if (hasAttemptedIptvLinkRefresh) return false
    hasAttemptedIptvLinkRefresh = true

    // A catch-up replay is deliberately NOT "live" here: its id contains `:live:`, but restarting
    // a recording from zero after a token refresh throws the viewer back to the top of a programme
    // they were half way through.
    val isLive = refreshId != null &&
        livePlayback.classifier.isLiveId(refreshId) &&
        !isCatchUpPlayback
    val paused = userPausedManually
    // Engine-aware: mpv VOD keeps its position too, live always rejoins the live edge.
    val savedPosition = if (isLive) 0L else (currentPlaybackPositionMs()?.takeIf { it > 0L } ?: 0L)

    // Mirrors loadSourceStreams' request derivation so the re-match hits the same catalog entry.
    val matchedType: String
    val matchedVideoId: String?
    val matchedSeason: Int?
    val matchedEpisode: Int?
    if (contentType in listOf("series", "tv") && currentSeason != null && currentEpisode != null) {
        matchedType = contentType ?: "series"
        matchedVideoId = currentVideoId ?: contentId
        matchedSeason = currentSeason
        matchedEpisode = currentEpisode
    } else {
        matchedType = contentType ?: "movie"
        matchedVideoId = contentId
        matchedSeason = null
        matchedEpisode = null
    }
    if (matchedLane && matchedVideoId.isNullOrBlank()) {
        hasAttemptedIptvLinkRefresh = false
        return false
    }

    Log.w(
        PlayerRuntimeController.TAG,
        "IPTV_LINK_REFRESH: stream rejected ($detailedError) — minting a fresh link for " +
            (refreshId ?: "matched:$matchedVideoId via $currentAddonName")
    )

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        val freshUrl = runCatching {
            if (refreshId != null) {
                // forceFresh: with static-cmd playback the plain resolve would rebuild the very
                // URL that just 401'd — the one-shot recovery must mint a genuinely new link.
                streamRepository.refreshIptvStreamUrl(refreshId, forceFresh = true)
            } else {
                streamRepository.refreshMatchedIptvStreamUrl(
                    type = matchedType,
                    videoId = matchedVideoId.orEmpty(),
                    season = matchedSeason,
                    episode = matchedEpisode,
                    addonName = currentAddonName,
                    streamName = _uiState.value.currentStreamName ?: navigationArgs.streamName,
                    failedUrl = currentStreamUrl
                )
            }
        }
            .onFailure { Log.w(PlayerRuntimeController.TAG, "IPTV_LINK_REFRESH: resolve threw: ${it.message}") }
            .getOrNull()
        if (freshUrl.isNullOrBlank()) {
            Log.w(
                PlayerRuntimeController.TAG,
                "IPTV_LINK_REFRESH: no fresh link for ${refreshId ?: "matched:$matchedVideoId"} — surfacing the original error"
            )
            _uiState.update {
                it.copy(
                    error = detailedError,
                    isBuffering = false,
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
            return@launch
        }
        // Live URLs may need the playlist's DoH rewrite (hostname → resolved IP + Host header),
        // exactly like the launch path.
        val prepared = if (isLive) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                livePlayback.dns.prepareLive(refreshId, freshUrl)
            }
        } else null
        val url = prepared?.url ?: freshUrl
        val headers = if (prepared != null && prepared.headers.isNotEmpty()) {
            currentHeaders + prepared.headers
        } else currentHeaders

        val view = mpvView
        if (view != null && isUsingMpvEngine()) {
            // mpv engine: swap in place (loadfile replace), the same path a live channel switch takes.
            currentStreamUrl = url
            currentHeaders = headers
            hasRenderedFirstFrame = false
            lastPlaybackIssueError = null
            _uiState.update { it.copy(error = null, isBuffering = true, currentStreamUrl = url) }
            runCatching {
                view.setMedia(url, headers, startPositionMs = savedPosition)
                view.setPaused(paused)
            }
        } else {
            pendingIptvLinkRefreshReinit = true
            releasePlayer(flushPlaybackState = false)
            if (savedPosition > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
            }
            currentHeaders = headers
            initializePlayer(url, headers, startPaused = paused)
        }
    }
    return true
}

/**
 * mpv end-file(reason=error) — the mpv-engine sibling of ExoPlayer's onPlayerError. Before this,
 * a failed mpv load just left the core idle behind an endless buffering spinner. mpv can't tell a
 * 401 from any other load failure (the HTTP status only appears in its logs), but for IPTV content
 * a fresh create_link is the right first remedy regardless — expired/consumed tokens are by far
 * the dominant cause. Non-IPTV content falls back to startup engine failover, then the error
 * screen. Fires on mpv's event thread, so hop to the controller scope first.
 */
internal fun PlayerRuntimeController.onMpvPlaybackEndedWithError(fileError: String?) {
    scope.launch {
        if (isReleasingPlayer) return@launch
        // In background the live demux is stopped deliberately; resume reloads the stream and a
        // genuinely dead link will re-error in the foreground where we can recover visibly.
        if (isInBackground) return@launch
        val detailedError = context.getString(com.nuvio.tv.R.string.player_error_mpv_playback_failed) +
            (fileError?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        if (attemptIptvLinkRefresh(detailedError)) return@launch
        if (!hasRenderedFirstFrame &&
            maybeAutoSwitchInternalPlayerOnStartupError(detailedError = detailedError, allowEngineFailover = true)
        ) {
            return@launch
        }
        cancelFirstFrameWatchdog()
        Log.w(PlayerRuntimeController.TAG, "mpv end-file error: $detailedError")
        _uiState.update {
            it.copy(
                error = detailedError,
                isBuffering = false,
                showLoadingOverlay = false,
                showPauseOverlay = false
            )
        }
    }
}
