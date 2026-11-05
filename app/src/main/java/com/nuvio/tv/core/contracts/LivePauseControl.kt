package com.nuvio.tv.core.contracts

import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Neutral live pause / rewind ports, so the clean live player never names the fork's core.livepause
 * package (ArchitectureTest firewall). The implementation ([LivePauseControlFactory]) is Hilt-bound on a
 * fork path; the player supplies a [LivePauseEngine] over its own host and renders a [LivePauseView].
 */

/** Where the viewer is inside the time-shifted stream, in ms from the window start. */
data class LivePauseWindow(val startMs: Long, val endMs: Long, val positionMs: Long)

/** What the controller needs from the player (the clean live host adapts to this; fakes in tests). Every call is the platform's own, off the main thread where required. */
interface LivePauseEngine {
    suspend fun pause()
    suspend fun play()
    suspend fun seekTo(positionMs: Long)

    /** A non-blocking read of the shadow state (mpv: demuxer-cache-state; local buffer: its HLS window). */
    fun window(): LivePauseWindow?

    /** mpv only: raise / restore the demuxer caches at runtime. */
    suspend fun setLiveCacheBudget(maxBytes: Long, maxBackBytes: Long, cacheSecs: Long)

    /** Stops the live source so the provider connection ends (the picture stays frozen). */
    suspend fun releaseLiveSource()

    /** Plays the local buffer, prepared paused at the window start. False = the player refused it. */
    suspend fun playLocalBuffer(playlistUri: String): Boolean

    /** Opens the channel again through the normal path (re-mints a single-use link). */
    suspend fun retune()

    /** Opens the provider's archive at [startEpochMs] through the existing catch-up path. */
    suspend fun openCatchUp(startEpochMs: Long, endEpochMs: Long): Boolean

    /** What the player can do and what it is playing, read when a pause starts. */
    fun facts(): LivePauseEngineFacts
}


/** What the player can do right now and what it is playing. */
data class LivePauseEngineFacts(
    val isLive: Boolean,
    val isCatchUpPlayback: Boolean,
    val hasProviderArchive: Boolean,
    /** ExoPlayer is active: it can play a local buffer. */
    val diskBuffer: Boolean,
    /** mpv is active: it can hold a back buffer and report a seekable window. */
    val engineBackBuffer: Boolean,
    val bitrateBps: Long?,
    val programmeEndEpochMs: Long?,
)

enum class LivePausePhase { OFF, PREPARING, SHIFTED, FAILED }

enum class LivePauseProblem { NO_SPACE, PROVIDER_REFUSED, STALLED, SEQUENCE_RESET, UNSUPPORTED_STREAM, TIMED_OUT }

/** What the live UI renders for pause / rewind. */
data class LivePauseView(
    val phase: LivePausePhase = LivePausePhase.OFF,
    val paused: Boolean = false,
    val behindLiveMs: Long = 0L,
    val windowStartMs: Long = 0L,
    val windowEndMs: Long = 0L,
    val positionMs: Long = 0L,
    val bufferFull: Boolean = false,
    val problem: LivePauseProblem? = null,
    /** Preparing only: when the wait ends as Failed whatever the work does (BoundedLoad). */
    val preparingDeadlineAtMs: Long = 0L,
) {
    companion object {
        val OFF = LivePauseView()
    }
}

/** One per player instance. */
interface LivePauseControl {
    val view: StateFlow<LivePauseView>
    fun onPause()
    fun onPlay()
    fun seekInWindow(positionMs: Long)
    fun goLive()
    fun retry()
    fun onChannelChanged()
    fun onLiveStarted()
    fun dispose()
}

class LivePauseControlRequest(
    val instanceId: String,
    val scope: CoroutineScope,
    val engine: LivePauseEngine,
    val profileId: PlaybackProfileId,
    /** The live channel on screen; links are minted from it. */
    val selection: ProviderPlaybackSelection,
)

fun interface LivePauseControlFactory {
    /** Null = live pause is not offered here. */
    fun create(request: LivePauseControlRequest): LivePauseControl?
}
