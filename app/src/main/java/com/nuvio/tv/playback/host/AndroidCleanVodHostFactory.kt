package com.nuvio.tv.playback.host

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import com.nuvio.tv.playback.android.AndroidPlaybackLifecyclePort
import com.nuvio.tv.playback.android.output.AndroidPlaybackOutputController
import com.nuvio.tv.playback.core.ExternalSubtitleId
import com.nuvio.tv.playback.core.DestinationExternalSubtitleRegistry
import com.nuvio.tv.playback.core.ExternalSubtitleRegistration
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackTrackId
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.mediasession.CleanMediaSessionMetadata
import com.nuvio.tv.playback.wiring.ProductionPlaybackSessionFactory
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Engine-neutral VOD command boundary consumed by the presentation bridge. */
internal interface CleanVodHost {
    val snapshot: StateFlow<PlaybackSnapshot>

    suspend fun tune(
        request: PlaybackRequest,
        startPositionMs: Long,
        metadata: CleanMediaSessionMetadata,
    ): Long

    suspend fun pause()
    suspend fun resume()
    suspend fun retry()
    suspend fun seekTo(positionMs: Long)
    suspend fun setPlaybackRate(rate: Float)
    suspend fun selectAudioTrack(trackId: PlaybackTrackId)
    suspend fun selectSubtitleTrack(trackId: PlaybackTrackId)
    suspend fun disableSubtitles()
    suspend fun attachExternalSubtitle(subtitleId: ExternalSubtitleId)
    fun registerExternalSubtitle(registration: ExternalSubtitleRegistration): ExternalSubtitleId
    suspend fun stop()
    suspend fun release()
}

internal data class AndroidCleanVodHostInput(
    val context: Context,
    val preferenceProfileId: PlaybackProfileId,
    val parentScope: CoroutineScope,
    val activity: Activity,
    val lifecycle: Lifecycle,
    val surfaceOwner: FrameLayout,
)

internal fun interface CleanVodHostFactory {
    suspend fun create(input: AndroidCleanVodHostInput): CleanVodHost
}

/** Creates one destination-scoped clean VOD owner without exposing either engine to the UI. */
internal class AndroidCleanVodHostFactory @Inject constructor(
    private val sessionFactory: ProductionPlaybackSessionFactory,
) : CleanVodHostFactory {
    override suspend fun create(input: AndroidCleanVodHostInput): CleanVodHost {
        val externalSubtitles = DestinationExternalSubtitleRegistry()
        val surfaces = withContext(Dispatchers.Main.immediate) {
            CleanLiveSurfaceCoordinator(
                owner = input.surfaceOwner,
                callbackScope = input.parentScope,
                constructibleModes = CONSTRUCTIBLE_MODES,
                secureMedia3SurfaceViewSupported = true,
            )
        }
        val host = CleanLivePlaybackHost.create(
            context = input.context,
            preferenceProfileId = input.preferenceProfileId,
            parentScope = input.parentScope,
            sessionFactory = sessionFactory,
            surfaces = surfaces,
            outputController = AndroidPlaybackOutputController(input.activity),
            lifecycle = AndroidPlaybackLifecyclePort(input.lifecycle),
            externalSubtitleResolver = externalSubtitles,
        )
        return AndroidCleanVodHost(host, externalSubtitles)
    }

    private companion object {
        val CONSTRUCTIBLE_MODES = setOf(
            SurfaceMode.SURFACE_VIEW,
            SurfaceMode.TEXTURE_VIEW,
            SurfaceMode.NATIVE_EMBED,
            SurfaceMode.GPU_RENDER,
        )
    }
}

private class AndroidCleanVodHost(
    private val host: CleanLivePlaybackHost,
    private val externalSubtitles: DestinationExternalSubtitleRegistry,
) : CleanVodHost {
    override val snapshot: StateFlow<PlaybackSnapshot> = host.snapshot

    override suspend fun tune(
        request: PlaybackRequest,
        startPositionMs: Long,
        metadata: CleanMediaSessionMetadata,
    ): Long = host.tuneVod(request, SessionProfile.FULLSCREEN, startPositionMs, metadata)

    override suspend fun pause() = host.pause()
    override suspend fun resume() = host.resume()
    override suspend fun retry() = host.retry()
    override suspend fun seekTo(positionMs: Long) = host.seekTo(positionMs)
    override suspend fun setPlaybackRate(rate: Float) = host.setPlaybackRate(rate)
    override suspend fun selectAudioTrack(trackId: PlaybackTrackId) = host.selectAudioTrack(trackId)
    override suspend fun selectSubtitleTrack(trackId: PlaybackTrackId) = host.selectSubtitleTrack(trackId)
    override suspend fun disableSubtitles() = host.disableSubtitles()
    override suspend fun attachExternalSubtitle(subtitleId: ExternalSubtitleId) =
        host.attachExternalSubtitle(subtitleId)
    override fun registerExternalSubtitle(
        registration: ExternalSubtitleRegistration,
    ): ExternalSubtitleId = externalSubtitles.register(registration)
    override suspend fun stop() = host.stop()
    override suspend fun release() {
        host.release()
        externalSubtitles.clear()
    }
}
