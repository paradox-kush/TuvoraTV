package com.nuvio.tv.playback.media3

import android.content.Context
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.SurfaceMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** PlayerView owns presentation binding; the engine retains affirmative teardown authority. */
internal class PlayerViewMedia3SurfaceHost(
    private val view: PlayerView,
    private val viewDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val onReleased: () -> Unit = {},
) : Media3SurfaceHost {
    override suspend fun acquire(mode: SurfaceMode, secure: Boolean): PlaybackResult<Media3SurfaceLease> =
        withContext(viewDispatcher) {
            val surface = view.videoSurfaceView as? SurfaceView
            if (mode != SurfaceMode.SURFACE_VIEW || surface == null || view.player != null) {
                return@withContext PlaybackResult.Failure(surfaceFailure())
            }
            surface.setSecure(secure)
            PlaybackResult.Success(Lease(secure))
        }

    private inner class Lease(override val secure: Boolean) : Media3SurfaceLease {
        override val mode = SurfaceMode.SURFACE_VIEW
        private var attachedPlayer: ExoPlayer? = null
        @Volatile private var released = false
        @Volatile private var playerTerminated = false

        override fun attach(player: ExoPlayer) {
            clearTerminatedBinding()
            check(!released && attachedPlayer == null && view.player == null)
            // Record possible ownership before invoking a setter that may partially succeed.
            attachedPlayer = player
            view.player = player
        }

        override fun detach(player: ExoPlayer): Boolean {
            if (attachedPlayer == null) return true
            if (attachedPlayer !== player || view.player !== player) return false
            // setPlayer(null) alone supplies no renderer acknowledgement in the pinned fork.
            if (!player.clearVideoSurfaceWithResult()) return false
            view.player = null
            attachedPlayer = null
            return true
        }

        override fun confirmPlayerReleased() {
            // The session can acknowledge on its own lane. View cleanup stays on the UI lane.
            playerTerminated = true
        }

        override fun abandon() {
            // The stuck player will never be called again. Mark the binding dead (the UI lane clears
            // the view later) and report the lease released so the coordinator frees the slot; the
            // slot's view is removed, so the stale view.player binding cannot be reached again.
            playerTerminated = true
            if (!released) {
                released = true
                onReleased()
            }
        }

        override suspend fun release(): Boolean = withContext(viewDispatcher) {
            clearTerminatedBinding()
            if (attachedPlayer != null || view.player != null) return@withContext false
            if (!released) {
                released = true
                onReleased()
            }
            true
        }

        private fun clearTerminatedBinding() {
            if (!playerTerminated) return
            if (view.player === attachedPlayer) view.player = null
            attachedPlayer = null
            playerTerminated = false
        }
    }
}

/** What the surface coordinator holds: a view and its video surface, with no Media3 type in its API. */
internal class PlayerViewPresentation private constructor(private val playerView: PlayerView) {
    val view: View get() = playerView
    val videoSurface: SurfaceView = checkNotNull(playerView.videoSurfaceView as? SurfaceView)

    fun host(viewDispatcher: CoroutineDispatcher, onReleased: () -> Unit): Media3SurfaceHost =
        PlayerViewMedia3SurfaceHost(playerView, viewDispatcher, onReleased)

    companion object {
        fun create(context: Context): PlayerViewPresentation = PlayerViewPresentation(
            PlayerView(context).apply {
                useController = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                keepScreenOn = false // Session/output policy owns this, as in the VOD screen.
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setKeepContentOnPlayerReset(true)
                // Preserve the current aspect/zoom policy without applying a second aspect fit.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        )
    }
}
