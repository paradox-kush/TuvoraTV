package com.nuvio.tv.playback.media3

import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.SurfaceMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class Media3SurfaceHostTest {
    @Test
    fun `default release callback is a safe no-op`() = runTest {
        val host = ViewMedia3SurfaceHost(
            surfaceView = { surfaceView() },
            textureView = { null },
            viewDispatcher = Dispatchers.Unconfined,
        )
        val lease = lease(host, SurfaceMode.SURFACE_VIEW, secure = false)

        assertTrue(lease.release())
        assertTrue(lease.release())
    }

    @Test
    fun `released view callback fires exactly once after affirmative detach`() = runTest {
        val view = SurfaceView(RuntimeEnvironment.getApplication())
        val released = mutableListOf<android.view.View>()
        val host = ViewMedia3SurfaceHost(
            surfaceView = { view },
            textureView = { null },
            viewDispatcher = Dispatchers.Unconfined,
            onReleasedView = released::add,
        )
        val lease = lease(host, SurfaceMode.SURFACE_VIEW, secure = true)
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.clearVideoSurfaceWithResult() } returnsMany listOf(false, true)

        assertTrue(lease.secure)
        lease.attach(player)
        assertFalse(lease.detach(player))
        assertFalse(lease.release())
        assertTrue(released.isEmpty())
        assertTrue(lease.detach(player))
        assertTrue(lease.release())
        assertTrue(lease.release())

        assertEquals(1, released.size)
        assertSame(view, released.single())
    }

    @Test
    fun `terminal player release proof permits one TextureView callback`() = runTest {
        val view = TextureView(RuntimeEnvironment.getApplication())
        var callbackCount = 0
        val host = ViewMedia3SurfaceHost(
            surfaceView = { null },
            textureView = { view },
            viewDispatcher = Dispatchers.Unconfined,
            onReleasedView = { callbackCount++ },
        )
        val lease = lease(host, SurfaceMode.TEXTURE_VIEW, secure = false)
        val player = mockk<ExoPlayer>(relaxed = true)

        assertFalse(lease.secure)
        lease.attach(player)
        assertFalse(lease.release())
        lease.confirmPlayerReleased()
        assertTrue(lease.release())
        assertTrue(lease.release())
        assertEquals(1, callbackCount)
    }

    private suspend fun lease(
        host: ViewMedia3SurfaceHost,
        mode: SurfaceMode,
        secure: Boolean,
    ): Media3SurfaceLease = when (val result = host.acquire(mode, secure)) {
        is PlaybackResult.Success -> result.value
        is PlaybackResult.Failure -> throw AssertionError(result.failure.code)
    }

    private fun surfaceView() = SurfaceView(RuntimeEnvironment.getApplication())
}
