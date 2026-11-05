package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.SeekParameters
import com.nuvio.tv.R
import com.nuvio.tv.core.contracts.OwnSourcePolicy
import com.nuvio.tv.core.party.OwnSeek
import com.nuvio.tv.core.party.PartyEvent
import com.nuvio.tv.core.party.PartyMedia
import com.nuvio.tv.core.party.PartyMemberStatus
import com.nuvio.tv.core.party.PartyPlayerPolicy
import com.nuvio.tv.core.party.PartyPlayerSyncPolicy
import com.nuvio.tv.core.party.PartySource
import com.nuvio.tv.core.party.PartyStream
import com.nuvio.tv.core.party.PartySync
import com.nuvio.tv.core.party.PartyTickAction
import com.nuvio.tv.core.party.PartyTickInput
import com.nuvio.tv.core.party.PartyTickState
import com.nuvio.tv.ui.screens.party.messageRes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.os.SystemClock

/*
 * Watch-party sync for the VOD player. Grafted from upstream NuvioMedia/NuvioTV #4028
 * (`PlayerRuntimeControllerParty.kt`, commits e1faa00ed sync, 628ad8dae behind/catch-up,
 * ce467de2c host-only media, abec3d5c4 description, e5f6c5fee shared stream) onto the fork-pinned
 * player. The decisions live in [PartyPlayerSyncPolicy] / [PartyPlayerPolicy]; this file only reads
 * player facts and applies the policy's actions. Every engine call goes through the controller's
 * engine-neutral helpers (seekPlaybackTo / setPlaybackPaused / setPlaybackSpeedInternal and the
 * position/duration reads), which on mpv are shadow reads and `ctl {}`-queued writes — never a
 * direct mpv call on the main thread.
 */

internal val PlayerRuntimeController.partyKey: String
    get() = party.playerKey(contentType.orEmpty(), contentId.orEmpty(), currentSeason, currentEpisode)

internal val PlayerRuntimeController.isOwnSourceContent: Boolean
    get() = OwnSourcePolicy.isOwnContentId(contentId) || OwnSourcePolicy.isOwnContentId(currentVideoId)

internal val PlayerRuntimeController.canUseParty: Boolean
    get() = PartyPlayerPolicy.canUseParty(
        contentType = contentType,
        contentId = contentId,
        isLive = isLiveContent(),
        isOwnSource = isOwnSourceContent,
    )

internal val PlayerRuntimeController.isPartySynced: Boolean
    get() = PartyPlayerPolicy.isSynced(party.state.value.party, partyKey)

internal val PlayerRuntimeController.isPartyControlLocked: Boolean
    get() = PartyPlayerPolicy.isControlLocked(party.state.value.party, partyKey)

internal fun PlayerRuntimeController.partyBlocksControl(): Boolean {
    if (!isPartyControlLocked) return false
    showPartyMessage(context.getString(R.string.party_host_only_note))
    return true
}

internal fun PlayerRuntimeController.showPartyMessage(text: String) {
    hideStreamSourceIndicatorJob?.cancel()
    _uiState.update { it.copy(showStreamSourceIndicator = true, streamSourceIndicatorText = text) }
    hideStreamSourceIndicatorJob = scope.launch {
        delay(PARTY_MESSAGE_MS)
        _uiState.update { it.copy(showStreamSourceIndicator = false) }
    }
}

internal fun PlayerRuntimeController.showPartyPanel() {
    _uiState.update {
        it.copy(
            showPartyPanel = true,
            showControls = true,
            showMoreDialog = false,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showSourcesPanel = false,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
        )
    }
}

internal fun PlayerRuntimeController.dismissPartyPanel() {
    _uiState.update { it.copy(showPartyPanel = false) }
    scheduleHideControls()
}

/** Play/pause for a member locked out by the host-only rule: it only pauses or resumes itself. */
internal fun PlayerRuntimeController.toggleOwnPlayback() {
    when {
        hasActivePlayIntent() -> {
            setPartyBehind(true)
            applyPartyPaused(paused = true)
        }
        _uiState.value.partyBehind -> applyPartyPaused(paused = false)
        else -> partyBlocksControl()
    }
    showControlsTemporarily()
}

/** Where a locked member's seek may go: back (falls behind), or forward until it catches up. */
internal fun PlayerRuntimeController.ownSeekTarget(targetMs: Long): Long? {
    val playback = party.playback.value ?: return targetMs
    val partyPositionMs = PartySync.expectedPositionMs(playback, party.nowMs(), currentPlaybackDurationMs().coerceAtLeast(0L))
    return when (val seek = PartySync.ownSeek(targetMs, partyPositionMs, _uiState.value.partyBehind)) {
        is OwnSeek.Behind -> {
            setPartyBehind(true)
            seek.positionMs
        }
        OwnSeek.CatchUp -> {
            setPartyBehind(false)
            null
        }
        OwnSeek.Blocked -> {
            partyBlocksControl()
            null
        }
    }
}

