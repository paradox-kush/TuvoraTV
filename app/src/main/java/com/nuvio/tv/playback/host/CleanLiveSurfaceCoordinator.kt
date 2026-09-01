package com.nuvio.tv.playback.host

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.playback.core.SurfaceCapabilities
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.media3.Media3SurfaceHost
import com.nuvio.tv.playback.media3.ViewMedia3SurfaceHost
import com.nuvio.tv.playback.mpv.MpvSurfaceHost
import com.nuvio.tv.playback.mpv.MpvSurfaceLease
import com.nuvio.tv.playback.ui.PlaybackSessionController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the one raw Android surface child used by a clean live-playback host.
 *
 * Construction deliberately precedes session composition. The host passes [media3SurfaceHost],
 * [mpvSurfaceHost], and [capabilities] into composition, binds the resulting controller exactly
 * once, then awaits [startHosting] before tune/zap. Engines remain the only attach/detach authority.
 */
internal class CleanLiveSurfaceCoordinator(
    private val owner: FrameLayout,
    private val callbackScope: CoroutineScope,
    constructibleModes: Set<SurfaceMode>,
    secureMedia3SurfaceViewSupported: Boolean,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val surfaceWaitTimeoutMs: Long = DEFAULT_SURFACE_WAIT_TIMEOUT_MS,
    private val awaitSurfaceValidity: suspend (View, Long) -> Boolean = ::awaitAndroidSurfaceValidity,
    private val mpvSurfaceFactory: (View) -> Surface? = ::androidMpvSurface,
) {
    init {
        require(owner.childCount == 0) { "Clean playback surface owner must start empty" }
        require(constructibleModes.isNotEmpty()) { "At least one surface mode must be constructible" }
        require(!secureMedia3SurfaceViewSupported || SurfaceMode.SURFACE_VIEW in constructibleModes) {
            "Secure Media3 output requires a constructible SurfaceView"
        }
        require(surfaceWaitTimeoutMs > 0) { "Surface wait timeout must be positive" }
    }

    private val modes = constructibleModes.toSet()
    private val ownershipMutex = Mutex()
    private val notificationMutex = Mutex()
    private val bindingLock = Any()
    @Volatile private var controller: PlaybackSessionController? = null
    @Volatile private var currentToken: Long? = null
    @Volatile private var controlledRemovalToken: Long? = null
    private var current: SurfaceSlot? = null
    private var nextToken = 1L
    private var hosting = false
    private var disposed = false
    private var reportedAvailable = false

    val capabilities: SurfaceCapabilities = SurfaceCapabilities(
        surfaceViewSupported = SurfaceMode.SURFACE_VIEW in modes,
        textureViewSupported = SurfaceMode.TEXTURE_VIEW in modes,
        nativeEmbedSupported = SurfaceMode.NATIVE_EMBED in modes,
        secureSurfaceSupported = secureMedia3SurfaceViewSupported && SurfaceMode.SURFACE_VIEW in modes,
        secureNativeEmbedSupported = false,
        gpuRenderingSupported = SurfaceMode.GPU_RENDER in modes,
        secureGpuRenderingSupported = false,
    )

    val media3SurfaceHost: Media3SurfaceHost = Media3SurfaceHost { mode, secure ->
        acquireMedia3(mode, secure)
    }

    val mpvSurfaceHost: MpvSurfaceHost = object : MpvSurfaceHost {
        override suspend fun acquire(mode: SurfaceMode, secure: Boolean): PlaybackResult<MpvSurfaceLease> =
            acquireMpv(mode, secure)
    }

    fun bindController(value: PlaybackSessionController): Boolean = synchronized(bindingLock) {
        if (controller != null || hosting || disposed) return@synchronized false
        controller = value
        true
    }

    suspend fun startHosting(): Boolean {
        if (controller == null) return false
        val started = withContext(mainDispatcher) {
            ownershipMutex.withLock {
                if (disposed) false else {
                    hosting = true
                    true
                }
            }
        }
        if (started) notifyAvailability(available = true, token = null)
        return started
    }

    suspend fun stopHosting(): Boolean {
        val stopped = withContext(mainDispatcher) {
            ownershipMutex.withLock {
                if (!hosting) false else {
                    hosting = false
                    true
                }
            }
        }
        if (stopped) notifyAvailability(available = false, token = null)
        return stopped
    }

    /** Must be called only after the session's affirmative release barrier has completed. */
    suspend fun disposeAfterSessionRelease(): Boolean = withContext(mainDispatcher) {
        ownershipMutex.withLock {
            val slot = current
            if (slot != null && !slot.released) return@withLock false
            slot?.let(::removeControlled)
            hosting = false
            disposed = true
            true
        }
    }

    private suspend fun acquireMedia3(
        mode: SurfaceMode,
        secure: Boolean,
    ) = withContext(mainDispatcher) {
        ownershipMutex.withLock {
            if (!canAcquire(mode) || mode !in MEDIA3_MODES) return@withLock failure()
            if (secure && (mode != SurfaceMode.SURFACE_VIEW || !capabilities.secureSurfaceSupported)) {
                return@withLock failure()
            }
            cleanupReleasedSlot()
            if (current != null) return@withLock failure()
            val slot = when (mode) {
                SurfaceMode.SURFACE_VIEW -> createSurfaceViewSlot(mode, secure)
                SurfaceMode.TEXTURE_VIEW -> createTextureViewSlot(mode)
                else -> error("validated Media3 mode")
            }
            // A release barrier can cancel an in-flight acquisition at any suspension point
            // (the surface-validity wait especially). An installed slot that never reached a
            // lease has no owner able to release it later, so any non-success exit — failure,
            // cancellation, or unexpected throw — must remove it here or the coordinator stays
            // occupied for the rest of the process and every later tune fails SURFACE_LOST.
            var leased = false
            try {
                if (!awaitValid(slot)) return@withLock failure()
                val result = ViewMedia3SurfaceHost(
                    surfaceView = { slot.view as? SurfaceView },
                    textureView = { slot.view as? TextureView },
                    viewDispatcher = mainDispatcher,
                    onReleasedView = { releasedView ->
                        if (releasedView === slot.view) markMedia3Released(slot)
                    },
                ).acquire(mode, secure)
                leased = result is PlaybackResult.Success
                result
            } finally {
                if (!leased) {
                    slot.released = true
                    removeControlled(slot)
                }
            }
        }
    }

    private suspend fun acquireMpv(
        mode: SurfaceMode,
        secure: Boolean,
    ): PlaybackResult<MpvSurfaceLease> = withContext(mainDispatcher) {
        ownershipMutex.withLock {
            if (secure || !canAcquire(mode) || mode !in MPV_MODES) return@withLock failure()
            cleanupReleasedSlot()
            if (current != null) return@withLock failure()
            val slot = when (mode) {
                // Direct mediacodec embedding targets a SurfaceView native window.
                SurfaceMode.NATIVE_EMBED -> createSurfaceViewSlot(mode, secure = false)
                // libmpv GPU output owns a Surface wrapping a TextureView SurfaceTexture.
                SurfaceMode.GPU_RENDER -> createTextureViewSlot(mode)
                else -> error("validated libmpv mode")
            }
            // Same cancellation-safety contract as the Media3 path: an installed slot that never
            // became a lease must never survive this call.
            var leased = false
            try {
                if (!awaitValid(slot)) return@withLock failure()
                val surface = mpvSurfaceFactory(slot.view)
                if (slot.view is TextureView) slot.ownedSurface = surface
                if (surface == null || !surface.isValid) return@withLock failure()
                leased = true
                PlaybackResult.Success(CoordinatorMpvLease(slot, surface))
            } finally {
                if (!leased) {
                    slot.released = true
                    removeControlled(slot)
                }
            }
        }
    }

    private fun canAcquire(mode: SurfaceMode): Boolean =
        controller != null && hosting && !disposed && mode in modes

    private fun createSurfaceViewSlot(mode: SurfaceMode, secure: Boolean): SurfaceSlot {
        val token = newToken()
        val view = SurfaceView(owner.context).apply {
            setSecure(secure)
            holder.addCallback(surfaceCallback(token))
        }
        return install(SurfaceSlot(token, mode, view))
    }

    private fun createTextureViewSlot(mode: SurfaceMode): SurfaceSlot {
        val token = newToken()
        val view = TextureView(owner.context).apply {
            surfaceTextureListener = textureListener(token)
        }
        return install(SurfaceSlot(token, mode, view))
    }

    private fun install(slot: SurfaceSlot): SurfaceSlot {
        check(current == null && owner.childCount == 0) { "Only one clean playback surface may exist" }
        current = slot
        currentToken = slot.token
        owner.addView(
            slot.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return slot
    }

    private suspend fun awaitValid(slot: SurfaceSlot): Boolean =
        awaitSurfaceValidity(slot.view, surfaceWaitTimeoutMs)

    private fun cleanupReleasedSlot() {
        current?.takeIf { it.released }?.let(::removeControlled)
    }

    private fun removeControlled(slot: SurfaceSlot) {
        if (current !== slot) return
        controlledRemovalToken = slot.token
        owner.removeView(slot.view)
        slot.ownedSurface?.release()
        slot.ownedSurface = null
        current = null
        currentToken = null
        controlledRemovalToken = null
    }

    private fun markMedia3Released(slot: SurfaceSlot) = markReleased(slot)

    private fun markMpvReleased(slot: SurfaceSlot) = markReleased(slot)

    private fun markReleased(slot: SurfaceSlot) {
        slot.released = true
        callbackScope.launch(mainDispatcher) {
            ownershipMutex.withLock {
                if (current !== slot || !slot.released) return@withLock
                val replacementMayBeCreated = slot.unexpectedlyLost && hosting && !disposed
                removeControlled(slot)
                // SurfaceAvailable means the UI-owned host is ready to construct a replacement;
                // the engine attachment path still acquires the concrete Surface/View. Without
                // this edge an actually removed child leaves the reducer in ATTACHING_SURFACE
                // forever because no object remains that can publish another framework callback.
                if (replacementMayBeCreated) notifyAvailability(available = true, token = null)
            }
        }
    }

    private fun surfaceCallback(token: Long) = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = enqueueAvailability(token, true)
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
        override fun surfaceDestroyed(holder: SurfaceHolder) = enqueueAvailability(token, false)
    }

    private fun textureListener(token: Long) = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) =
            enqueueAvailability(token, true)

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            enqueueAvailability(token, false)
            return true
        }
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private fun enqueueAvailability(token: Long, available: Boolean) {
        if (token != currentToken || token == controlledRemovalToken || !hosting) return
        if (!available) current?.takeIf { it.token == token }?.unexpectedlyLost = true
        callbackScope.launch { notifyAvailability(available, token) }
    }

    private suspend fun notifyAvailability(available: Boolean, token: Long?) {
        notificationMutex.withLock {
            val bound = controller ?: return@withLock
            if (token != null && (token != currentToken || token == controlledRemovalToken || !hosting)) {
                return@withLock
            }
            if (reportedAvailable == available) return@withLock
            if (available) bound.surfaceAvailable() else bound.surfaceUnavailable()
            reportedAvailable = available
        }
    }

    private fun newToken(): Long {
        check(nextToken < Long.MAX_VALUE) { "Surface token exhausted" }
        return nextToken++
    }

    private inner class CoordinatorMpvLease(
        private val slot: SurfaceSlot,
        override val surface: Surface,
    ) : MpvSurfaceLease {
        override val mode: SurfaceMode = slot.mode
        override val secure: Boolean = false
        private var state = MpvLeaseState.ACQUIRED

        @Synchronized override fun markAttached() {
            check(state == MpvLeaseState.ACQUIRED)
            state = MpvLeaseState.ATTACHED
            slot.attached = true
        }

        @Synchronized override fun confirmDetached() {
            if (state == MpvLeaseState.ATTACHED) state = MpvLeaseState.DETACHED
            slot.attached = false
        }

        @Synchronized override fun confirmCoreDestroyed() {
            if (state != MpvLeaseState.RELEASED) state = MpvLeaseState.CORE_DESTROYED
            slot.attached = false
        }

        @Synchronized override fun release(): Boolean = when (state) {
            MpvLeaseState.ATTACHED -> false
            MpvLeaseState.RELEASED -> true
            MpvLeaseState.ACQUIRED, MpvLeaseState.DETACHED, MpvLeaseState.CORE_DESTROYED -> {
                state = MpvLeaseState.RELEASED
                markMpvReleased(slot)
                true
            }
        }
    }

    private data class SurfaceSlot(
        val token: Long,
        val mode: SurfaceMode,
        val view: View,
        @Volatile var attached: Boolean = false,
        @Volatile var released: Boolean = false,
        @Volatile var unexpectedlyLost: Boolean = false,
        var ownedSurface: Surface? = null,
    )

    private fun failure(): PlaybackResult.Failure = PlaybackResult.Failure(
        PlaybackFailure(
            code = FailureCode.SURFACE_LOST,
            domain = FailureDomain.VIDEO_RENDERER_SURFACE,
            phase = FailurePhase.SURFACE_ATTACHMENT,
            retryability = Retryability.HANDOFF_ELIGIBLE,
        ),
    )

    private companion object {
        const val DEFAULT_SURFACE_WAIT_TIMEOUT_MS = 3_000L
        const val SURFACE_POLL_INTERVAL_MS = 16L
        val MEDIA3_MODES = setOf(SurfaceMode.SURFACE_VIEW, SurfaceMode.TEXTURE_VIEW)
        val MPV_MODES = setOf(SurfaceMode.NATIVE_EMBED, SurfaceMode.GPU_RENDER)
    }

    private enum class MpvLeaseState { ACQUIRED, ATTACHED, DETACHED, CORE_DESTROYED, RELEASED }
}

