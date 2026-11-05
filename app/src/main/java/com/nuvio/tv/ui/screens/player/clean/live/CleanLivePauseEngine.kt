package com.nuvio.tv.ui.screens.player.clean.live

import com.nuvio.tv.core.contracts.LivePauseEngine
import com.nuvio.tv.core.contracts.LivePauseEngineFacts
import com.nuvio.tv.core.contracts.LivePauseWindow
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.host.CleanLiveHost
import com.nuvio.tv.playback.mediasession.CleanMediaSessionMetadata
import kotlinx.coroutines.CancellationException

/**
 * The clean live host seen as a [LivePauseEngine]. One per player instance. Every call is a command on
 * the host's own serialized lane (so mpv is reached only through its control queue, never on Main), and
 * the window is read from the session's published snapshot, not from the engine.
 */
internal class CleanLivePauseEngine(
    private val host: CleanLiveHost,
    private val metadata: () -> CleanMediaSessionMetadata,
    private val retuneLive: suspend () -> Unit,
    /** The provider archive request for the channel on screen, when it has one (Xtream tv_archive). */
    private val catchUpFor: (startEpochMs: Long, endEpochMs: Long) -> ProviderPlaybackSelection? = { _, _ -> null },
    private val hasProviderArchive: () -> Boolean = { false },
) : LivePauseEngine {
    // The controller's actor is the only writer and reader of this; the host calls are serialized there.
    private var localBuffer = false
    private var replaying = false

    override suspend fun pause() = host.pause()
    override suspend fun play() = host.resume()
    override suspend fun seekTo(positionMs: Long) = host.seekTo(positionMs)

    override fun window(): LivePauseWindow? {
        val snapshot = host.snapshot.value
        if (localBuffer || replaying) {
            // The local playlist (or an archive replay) reports its own window: 0..duration.
            val duration = snapshot.durationMs ?: return null
            return LivePauseWindow(0L, duration, snapshot.positionMs.coerceIn(0L, duration))
        }
        val start = snapshot.seekableStartMs ?: return null
        val end = snapshot.seekableEndMs ?: return null
        return LivePauseWindow(start, end, snapshot.positionMs)
    }

    override suspend fun setLiveCacheBudget(maxBytes: Long, maxBackBytes: Long, cacheSecs: Long) =
        host.setLiveCacheBudget(maxBytes, maxBackBytes, cacheSecs)

    override suspend fun releaseLiveSource() = host.stop()

    override suspend fun playLocalBuffer(playlistUri: String): Boolean = try {
        host.tuneLocalBuffer(playlistUri, metadata())
        // Prepared paused at the window start: the viewer chose to pause.
        host.pause()
        localBuffer = true
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    override suspend fun retune() {
        localBuffer = false
        replaying = false
        retuneLive()
    }

    override suspend fun openCatchUp(startEpochMs: Long, endEpochMs: Long): Boolean {
        val selection = catchUpFor(startEpochMs, endEpochMs) ?: return false
        return try {
            host.tuneCatchUp(selection, SessionProfile.FULLSCREEN, metadata())
            replaying = true
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override fun facts(): LivePauseEngineFacts {
        val engine = host.snapshot.value.graph?.engine
        return LivePauseEngineFacts(
            isLive = !localBuffer && !replaying,
            isCatchUpPlayback = replaying,
            hasProviderArchive = hasProviderArchive(),
            diskBuffer = engine == EngineType.MEDIA3,
            engineBackBuffer = engine == EngineType.LIBMPV,
            bitrateBps = null,
            programmeEndEpochMs = null,
        )
    }
}