internal fun PlayerRuntimeController.catchUpParty() {
    setPartyBehind(false)
}

internal fun PlayerRuntimeController.currentPartyMedia(): PartyMedia = party.withServer(localPartyMedia())

private fun PlayerRuntimeController.localPartyMedia(): PartyMedia = PartyMedia(
    type = contentType.orEmpty(),
    metaId = contentId.orEmpty(),
    metaType = contentType.orEmpty(),
    videoId = currentVideoId,
    season = currentSeason,
    episode = currentEpisode,
    title = contentName?.takeIf { it.isNotBlank() } ?: title,
    episodeTitle = currentEpisodeTitle,
    description = _uiState.value.description?.take(PARTY_DESCRIPTION_LIMIT),
    poster = poster,
    background = backdrop,
    logo = logo,
    source = PartySource(
        infoHash = currentInfoHash,
        fileIdx = currentFileIdx,
        filename = currentFilename,
        videoSize = currentVideoSize,
        bingeGroup = currentStreamBingeGroup,
        name = _uiState.value.currentStreamName,
    ),
)

/** The host's current link when it may be shared (see [PartyPlayerPolicy.mayShareStream]), else null. */
internal fun PlayerRuntimeController.currentPartyStream(): PartyStream? {
    val shareable = PartyPlayerPolicy.mayShareStream(
        party = party.state.value.party,
        isTorrent = isTorrentStream,
        isServerStream = _uiState.value.isServerStream,
        isOwnSource = isOwnSourceContent,
        url = currentStreamUrl,
    )
    if (!shareable) return null
    return PartyStream(
        mediaKey = partyKey,
        url = currentStreamUrl,
        headers = currentHeaders,
        responseHeaders = currentStreamResponseHeaders,
        name = _uiState.value.currentStreamName,
        description = currentStreamDescription,
        addonName = currentAddonName.orEmpty(),
        addonLogo = currentAddonLogo,
        filename = currentFilename,
        videoSize = currentVideoSize,
        bingeGroup = currentStreamBingeGroup,
    )
}

/** Whether the host could share this stream if it opted in (drives the panel's switch). */
internal fun PlayerRuntimeController.canSharePartyStream(): Boolean {
    val current = party.state.value.party ?: return false
    return PartyPlayerPolicy.mayShareStream(
        party = current.copy(sharing = true),
        isTorrent = isTorrentStream,
        isServerStream = _uiState.value.isServerStream,
        isOwnSource = isOwnSourceContent,
        url = currentStreamUrl,
    )
}

internal fun PlayerRuntimeController.partyBaseRate(): Float =
    party.playback.value?.rate ?: _uiState.value.playbackSpeed

/** A local control (play/pause, seek, speed) on the party's media becomes the room's state. */
internal fun PlayerRuntimeController.publishPartyPlayback(
    positionMs: Long = currentPlaybackPositionMs() ?: 0L,
    rate: Float = partyBaseRate(),
    paused: Boolean = !hasActivePlayIntent(),
) {
    if (!isPartySynced) return
    party.publishPlayback(paused = paused, positionMs = positionMs, rate = rate)
}

internal fun PlayerRuntimeController.partyResumePositionMs(): Long? =
    PartyPlayerPolicy.resumePositionMs(party.state.value.party, party.playback.value, partyKey, party.nowMs())

internal fun PlayerRuntimeController.startParty() {
    scope.launch {
        val error = party.create(currentPartyMedia()) ?: return@launch
        showPartyMessage(context.getString(error.messageRes))
    }
}

internal fun PlayerRuntimeController.switchPartyToCurrent() {
    party.publishMedia(currentPartyMedia(), currentPlaybackPositionMs() ?: 0L)
}

/** Leaving the player: pause the room if nobody else is watching, and say we're browsing. */
internal fun PlayerRuntimeController.leavePartyPlayback() {
    party.state.value.party ?: return
    val playback = party.playback.value
    if (PartyPlayerPolicy.pauseRoomOnLeave(party.state.value.party, playback, partyKey) && playback != null) {
        party.publishPlayback(paused = true, positionMs = currentPlaybackPositionMs() ?: playback.positionMs, rate = playback.rate)
    }
    party.setLocalStatus(PartyMemberStatus.Browsing, null)
}

/** Started once per player: runs the sync loop while a party exists and follows media changes. */
internal fun PlayerRuntimeController.bindParty() {
    scope.launch {
        party.state
            .map { it.party?.id }
            .distinctUntilChanged()
            .collectLatest { partyId ->
                if (partyId == null) {
                    clearPartyNudge()
                    setPartyBehind(false)
                } else {
                    runPartySync()
                }
            }
    }
    scope.launch {
        party.events.collect { event ->
            if (event is PartyEvent.MediaChanged) handlePartyMediaChange(event.media)
        }
    }
}

