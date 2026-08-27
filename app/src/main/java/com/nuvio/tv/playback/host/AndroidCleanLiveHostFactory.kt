package com.nuvio.tv.playback.host

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import com.nuvio.tv.playback.android.AndroidPlaybackLifecyclePort
import com.nuvio.tv.playback.android.output.AndroidPlaybackOutputController
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.mediasession.CleanMediaSessionMetadata
import com.nuvio.tv.playback.wiring.ProductionPlaybackSessionFactory
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Engine-neutral command facade shared by Android clean live surfaces. */
internal interface CleanLiveHost {
    val snapshot: StateFlow<PlaybackSnapshot>

    suspend fun tune(
        selection: ProviderPlaybackSelection,
        profile: SessionProfile,
        metadata: CleanMediaSessionMetadata,
    ): Long

    suspend fun zap(
        selection: ProviderPlaybackSelection,
        profile: SessionProfile,
        metadata: CleanMediaSessionMetadata,
    ): Long

    suspend fun pause()
    suspend fun resume()
    suspend fun retry()
    suspend fun changeProfile(profile: SessionProfile)
    suspend fun stop()
    suspend fun release()
}

/** Android ownership required to build one clean live host without exposing an engine API. */
internal class AndroidCleanLiveHostInput(
    val context: Context,
    val preferenceProfileId: PlaybackProfileId,
    val parentScope: CoroutineScope,
    val activity: Activity,
    val lifecycle: Lifecycle,
    val surfaceOwner: FrameLayout,
    val previewViewport: VideoDimensions? = null,
) {
    override fun toString(): String =
        "AndroidCleanLiveHostInput(profileBound=true, surfaceOwnerBound=true, " +
            "previewViewportKnown=${previewViewport != null})"
}

internal fun interface CleanLiveHostFactory {
    suspend fun create(input: AndroidCleanLiveHostInput): CleanLiveHost
}

/** Production Android composition shared by fullscreen now and the clean guide host later. */
internal class AndroidCleanLiveHostFactory @Inject constructor(
    private val sessionFactory: ProductionPlaybackSessionFactory,
) : CleanLiveHostFactory {
    override suspend fun create(input: AndroidCleanLiveHostInput): CleanLiveHost {
        val surfaces = withContext(Dispatchers.Main.immediate) {
            CleanLiveSurfaceCoordinator(
                owner = input.surfaceOwner,
                callbackScope = input.parentScope,
                constructibleModes = PRODUCTION_CONSTRUCTIBLE_SURFACE_MODES,
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
            previewViewport = input.previewViewport,
        )
        return AndroidCleanLiveHost(host)
    }

    private companion object {
        val PRODUCTION_CONSTRUCTIBLE_SURFACE_MODES = setOf(
            SurfaceMode.SURFACE_VIEW,
            SurfaceMode.TEXTURE_VIEW,
            SurfaceMode.NATIVE_EMBED,
            SurfaceMode.GPU_RENDER,
        )
    }
}

private class AndroidCleanLiveHost(
    private val host: CleanLivePlaybackHost,
) : CleanLiveHost {
    override val snapshot: StateFlow<PlaybackSnapshot> = host.snapshot

    override suspend fun tune(
        selection: ProviderPlaybackSelection,
        profile: SessionProfile,
        metadata: CleanMediaSessionMetadata,
    ): Long = host.tune(selection, profile, metadata)

    override suspend fun zap(
        selection: ProviderPlaybackSelection,
        profile: SessionProfile,
        metadata: CleanMediaSessionMetadata,
    ): Long = host.zap(selection, profile, metadata)

    override suspend fun pause() = host.pause()
    override suspend fun resume() = host.resume()
    override suspend fun retry() = host.retry()
    override suspend fun changeProfile(profile: SessionProfile) = host.changeProfile(profile)
    override suspend fun stop() = host.stop()
    override suspend fun release() = host.release()
}
