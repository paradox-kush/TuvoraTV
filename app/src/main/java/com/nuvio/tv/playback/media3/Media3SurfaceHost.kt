package com.nuvio.tv.playback.media3

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.playback.core.SurfaceMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A lifecycle-owned surface reservation. The adapter attaches and detaches the player explicitly. */
interface Media3SurfaceLease {
    val mode: SurfaceMode
    val secure: Boolean

    fun attach(player: ExoPlayer)
    /** True only when the renderer has acknowledged that it no longer owns this surface. */
    fun detach(player: ExoPlayer): Boolean

    /** Called only after affirmative player termination proves all renderer ownership ended. */
    fun confirmPlayerReleased()

    /** True only when the host no longer owns an attached player/surface relationship. */
    suspend fun release(): Boolean
}

fun interface Media3SurfaceHost {
    suspend fun acquire(mode: SurfaceMode, secure: Boolean): PlaybackResult<Media3SurfaceLease>
}

/**
 * Thin Android host for views owned by the UI lifecycle. SurfaceView is the normal path;
 * TextureView is used only when the already-selected graph requests it.
 */
class ViewMedia3SurfaceHost(
    private val surfaceView: () -> SurfaceView?,
    private val textureView: () -> TextureView?,
    private val viewDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val onReleasedView: (View) -> Unit = {},
) : Media3SurfaceHost {
    override suspend fun acquire(
        mode: SurfaceMode,
        secure: Boolean,
    ): PlaybackResult<Media3SurfaceLease> = withContext(viewDispatcher) {
        val lease = when (mode) {
            SurfaceMode.SURFACE_VIEW -> surfaceView()?.let { view ->
                SurfaceViewLease(view, secure, onReleasedView)
            }
            SurfaceMode.TEXTURE_VIEW -> {
                if (secure) null else textureView()?.let { view ->
                    TextureViewLease(view, onReleasedView)
                }
            }
            SurfaceMode.NATIVE_EMBED,
            SurfaceMode.GPU_RENDER,
            -> null
        }
        lease?.let { PlaybackResult.Success(it) }
            ?: PlaybackResult.Failure(surfaceFailure())
    }

    private class SurfaceViewLease(
        private val view: SurfaceView,
        override val secure: Boolean,
        private val onReleasedView: (View) -> Unit,
    ) : Media3SurfaceLease {
        override val mode: SurfaceMode = SurfaceMode.SURFACE_VIEW
        private var attached = false
        private var released = false

        override fun attach(player: ExoPlayer) {
            check(!released) { "Released surface lease cannot be attached" }
            view.setSecure(secure)
            player.setVideoSurfaceView(view)
            attached = true
        }

        override fun detach(player: ExoPlayer): Boolean {
            val acknowledged = player.clearVideoSurfaceWithResult()
            if (acknowledged) attached = false
            return acknowledged
        }

        override fun confirmPlayerReleased() {
            attached = false
        }

        override suspend fun release(): Boolean {
            val notify = synchronized(this) {
                if (attached) return false
                if (released) return true
                released = true
                true
            }
            if (notify) onReleasedView(view)
            return true
        }
    }

    private class TextureViewLease(
        private val view: TextureView,
        private val onReleasedView: (View) -> Unit,
    ) : Media3SurfaceLease {
        override val mode: SurfaceMode = SurfaceMode.TEXTURE_VIEW
        override val secure: Boolean = false
        private var attached = false
        private var released = false

        override fun attach(player: ExoPlayer) {
            check(!released) { "Released surface lease cannot be attached" }
            player.setVideoTextureView(view)
            attached = true
        }

        override fun detach(player: ExoPlayer): Boolean {
            val acknowledged = player.clearVideoSurfaceWithResult()
            if (acknowledged) attached = false
            return acknowledged
        }

        override fun confirmPlayerReleased() {
            attached = false
        }

        override suspend fun release(): Boolean {
            val notify = synchronized(this) {
                if (attached) return false
                if (released) return true
                released = true
                true
            }
            if (notify) onReleasedView(view)
            return true
        }
    }
}

internal fun surfaceFailure() = PlaybackFailure(
    code = FailureCode.SURFACE_LOST,
    domain = FailureDomain.VIDEO_RENDERER_SURFACE,
    phase = FailurePhase.SURFACE_ATTACHMENT,
    retryability = Retryability.HANDOFF_ELIGIBLE,
)