private fun PlayerRuntimeController.setPartyBehind(behind: Boolean) {
    if (_uiState.value.partyBehind != behind) {
        _uiState.update { it.copy(partyBehind = behind, partyBehindMs = it.partyBehindMs.takeIf { behind }) }
    }
}

private fun PlayerRuntimeController.applyPartyPaused(paused: Boolean) {
    if (paused) {
        userPausedManually = true
        setPlaybackPaused(true)
        if (isUsingMpvEngine()) {
            stopProgressUpdates()
            stopWatchProgressSaving()
            emitPauseScrobbleForCurrentProgress()
        }
    } else {
        userPausedManually = false
        cancelPauseOverlay()
        setPlaybackPaused(false)
        if (isUsingMpvEngine()) {
            startProgressUpdates()
            startWatchProgressSaving()
            emitScrobbleStart()
        }
    }
}

private fun PlayerRuntimeController.clearPartyNudge(rate: Float = partyBaseRate()) {
    if (partyRateNudge == null) return
    partyRateNudge = null
    setPlaybackSpeedInternal(rate)
}

private fun PlayerRuntimeController.handlePartyMediaChange(media: PartyMedia) {
    if (media.key == partyKey) return
    val next = _uiState.value.nextEpisode
    if (media.metaId == contentId && next != null && next.hasAired && next.season == media.season && next.episode == media.episode) {
        playNextEpisode(userInitiated = true)
        return
    }
    showPartyPanel()
}

private fun PlayerRuntimeController.partyTickInput(current: com.nuvio.tv.core.party.ActiveParty): PartyTickInput {
    val state = _uiState.value
    return PartyTickInput(
        monoMs = SystemClock.elapsedRealtime(),
        partyNowMs = party.nowMs(),
        party = current,
        playerKey = partyKey,
        playback = party.playback.value,
        positionMs = currentPlaybackPositionMs(),
        durationMs = currentPlaybackDurationMs().coerceAtLeast(0L),
        playing = hasActivePlayIntent(),
        rate = state.playbackSpeed,
        loading = state.isBuffering || state.showLoadingOverlay,
        hasError = state.error != null,
        inBackground = isInBackground,
        rendered = hasRenderedFirstFrame,
        playbackEnded = state.playbackEnded,
        previewSeekPending = pendingPreviewSeekPosition != null,
        behind = state.partyBehind,
        behindShownMs = state.partyBehindMs,
        nudgeRate = partyRateNudge,
    )
}

private fun PlayerRuntimeController.applyPartyTick(action: PartyTickAction) {
    when (action) {
        PartyTickAction.PublishMediaSwitch -> party.publishMedia(currentPartyMedia(), positionMs = 0L)
        is PartyTickAction.ReportStatus -> party.setLocalStatus(action.report.status, action.report.mediaKey, action.report.behindMs)
        is PartyTickAction.Publish -> party.publishPlayback(paused = action.paused, positionMs = action.positionMs, rate = action.rate)
        is PartyTickAction.SetPaused -> applyPartyPaused(action.paused)
        is PartyTickAction.Seek -> {
            val duration = currentPlaybackDurationMs()
            seekPlaybackTo(if (duration > 0L) action.positionMs.coerceAtMost(duration) else action.positionMs, SeekParameters.EXACT)
        }
        is PartyTickAction.SetRate -> {
            setPlaybackSpeedInternal(action.rate)
            _uiState.update { it.copy(playbackSpeed = action.rate) }
        }
        is PartyTickAction.Nudge -> {
            partyRateNudge = action.rate
            setPlaybackSpeedInternal(action.rate)
        }
        is PartyTickAction.ClearNudge -> clearPartyNudge(action.rate)
        is PartyTickAction.SetBehind -> setPartyBehind(action.behind)
        is PartyTickAction.ShowBehind -> _uiState.update { it.copy(partyBehindMs = action.gapMs) }
        PartyTickAction.CaughtUp -> showPartyMessage(context.getString(R.string.party_caught_up))
    }
}

private suspend fun PlayerRuntimeController.runPartySync() {
    var tickState = PartyTickState()
    var lastShared: PartyStream? = null
    try {
        while (currentCoroutineContext().isActive) {
            delay(PartyPlayerSyncPolicy.TICK_MS)
            val current = party.state.value.party ?: break

            // Stream sharing: the repository re-checks host + opt-in; only changes are sent.
            if (current.isHost && current.sharing) {
                val shared = currentPartyStream()
                if (shared != lastShared) {
                    lastShared = shared
                    party.shareStream(shared)
                }
            } else {
                lastShared = null
            }

            val result = PartyPlayerSyncPolicy.tick(tickState, partyTickInput(current))
            tickState = result.state
            result.actions.forEach { applyPartyTick(it) }
        }
    } finally {
        clearPartyNudge()
    }
}

private const val PARTY_MESSAGE_MS = 2_200L
private const val PARTY_DESCRIPTION_LIMIT = 600
