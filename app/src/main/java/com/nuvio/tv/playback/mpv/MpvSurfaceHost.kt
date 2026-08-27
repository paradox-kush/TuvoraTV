package com.nuvio.tv.playback.mpv

import android.view.Surface
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

internal interface MpvSurfaceHost {
    suspend fun acquire(mode: SurfaceMode, secure: Boolean): PlaybackResult<MpvSurfaceLease>
}

internal interface MpvSurfaceLease {
    val mode: SurfaceMode
    val secure: Boolean
    val surface: Surface
    fun markAttached()
    fun confirmDetached()
    fun confirmCoreDestroyed()
    /** Never succeeds merely because a detach request was issued. */
    fun release(): Boolean
}

internal class ViewMpvSurfaceHost(
    private val nativeEmbedSurface: () -> Surface?,
    private val gpuRenderSurface: () -> Surface?,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : MpvSurfaceHost {
    override suspend fun acquire(
        mode: SurfaceMode,
        secure: Boolean,
    ): PlaybackResult<MpvSurfaceLease> = withContext(mainDispatcher) {
        if (secure || mode !in setOf(SurfaceMode.NATIVE_EMBED, SurfaceMode.GPU_RENDER)) {
            return@withContext surfaceFailure()
        }
        val surface = when (mode) {
            SurfaceMode.NATIVE_EMBED -> nativeEmbedSurface()
            SurfaceMode.GPU_RENDER -> gpuRenderSurface()
            else -> null
        }
        if (surface == null || !surface.isValid) {
            surfaceFailure()
        } else {
            PlaybackResult.Success(ProofMpvSurfaceLease(mode, surface))
        }
    }
}

private class ProofMpvSurfaceLease(
    override val mode: SurfaceMode,
    override val surface: Surface,
) : MpvSurfaceLease {
    override val secure: Boolean = false
    private enum class State { ACQUIRED, ATTACHED, DETACHED, CORE_DESTROYED, RELEASED }
    private var state = State.ACQUIRED

    @Synchronized override fun markAttached() {
        check(state == State.ACQUIRED) { "Surface lease cannot be attached from $state" }
        state = State.ATTACHED
    }

    @Synchronized override fun confirmDetached() {
        if (state == State.ATTACHED) state = State.DETACHED
    }

    @Synchronized override fun confirmCoreDestroyed() {
        if (state != State.RELEASED) state = State.CORE_DESTROYED
    }

    @Synchronized override fun release(): Boolean = when (state) {
        State.ACQUIRED, State.DETACHED, State.CORE_DESTROYED -> {
            state = State.RELEASED
            true
        }
        State.RELEASED -> true
        State.ATTACHED -> false
    }
}

private fun surfaceFailure(): PlaybackResult.Failure = PlaybackResult.Failure(
    PlaybackFailure(
        code = FailureCode.SURFACE_LOST,
        domain = FailureDomain.VIDEO_RENDERER_SURFACE,
        phase = FailurePhase.SURFACE_ATTACHMENT,
        retryability = Retryability.HANDOFF_ELIGIBLE,
    ),
)