private suspend fun awaitAndroidSurfaceValidity(view: View, timeoutMs: Long): Boolean {
    if (view.hasValidSurface()) return true
    return when (view) {
        // SurfaceHolder supports multiple callbacks, so we can suspend on the platform's own
        // surfaceCreated push instead of spinning a 16ms poll through the zap-contended window.
        is SurfaceView -> withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                val callback = object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                        view.holder.removeCallback(this)
                        if (continuation.isActive) continuation.resume(Unit) {}
                    }

                    override fun surfaceChanged(
                        holder: android.view.SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = Unit
                }
                view.holder.addCallback(callback)
                continuation.invokeOnCancellation { view.holder.removeCallback(callback) }
                // The surface may have become valid between the fast-path check and callback
                // registration; never wait on a push that already happened.
                if (view.hasValidSurface()) {
                    view.holder.removeCallback(callback)
                    if (continuation.isActive) continuation.resume(Unit) {}
                }
            }
            true
        } ?: false
        // TextureView exposes a SINGLE surfaceTextureListener slot and the coordinator already
        // owns it for availability reporting — installing a second listener here would clobber
        // it. Polling stays for this type only.
        else -> withTimeoutOrNull(timeoutMs) {
            while (!view.hasValidSurface()) delay(16L)
            true
        } ?: false
    }
}

private fun View.hasValidSurface(): Boolean = when (this) {
    is SurfaceView -> holder.surface.isValid
    is TextureView -> isAvailable && surfaceTexture != null
    else -> false
}

private fun androidMpvSurface(view: View): Surface? = when (view) {
    is SurfaceView -> view.holder.surface.takeIf(Surface::isValid)
    is TextureView -> view.surfaceTexture?.let { texture ->
        Surface(texture).let { surface ->
            if (surface.isValid) {
                surface
            } else {
                surface.release()
                null
            }
        }
    }
    else -> null
}
