package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import com.nuvio.tv.R
import com.nuvio.tv.core.analytics.Breadcrumbs
import com.nuvio.tv.core.analytics.LivePlaybackFreezePolicy
import com.nuvio.tv.core.analytics.LivePlaybackFreezeReporter
import com.nuvio.tv.core.analytics.LivePlaybackRecoveryPolicy
import com.nuvio.tv.core.analytics.LiveRecoveryCoordinator
import com.nuvio.tv.data.local.PlayerSettings
import kotlinx.coroutines.flow.update

/**
 * Keeps live channels playing, and reports the ones that could not be saved.
 *
 * Nothing in Media3 re-prepares a live source, so a clean upstream close or a dead socket
 * leaves a frozen picture with no error — the app's own stall watchdog is off for live too,
 * because it needs a known duration. This detects the freeze from the progress loop,
 * reconnects, and records what happened either way.
 */

internal fun PlayerRuntimeController.armLiveFreezeReporter() {
    // isLiveFeed, not isLiveContent: a catch-up recording that reaches its end has not frozen, and
    // this reporter's recovery ladder ends by reloading the stream from the LIVE EDGE — which
    // would throw the viewer out of the programme they were replaying.
    if (!isLiveFeed()) return
    // The rebuilt player has rendered, so the reconnect is done; whether it actually fixed
    // anything is decided by the next few samples, not by getting this far.
    liveRecoveryInFlight = false
    // Mid-recovery the player is torn down and rebuilt, which lands back here. Re-arming would
    // discard the open freeze and the reconnect count with it.
    if (livePlaybackFreezeReporter.isFreezeOpen) return
    val playerSettings = currentPlayerSettingsForReport
    val engine = if (isUsingMpvEngine()) "mpv" else "exoplayer"
    livePlaybackFreezeReporter.onLivePlaybackStarted(
        profile = LivePlaybackFreezeReporter.Profile(
            engine = engine,
            bufferEngineEnabled = playerSettings.bufferEngineEnabled,
            minBufferMs = effectiveBufferMs(playerSettings, BufferKnob.MIN),
            maxBufferMs = effectiveBufferMs(playerSettings, BufferKnob.MAX),
            bufferForPlaybackMs = effectiveBufferMs(playerSettings, BufferKnob.FOR_PLAYBACK),
            bufferForPlaybackAfterRebufferMs = effectiveBufferMs(playerSettings, BufferKnob.AFTER_REBUFFER),
            streamContainer = LivePlaybackFreezeReporter.streamContainerOf(currentStreamUrl),
            iptvKind = iptvKindOf(contentId),
        ),
        nowMs = System.currentTimeMillis(),
        positionMs = currentLivePositionMs(),
    )
    liveRecoveryAttempts = 0
    lastLiveRecoveryAtMs = 0L
    // A fresh channel-dwell starts the dual-engine escalation budget over (§3.6a rule 5). This
    // point is reached only on a genuinely new tune — the `isFreezeOpen` guard above returns early
    // during a mid-recovery rebuild (including an engine switch), so the flag persists across it.
    liveEngineSwitchedThisDwell = false
    liveReWedgeTimestampsMs.clear()
    lastLiveBackwardJumpMs = null
}

/**
 * Playback breadcrumb for every content kind — [armLiveFreezeReporter] stays live-only. The
 * persisted side is what lets the next launch's `app_exit` say "died while streaming".
 */
internal fun PlayerRuntimeController.recordPlaybackStartBreadcrumb() {
    Breadcrumbs.playbackStarted(
        kind = if (isLiveContent()) "live" else "vod",
        engine = if (isUsingMpvEngine()) "mpv" else "exoplayer",
        surface = "player",
        container = LivePlaybackFreezeReporter.streamContainerOf(currentStreamUrl),
        nowMs = System.currentTimeMillis(),
    )
}

