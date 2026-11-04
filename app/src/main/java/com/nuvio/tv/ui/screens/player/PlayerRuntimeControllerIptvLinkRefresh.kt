package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.diagnostics.LogRedaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Fork-only (B1 + C7): one-shot IPTV expired-link recovery on upstream's player. Called first from
 * ExoPlayer's onPlayerError and libmpv's handleMpvPlaybackError, before any upstream rung (parsing
 * fallback, startup retry, engine failover) could replay the dead single-use link. The decision is
 * [IptvLinkRefreshPolicy]; this only mints through StreamRepository and reopens the stream, resuming
 * where the viewer was (or, before the first frame, at the requested resume: [PlaybackStartPositionPolicy]).
 *
 * Returns true when the error is handled here (a re-mint started or is already in flight), false to
 * hand it to upstream's handling unchanged.
 */
internal fun PlayerRuntimeController.attemptIptvLinkRefresh(httpStatus: Int?, detailedError: String): Boolean {
    // A second error event for the link being replaced (end-file + shutdown, or a late Exo error).
    if (iptvLinkRefreshInFlight) return true
    val iptvId = iptvClassifier?.refreshableIptvId(listOf(currentVideoId, contentId))
    val lane = IptvLinkRefreshPolicy.lane(
        httpStatus = httpStatus,
        iptvId = iptvId,
        isLiveId = iptvId != null && iptvClassifier?.isLiveId(iptvId) == true,
        isCatchUp = navigationArgs.isCatchUp,
        addonName = currentAddonName,
        alreadyAttempted = hasAttemptedIptvLinkRefresh,
    ) ?: return false

    val isEpisode = contentType in listOf("series", "tv") && currentSeason != null && currentEpisode != null
    val matchedType = if (isEpisode) contentType ?: "series" else contentType ?: "movie"
    val matchedVideoId = if (isEpisode) currentVideoId ?: contentId else contentId
    if (lane == IptvLinkRefreshPolicy.Lane.Matched && matchedVideoId.isNullOrBlank()) return false

    hasAttemptedIptvLinkRefresh = true
    iptvLinkRefreshInFlight = true
    val paused = userPausedManually
    val savedPosition = PlaybackStartPositionPolicy.targetAfterSourceSwap(
        isLive = false,
        firstFrameShown = hasRenderedFirstFrame,
        currentPositionMs = currentPlaybackPositionMs(),
        requestedStartMs = requestedStartPositionMs,
    )
    val failedUrl = currentStreamUrl
    Log.w(PlayerRuntimeController.TAG, "IPTV_LINK_REFRESH: HTTP $httpStatus, minting a fresh link (${lane::class.simpleName})")

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        try {
            showRecoveryOverlay()
            val fresh = try {
                when (lane) {
                    // forceFresh: a static-cmd resolve would rebuild the very URL that just died.
                    is IptvLinkRefreshPolicy.Lane.IptvId -> streamRepository.refreshIptvStreamUrl(lane.id, forceFresh = true)
                    IptvLinkRefreshPolicy.Lane.Matched -> streamRepository.refreshMatchedIptvStreamUrl(
                        type = matchedType,
                        videoId = matchedVideoId.orEmpty(),
                        season = if (isEpisode) currentSeason else null,
                        episode = if (isEpisode) currentEpisode else null,
                        addonName = currentAddonName,
                        streamName = _uiState.value.currentStreamName ?: navigationArgs.streamName,
                        failedUrl = failedUrl,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(PlayerRuntimeController.TAG, "IPTV_LINK_REFRESH: resolve threw: ${LogRedaction.text(e.message)}")
                null
            }
            if (fresh.isNullOrBlank() || fresh == failedUrl || streamRepository.isDeferredIptvUrl(fresh)) {
                // No new link: show the error. Upstream's rungs would only replay the dead link.
                Log.w(PlayerRuntimeController.TAG, "IPTV_LINK_REFRESH: no fresh link; surfacing the original error")
                _uiState.update {
                    it.copy(error = detailedError, isBuffering = false, showLoadingOverlay = false, showPauseOverlay = false)
                }
                return@launch
            }
            releasePlayer(flushPlaybackState = false)
            currentStreamUrl = fresh
            _uiState.update {
                it.copy(currentStreamUrl = fresh, pendingSeekPosition = savedPosition.takeIf { pos -> pos > 0L })
            }
            reinitializeCurrentStream(startPaused = paused)
        } finally {
            iptvLinkRefreshInFlight = false
        }
    }
    return true
}
