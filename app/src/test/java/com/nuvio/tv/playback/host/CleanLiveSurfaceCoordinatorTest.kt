package com.nuvio.tv.playback.host

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.playback.core.PlaybackCommand
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.media3.Media3SurfaceLease
import com.nuvio.tv.playback.mpv.MpvSurfaceLease
import com.nuvio.tv.playback.ui.PlaybackSessionController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CleanLiveSurfaceCoordinatorTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    @Test
    fun `capabilities advertise only explicitly constructible truthful modes`() {
        val coordinator = coordinator(
            modes = setOf(SurfaceMode.SURFACE_VIEW, SurfaceMode.NATIVE_EMBED),
            secureSurfaceView = true,
        )

        assertTrue(coordinator.capabilities.surfaceViewSupported)
        assertFalse(coordinator.capabilities.textureViewSupported)
        assertTrue(coordinator.capabilities.nativeEmbedSupported)
        assertFalse(coordinator.capabilities.gpuRenderingSupported)
        assertTrue(coordinator.capabilities.secureSurfaceSupported)
        assertFalse(coordinator.capabilities.secureNativeEmbedSupported)
        assertFalse(coordinator.capabilities.secureGpuRenderingSupported)
    }

    @Test
    fun `construction rejects empty modes and impossible secure claim`() {
        assertIllegalArgument {
            coordinator(modes = emptySet())
        }
        assertIllegalArgument {
            coordinator(
                modes = setOf(SurfaceMode.TEXTURE_VIEW),
                secureSurfaceView = true,
            )
        }
        assertIllegalArgument {
            val occupiedOwner = owner().apply {
                addView(android.view.View(context))
            }
            coordinator(
                owner = occupiedOwner,
                modes = setOf(SurfaceMode.SURFACE_VIEW),
            )
        }
    }

    @Test
    fun `engine hosts reject cross-engine modes even when coordinator can construct them`() = runTest {
        val coordinator = coordinator(modes = SurfaceMode.entries.toSet())
        start(coordinator)

        assertFailure(coordinator.media3SurfaceHost.acquire(SurfaceMode.NATIVE_EMBED, secure = false))
        assertFailure(coordinator.media3SurfaceHost.acquire(SurfaceMode.GPU_RENDER, secure = false))
        assertFailure(coordinator.mpvSurfaceHost.acquire(SurfaceMode.SURFACE_VIEW, secure = false))
        assertFailure(coordinator.mpvSurfaceHost.acquire(SurfaceMode.TEXTURE_VIEW, secure = false))
        assertFailure(coordinator.mpvSurfaceHost.acquire(SurfaceMode.NATIVE_EMBED, secure = true))
    }

    @Test
    fun `binding is one shot and hosting fails closed before binding`() = runTest {
        val commands = mutableListOf<PlaybackCommand>()
        val coordinator = coordinator(modes = setOf(SurfaceMode.SURFACE_VIEW))

        assertFalse(coordinator.startHosting())
        assertFailure(coordinator.media3SurfaceHost.acquire(SurfaceMode.SURFACE_VIEW, secure = false))
        assertTrue(coordinator.bindController(controller(commands)))
        assertFalse(coordinator.bindController(controller(mutableListOf())))
        assertTrue(coordinator.startHosting())
        assertEquals(listOf(PlaybackCommand.SurfaceAvailable), commands)
    }

    @Test
    fun `Media3 owns exactly one requested raw child at a time`() = runTest {
        val owner = owner()
        val coordinator = coordinator(
            owner = owner,
            modes = setOf(SurfaceMode.SURFACE_VIEW, SurfaceMode.TEXTURE_VIEW),
        )
        start(coordinator)

        val first = media3Lease(coordinator, SurfaceMode.SURFACE_VIEW)
        assertEquals(1, owner.childCount)
        assertTrue(owner.getChildAt(0) is SurfaceView)
        assertFailure(coordinator.media3SurfaceHost.acquire(SurfaceMode.TEXTURE_VIEW, secure = false))
        assertEquals(1, owner.childCount)

        assertTrue(first.release())
        val second = media3Lease(coordinator, SurfaceMode.TEXTURE_VIEW)
        assertEquals(1, owner.childCount)
        assertTrue(owner.getChildAt(0) is TextureView)
        assertTrue(second.release())
        assertEquals(0, owner.childCount)
    }

    @Test
    fun `Media3 lease reports exact requested secure fact`() = runTest {
        val coordinator = coordinator(
            modes = setOf(SurfaceMode.SURFACE_VIEW, SurfaceMode.TEXTURE_VIEW),
            secureSurfaceView = true,
        )
        start(coordinator)

        val nonSecure = media3Lease(coordinator, SurfaceMode.SURFACE_VIEW, secure = false)
        assertFalse(nonSecure.secure)
        assertTrue(nonSecure.release())

        val secure = media3Lease(coordinator, SurfaceMode.SURFACE_VIEW, secure = true)
        assertTrue(secure.secure)
        assertTrue(secure.release())
        assertFailure(coordinator.media3SurfaceHost.acquire(SurfaceMode.TEXTURE_VIEW, secure = true))
    }

    @Test
    fun `Media3 detach requires affirmative renderer release proof`() = runTest {
        val owner = owner()
        val coordinator = coordinator(owner, setOf(SurfaceMode.SURFACE_VIEW))
        start(coordinator)
        val lease = media3Lease(coordinator, SurfaceMode.SURFACE_VIEW)
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.clearVideoSurfaceWithResult() } returns false

        lease.attach(player)
        assertFalse(lease.detach(player))
        assertFalse(lease.release())
        assertEquals(1, owner.childCount)
        verify(exactly = 1) { player.clearVideoSurfaceWithResult() }

        lease.confirmPlayerReleased()
        assertTrue(lease.release())
        assertEquals(0, owner.childCount)
    }

    @Test
    fun `affirmative Media3 detach permits surface release`() = runTest {
        val owner = owner()
        val coordinator = coordinator(owner, setOf(SurfaceMode.SURFACE_VIEW))
        start(coordinator)
        val lease = media3Lease(coordinator, SurfaceMode.SURFACE_VIEW)
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.clearVideoSurfaceWithResult() } returns true

        lease.attach(player)
        assertTrue(lease.detach(player))
        assertTrue(lease.release())
        assertEquals(0, owner.childCount)
    }

    @Test
    fun `libmpv native embed and GPU render have distinct Android backing`() = runTest {
        val owner = owner()
        val surface = validSurface()
        val coordinator = coordinator(
            owner = owner,
            modes = setOf(SurfaceMode.NATIVE_EMBED, SurfaceMode.GPU_RENDER),
            mpvSurfaceFactory = { surface },
        )
        start(coordinator)

        val native = mpvLease(coordinator, SurfaceMode.NATIVE_EMBED)
        assertTrue(owner.getChildAt(0) is SurfaceView)
        assertEquals(SurfaceMode.NATIVE_EMBED, native.mode)
        assertFalse(native.secure)
        assertTrue(native.release())
        drainMain()
        verify(exactly = 0) { surface.release() }

        val gpu = mpvLease(coordinator, SurfaceMode.GPU_RENDER)
        assertTrue(owner.getChildAt(0) is TextureView)
        assertEquals(SurfaceMode.GPU_RENDER, gpu.mode)
        assertFalse(gpu.secure)
        assertTrue(gpu.release())
        drainMain()
        verify(exactly = 1) { surface.release() }
        assertEquals(0, owner.childCount)
    }

    @Test
    fun `libmpv attached lease cannot release without detach proof`() = runTest {
        val coordinator = coordinator(
            modes = setOf(SurfaceMode.NATIVE_EMBED),
            mpvSurfaceFactory = { validSurface() },
        )
        start(coordinator)
        val lease = mpvLease(coordinator, SurfaceMode.NATIVE_EMBED)

        lease.markAttached()
        assertFalse(lease.release())
        lease.confirmDetached()
        assertTrue(lease.release())
    }

    @Test
    fun `stale surface callbacks cannot report unavailable for replacement`() = runTest {
        val commands = mutableListOf<PlaybackCommand>()
        val owner = owner()
        val coordinator = coordinator(owner, setOf(SurfaceMode.TEXTURE_VIEW))
        start(coordinator, commands)
        val oldLease = media3Lease(coordinator, SurfaceMode.TEXTURE_VIEW)
        val oldListener = (owner.getChildAt(0) as TextureView).surfaceTextureListener
        assertTrue(oldLease.release())
        media3Lease(coordinator, SurfaceMode.TEXTURE_VIEW)

        oldListener!!.onSurfaceTextureDestroyed(mockk<SurfaceTexture>())
        drainMain()

        assertEquals(listOf(PlaybackCommand.SurfaceAvailable), commands)
        assertEquals(1, owner.childCount)
    }

    @Test
    fun `actually removed surface publishes host readiness and replacement recovers`() = runTest {
        val commands = mutableListOf<PlaybackCommand>()
        val owner = owner()
        val coordinator = coordinator(owner, setOf(SurfaceMode.TEXTURE_VIEW))
        start(coordinator, commands)
        val lease = media3Lease(coordinator, SurfaceMode.TEXTURE_VIEW)
        val oldView = owner.getChildAt(0) as TextureView
        val listener = oldView.surfaceTextureListener!!
        val texture = mockk<SurfaceTexture>()

        listener.onSurfaceTextureDestroyed(texture)
        drainMain()
        assertTrue(lease.release())
        drainMain()

        assertEquals(0, owner.childCount)
        val replacement = media3Lease(coordinator, SurfaceMode.TEXTURE_VIEW)
        val newView = owner.getChildAt(0)
        assertTrue(newView !== oldView)
        assertTrue(replacement.release())

        assertEquals(
            listOf(
                PlaybackCommand.SurfaceAvailable,
                PlaybackCommand.SurfaceUnavailable,
                PlaybackCommand.SurfaceAvailable,
            ),
            commands,
        )
    }

    @Test
    fun `controlled surface release does not emit surface unavailable`() = runTest {
        val commands = mutableListOf<PlaybackCommand>()
        val coordinator = coordinator(modes = setOf(SurfaceMode.SURFACE_VIEW))
        start(coordinator, commands)
        val lease = media3Lease(coordinator, SurfaceMode.SURFACE_VIEW)

        assertTrue(lease.release())
        drainMain()

        assertEquals(listOf(PlaybackCommand.SurfaceAvailable), commands)
    }

    @Test
    fun `surface wait is bounded and failed acquisition removes child`() = runTest {
        var observedTimeout = -1L
        val owner = owner()
        val coordinator = coordinator(
            owner = owner,
            modes = setOf(SurfaceMode.SURFACE_VIEW),
            surfaceWaitTimeoutMs = 41L,
            awaitSurfaceValidity = { _, timeout ->
                observedTimeout = timeout
                false
            },
        )
        start(coordinator)

        assertFailure(coordinator.media3SurfaceHost.acquire(SurfaceMode.SURFACE_VIEW, secure = false))
        assertEquals(41L, observedTimeout)
        assertEquals(0, owner.childCount)
    }

    @Test
    fun `dispose fails closed while renderer still owns surface`() = runTest {
        val coordinator = coordinator(modes = setOf(SurfaceMode.SURFACE_VIEW))
        start(coordinator)
        val lease = media3Lease(coordinator, SurfaceMode.SURFACE_VIEW)
        val player = mockk<ExoPlayer>(relaxed = true)
        lease.attach(player)

        assertFalse(coordinator.disposeAfterSessionRelease())
        lease.confirmPlayerReleased()
        assertTrue(lease.release())
        assertTrue(coordinator.disposeAfterSessionRelease())
        assertFalse(coordinator.startHosting())
    }

    private fun coordinator(
        owner: FrameLayout = owner(),
        modes: Set<SurfaceMode>,
        secureSurfaceView: Boolean = false,
        surfaceWaitTimeoutMs: Long = 100L,
        awaitSurfaceValidity: suspend (android.view.View, Long) -> Boolean = { _, _ -> true },
        mpvSurfaceFactory: (android.view.View) -> Surface? = { validSurface() },
    ) = CleanLiveSurfaceCoordinator(
        owner = owner,
        callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add),
        constructibleModes = modes,
        secureMedia3SurfaceViewSupported = secureSurfaceView,
        mainDispatcher = Dispatchers.Unconfined,
        surfaceWaitTimeoutMs = surfaceWaitTimeoutMs,
        awaitSurfaceValidity = awaitSurfaceValidity,
        mpvSurfaceFactory = mpvSurfaceFactory,
    )

    private fun owner() = FrameLayout(RuntimeEnvironment.getApplication())

    private fun controller(commands: MutableList<PlaybackCommand>) = PlaybackSessionController(
        snapshot = MutableStateFlow(PlaybackSnapshot()),
        dispatchCommand = commands::add,
        releaseSession = {},
    )

    private suspend fun start(
        coordinator: CleanLiveSurfaceCoordinator,
        commands: MutableList<PlaybackCommand> = mutableListOf(),
    ) {
        assertTrue(coordinator.bindController(controller(commands)))
        assertTrue(coordinator.startHosting())
    }

    private suspend fun media3Lease(
        coordinator: CleanLiveSurfaceCoordinator,
        mode: SurfaceMode,
        secure: Boolean = false,
    ): Media3SurfaceLease = when (val result = coordinator.media3SurfaceHost.acquire(mode, secure)) {
        is PlaybackResult.Success -> result.value
        is PlaybackResult.Failure -> throw AssertionError(
            "Expected Media3 surface lease: ${result.failure.code}",
        )
    }

    private suspend fun mpvLease(
        coordinator: CleanLiveSurfaceCoordinator,
        mode: SurfaceMode,
    ): MpvSurfaceLease = when (val result = coordinator.mpvSurfaceHost.acquire(mode, secure = false)) {
        is PlaybackResult.Success -> result.value
        is PlaybackResult.Failure -> throw AssertionError(
            "Expected libmpv surface lease: ${result.failure.code}",
        )
    }

    private fun assertFailure(result: PlaybackResult<*>) {
        assertTrue(result is PlaybackResult.Failure)
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private fun validSurface(): Surface = mockk(relaxed = true) {
        every { isValid } returns true
    }

    private fun drainMain() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