/** One sampled tick. No-op unless a live channel is being watched. */
internal fun PlayerRuntimeController.sampleLiveFreeze(positionMs: Long, bufferedPositionMs: Long) {
    if (!isLiveFeed()) return
    val state: LivePlaybackFreezePolicy.PlaybackState
    val wantsToPlay: Boolean
    // The one signal audio cannot keep alive: a frozen picture with playing audio leaves every
    // other field here looking healthy, including the playhead.
    val videoProgressTicks: Long
    val hasVideoTrack: Boolean
    if (isUsingMpvEngine()) {
        val view = mpvView ?: return
        // mpv has no ENDED state to read: keep-open=yes parks the core at EOF holding the last
        // frame, which surfaces as idle-and-not-playing. That reads as a stall here, and the
        // reconnect is the same either way.
        state = when {
            view.isPausedForCacheNow() -> LivePlaybackFreezePolicy.PlaybackState.BUFFERING
            view.isCoreIdleNow() -> LivePlaybackFreezePolicy.PlaybackState.IDLE
            else -> LivePlaybackFreezePolicy.PlaybackState.READY
        }
        wantsToPlay = !userPausedManually
        videoProgressTicks = view.videoFrameTicksNow()
        hasVideoTrack = view.hasVideoTrackNow()
    } else {
        val player = _exoPlayer ?: return
        state = when (player.playbackState) {
            Player.STATE_BUFFERING -> LivePlaybackFreezePolicy.PlaybackState.BUFFERING
            Player.STATE_READY -> LivePlaybackFreezePolicy.PlaybackState.READY
            Player.STATE_ENDED -> LivePlaybackFreezePolicy.PlaybackState.ENDED
            else -> LivePlaybackFreezePolicy.PlaybackState.IDLE
        }
        wantsToPlay = player.playWhenReady && !userPausedManually
        // The renderer's own count of frames it put on screen.
        videoProgressTicks = player.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: 0L
        hasVideoTrack = player.videoFormat != null
    }

    val decision = livePlaybackFreezeReporter.onSample(
        nowMs = System.currentTimeMillis(),
        positionMs = positionMs,
        bufferedPositionMs = bufferedPositionMs,
        state = state,
        wantsToPlay = wantsToPlay,
        rebufferCount = rebufferCount,
        rebufferTotalMs = rebufferTotalMs,
        videoProgressTicks = videoProgressTicks,
        hasVideoTrack = hasVideoTrack,
    )

    when (decision) {
        is LivePlaybackFreezePolicy.Decision.Start -> {
            // A new freeze incident: record it for the coordinator's re-wedge persistence signal.
            recordLiveWedgeIncident(System.currentTimeMillis())
            maybeReconnectLiveStream(decision.kind)
        }
        is LivePlaybackFreezePolicy.Decision.Continue -> maybeReconnectLiveStream(decision.kind)
        else -> Unit
    }
}

/** Records a distinct freeze incident and keeps only those within the coordinator's stable window,
 *  so a channel that recovers and plays cleanly for [LiveRecoveryCoordinator.T_STABLE_MS] is not
 *  counted as persistent. */
private fun PlayerRuntimeController.recordLiveWedgeIncident(nowMs: Long) {
    liveReWedgeTimestampsMs.addLast(nowMs)
    while (liveReWedgeTimestampsMs.isNotEmpty() &&
        nowMs - liveReWedgeTimestampsMs.first() > LiveRecoveryCoordinator.T_STABLE_MS
    ) {
        liveReWedgeTimestampsMs.removeFirst()
    }
}

/**
 * The dual-engine escalation rung (design §4 rung 4). Returns true when it has taken (or is
 * deliberately waiting to take) ownership of this incident — the caller then skips the same-engine
 * reconnect ladder, because re-opening ExoPlayer on a backward-PTS stream only re-wedges (the 7TV
 * RCA). Only ExoPlayer escalates to libmpv, at most once per dwell; if we are already on mpv (or the
 * switch was already spent), the normal ladder runs and eventually surfaces a specific error.
 */
private fun PlayerRuntimeController.maybeSwitchEngineForPersistentLiveFreeze(sinceLastAttemptMs: Long): Boolean {
    if (isUsingMpvEngine() || liveEngineSwitchedThisDwell || !livePlaybackMpvAvailable) return false
    val nowMs = System.currentTimeMillis()
    val reWedges = liveReWedgeTimestampsMs.count { nowMs - it <= LiveRecoveryCoordinator.T_STABLE_MS }
    val persistent = LiveRecoveryCoordinator.isPersistent(
        LiveRecoveryCoordinator.Input(
            fault = LiveRecoveryCoordinator.Fault.STALL,
            engine = LiveRecoveryCoordinator.Engine.EXO,
            isLiveFeed = true,
            mpvAvailable = true,
            engineSwitchedThisDwell = false,
            connectionFreeAttempts = 0,
            backwardJumpMs = lastLiveBackwardJumpMs,
            reWedgesInWindow = reWedges,
            reopensThisIncident = liveRecoveryAttempts,
            sinceLastReopenMs = sinceLastAttemptMs,
            maxConnectionsOne = false,
        )
    )
    if (!persistent) return false
    // Persistent: never re-open on the dying engine. Honour the ≥5 s handoff backoff (§3.6/F3) so
    // the provider's slot can release before mpv opens cold — but hold the incident either way.
    if (sinceLastAttemptMs < LiveRecoveryCoordinator.MIN_REOPEN_BACKOFF_MS) return true
    switchEngineForPersistentLiveFreeze()
    return true
}

