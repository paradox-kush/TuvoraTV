package com.nuvio.tv.playback.ui

import com.nuvio.tv.playback.core.PlaybackCommand
import com.nuvio.tv.playback.core.PlaybackPreferences
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.PlaybackSession
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.SessionProfile
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-safe ownership boundary for one clean playback session.
 *
 * The UI observes only the immutable, secret-free [snapshot] and sends engine-neutral commands.
 * Adapter/session events are intentionally not exposed: deferred-resolution events may carry the
 * concrete secret-bearing request needed by the engine and must remain inside playback wiring.
 */
class PlaybackSessionController internal constructor(
    val snapshot: StateFlow<PlaybackSnapshot>,
    private val dispatchCommand: suspend (PlaybackCommand) -> Unit,
    private val releaseSession: suspend () -> Unit,
) {
    constructor(session: PlaybackSession) : this(
        snapshot = session.snapshot,
        dispatchCommand = session::dispatch,
        releaseSession = session::release,
    )

    suspend fun tune(selection: ProviderPlaybackSelection, profile: SessionProfile) {
        dispatchCommand(PlaybackCommand.Tune(selection, profile))
    }

    /** Migration-only concrete entry; provider-backed UI should use the deferred overload. */
    suspend fun tune(request: PlaybackRequest, profile: SessionProfile) {
        dispatchCommand(PlaybackCommand.Tune(request, profile))
    }

    suspend fun zap(selection: ProviderPlaybackSelection, profile: SessionProfile) {
        dispatchCommand(PlaybackCommand.Zap(selection, profile))
    }

    /** Migration-only concrete entry; provider-backed UI should use the deferred overload. */
    suspend fun zap(request: PlaybackRequest, profile: SessionProfile) {
        dispatchCommand(PlaybackCommand.Zap(request, profile))
    }

    suspend fun pause() = dispatchCommand(PlaybackCommand.Pause)
    suspend fun resume() = dispatchCommand(PlaybackCommand.Resume)
    suspend fun retry() = dispatchCommand(PlaybackCommand.Retry)

    suspend fun changePreferences(preferences: PlaybackPreferences) {
        dispatchCommand(PlaybackCommand.PreferencesChanged(preferences))
    }

    suspend fun changeProfile(profile: SessionProfile) {
        dispatchCommand(PlaybackCommand.SessionProfileChanged(profile))
    }

    suspend fun surfaceAvailable() = dispatchCommand(PlaybackCommand.SurfaceAvailable)
    suspend fun surfaceUnavailable() = dispatchCommand(PlaybackCommand.SurfaceUnavailable)
    suspend fun stop() = dispatchCommand(PlaybackCommand.Stop)

    /** Returns only after the session's affirmative engine/provider release barrier completes. */
    suspend fun release() = releaseSession()
}