/**
 * Re-prepares the current live stream, which is what a viewer is doing by hand when they
 * change channel and come back. Backed off and capped by [LivePlaybackRecoveryPolicy].
 */
private fun PlayerRuntimeController.maybeReconnectLiveStream(kind: LivePlaybackFreezePolicy.Kind) {
    val nowMs = System.currentTimeMillis()
    val sinceLastAttemptMs = if (lastLiveRecoveryAtMs == 0L) Long.MAX_VALUE else nowMs - lastLiveRecoveryAtMs
    if (liveRecoveryInFlight) {
        // The flag clears when the rebuilt player renders. If it never does, the ladder would
        // wedge here forever, so give the rebuild a bounded window and then move on.
        if (sinceLastAttemptMs < LIVE_RECOVERY_RENDER_TIMEOUT_MS) return
        liveRecoveryInFlight = false
    }

    // Dual-engine escalation (design §4 rung 4): a persistent backward-PTS discontinuity (7TV) is
    // fixed ONLY by switching to the clock-rebasing engine (libmpv) — never another re-open on the
    // same one. The coordinator owns the persistence decision; when it fires, the same-engine
    // reconnect ladder below is skipped entirely for this incident.
    if (maybeSwitchEngineForPersistentLiveFreeze(sinceLastAttemptMs)) return

    when (
        LivePlaybackRecoveryPolicy.evaluate(
            LivePlaybackRecoveryPolicy.Input(
                attempts = liveRecoveryAttempts,
                sinceLastAttemptMs = sinceLastAttemptMs,
                kind = kind,
                // Only mpv can reinitialise its video track without a new connection; the
                // ExoPlayer path escalates straight to a re-prepare rather than spending
                // attempts on a primitive it does not have.
                videoResetAttempts = if (isUsingMpvEngine()) {
                    LivePlaybackRecoveryPolicy.VIDEO_RESET_ATTEMPTS
                } else {
                    0
                },
            )
        )
    ) {
        LivePlaybackRecoveryPolicy.Decision.Wait -> Unit

        // Audio is still arriving, so the link works and the fault is downstream of it.
        // `video-reload` reinitialises the video track off the demuxer that is already
        // connected — no new create_link, nothing spent against the provider's connection cap.
        LivePlaybackRecoveryPolicy.Decision.ResetVideo -> {
            liveRecoveryAttempts += 1
            lastLiveRecoveryAtMs = nowMs
            livePlaybackFreezeReporter.onRecoveryAttempt(nowMs)
            Log.w(
                PlayerRuntimeController.TAG,
                "LIVE_RECONNECT: reloading video track (kind=$kind attempt=$liveRecoveryAttempts)",
            )
            mpvView?.reloadVideoTrack()
        }

        LivePlaybackRecoveryPolicy.Decision.GiveUp -> {
            // Before conceding, take the one escape the same-engine ladder cannot: switch engines.
            // Telemetry (2026-08-25) shows the dominant live freeze is a plain video_stalled on raw
            // .ts on BOTH engines, and 12 libmpv incidents sat frozen ~2h because nothing surfaced.
            // So a persistent ExoPlayer freeze that never reached the backward-PTS "persistent" rung
            // still gets one last-resort libmpv attempt here. If we are already on mpv (or the switch
            // is spent), there is nothing left to try — surface it, never let the picture stand.
            if (!isUsingMpvEngine() && !liveEngineSwitchedThisDwell && livePlaybackMpvAvailable) {
                Log.w(
                    PlayerRuntimeController.TAG,
                    "LIVE_RECONNECT: out of ExoPlayer attempts (kind=$kind) — last-resort switch to libmpv",
                )
                switchEngineForPersistentLiveFreeze()
                return
            }
            if (!liveRecoveryGaveUp) {
                liveRecoveryGaveUp = true
                // Auto-report the terminal freeze (gave_up=true) so the fleet sees it without the
                // viewer acting, then surface an actionable overlay — never a silent frozen picture.
                val pos = currentLivePositionMs()
                livePlaybackFreezeReporter.onRecoveryGaveUp(
                    nowMs = System.currentTimeMillis(),
                    positionMs = pos,
                    bufferedPositionMs = currentLiveBufferedPositionMs(pos),
                    rebufferCount = rebufferCount,
                    rebufferTotalMs = rebufferTotalMs,
                )
                surfaceLiveFreezeGaveUp()
                Log.w(
                    PlayerRuntimeController.TAG,
                    "LIVE_RECONNECT: giving up after $liveRecoveryAttempts attempts (kind=$kind)",
                )
            }
        }

        LivePlaybackRecoveryPolicy.Decision.Reconnect -> {
            liveRecoveryAttempts += 1
            lastLiveRecoveryAtMs = nowMs
            livePlaybackFreezeReporter.onRecoveryAttempt(nowMs)
            Log.w(
                PlayerRuntimeController.TAG,
                "LIVE_RECONNECT: re-preparing live stream (kind=$kind attempt=$liveRecoveryAttempts)",
            )
            // Guards the teardown: releasePlayer() would otherwise close the freeze record as
            // unrecovered, and the rebuild would re-arm a fresh one, so a successful reconnect
            // would never be attributable to the reconnect.
            liveRecoveryInFlight = true
            // Live resumes at the live edge, never at a saved position.
            reinitializeLiveStreamFromLiveEdge()
        }
    }
}

/**
 * Flushes an unresolved freeze. Reaching here while frozen is exactly the reported workaround —
 * the viewer changed channel or backed out because the picture never came back.
 */
internal fun PlayerRuntimeController.stopLiveFreezeReporter() {
    // A reconnect releases the player on purpose; the freeze is still open and unresolved.
    if (liveRecoveryInFlight) return
    val position = currentLivePositionMs()
    livePlaybackFreezeReporter.onLivePlaybackStopped(
        nowMs = System.currentTimeMillis(),
        positionMs = position,
        bufferedPositionMs = currentLiveBufferedPositionMs(position),
        rebufferCount = rebufferCount,
        rebufferTotalMs = rebufferTotalMs,
    )
    liveRecoveryAttempts = 0
    lastLiveRecoveryAtMs = 0L
    liveRecoveryGaveUp = false
}

/**
 * Surfaces a terminal live freeze as an actionable error overlay (Retry + Report Frozen) instead of
 * a silently standing frozen picture — the fix for the fleet's never-recovered tail (telemetry
 * 2026-08-25). [PlayerUiState.liveFreezeGaveUp] flags the overlay so the Report action appears even
 * when generic issue reporting is off, and so its report emits freeze-specific telemetry.
 */
private fun PlayerRuntimeController.surfaceLiveFreezeGaveUp() {
    _uiState.update {
        it.copy(
            error = context.getString(R.string.player_live_freeze_gave_up),
            liveFreezeGaveUp = true,
            showLoadingOverlay = false,
            isBuffering = false,
            showPauseOverlay = false,
        )
    }
}

private fun PlayerRuntimeController.currentLivePositionMs(): Long =
    if (isUsingMpvEngine()) {
        mpvView?.currentPositionMs()?.coerceAtLeast(0L) ?: 0L
    } else {
        _exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
    }

private fun PlayerRuntimeController.currentLiveBufferedPositionMs(positionMs: Long): Long =
    if (isUsingMpvEngine()) positionMs else _exoPlayer?.bufferedPosition?.coerceAtLeast(0L) ?: positionMs

private enum class BufferKnob { MIN, MAX, FOR_PLAYBACK, AFTER_REBUFFER }

/**
 * What the LoadControl is really using. With the buffer engine off the stored [PlayerSettings]
 * values are inert and Media3's own defaults apply, so reporting the stored ones would point
 * any buffer-starvation analysis at numbers that were never in effect.
 */
private fun effectiveBufferMs(playerSettings: PlayerSettings, knob: BufferKnob): Int {
    if (!playerSettings.bufferEngineEnabled) {
        return when (knob) {
            BufferKnob.MIN -> DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
            BufferKnob.MAX -> DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
            BufferKnob.FOR_PLAYBACK -> DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
            BufferKnob.AFTER_REBUFFER -> DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        }
    }
    val buffers = playerSettings.bufferSettings
    return when (knob) {
        BufferKnob.MIN -> buffers.minBufferMs
        BufferKnob.MAX -> buffers.maxBufferMs
        BufferKnob.FOR_PLAYBACK -> buffers.bufferForPlaybackMs
        BufferKnob.AFTER_REBUFFER -> buffers.bufferForPlaybackAfterRebufferMs
    }
}

/**
 * How long a reconnect is given to render before the ladder stops waiting on it. Comfortably
 * past [PlayerRuntimeController.FIRST_FRAME_TIMEOUT_MS] so the startup recovery path gets its
 * turn on a slow channel before this steps in.
 */
private const val LIVE_RECOVERY_RENDER_TIMEOUT_MS = 20_000L

/** Which IPTV lane produced the channel. Never carries the provider host. */
private fun iptvKindOf(contentId: String?): String = when {
    contentId == null -> "other"
    contentId.startsWith("xtream:") -> "xtream"
    else -> "other"
}
