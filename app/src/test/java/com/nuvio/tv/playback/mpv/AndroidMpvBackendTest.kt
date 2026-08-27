package com.nuvio.tv.playback.mpv

import android.content.Context
import android.content.res.AssetManager
import android.view.Surface
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.SurfaceMode
import `is`.xyz.mpv.MPVNode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidMpvBackendTest {
    @Test
    fun `release and hard abort await one native destroy initiation`() = runTest {
        val core = FakeCore()
        val backend = backend(core)
        val lease = FakeLease()
        assertSuccess(backend.attachSurface(lease))
        assertSuccess(backend.start())

        assertSuccess(backend.release())
        assertSuccess(backend.hardAbort())

        assertEquals(1, core.destroyCalls)
        assertTrue(lease.canRelease)
        assertEquals(MpvBackendLifecycle.DEAD, backend.lifecycle)
    }

    @Test
    fun `failed detach never marks the lease detached but destroy still proves ownership ended`() = runTest {
        val core = FakeCore().apply { detachResult = false }
        val backend = backend(core)
        val lease = FakeLease()
        assertSuccess(backend.attachSurface(lease))

        assertTrue(backend.detachSurface() is PlaybackResult.Failure)
        assertTrue(!lease.canRelease)
        assertSuccess(backend.release())
        assertTrue(lease.canRelease)
    }

    @Test
    fun `only VO presented counter can publish first video frame`() = runTest {
        val core = FakeCore()
        val backend = backend(core)
        assertSuccess(backend.attachSurface(FakeLease()))
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            backend.events.first { it == MpvBackendEvent.FirstVideoFrame }
        }

        core.observer?.event(17, MPVNode.None) // VIDEO_RECONFIG is not presentation proof.
        core.observer?.event(21, MPVNode.None) // PLAYBACK_RESTART is not presentation proof.
        runCurrent()
        assertTrue(!event.isCompleted)
        core.observer?.property("presented-video-frame-count", 1L)
        runCurrent()
        assertEquals(MpvBackendEvent.FirstVideoFrame, event.await())
    }

    @Test
    fun `stream position reports continuing bytes before an endless load completes`() = runTest {
        val core = FakeCore()
        val backend = backend(core)
        assertSuccess(backend.attachSurface(FakeLease()))
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            backend.events.first { it == MpvBackendEvent.BytesReceived }
        }

        core.observer?.property("stream-pos", 1_024L)
        runCurrent()
        assertEquals(MpvBackendEvent.BytesReceived, event.await())
    }

    private fun TestScope.backend(core: FakeCore): AndroidMpvBackend {
        val files = File(System.getProperty("java.io.tmpdir"), "mpv-backend-${System.nanoTime()}").apply { mkdirs() }
        File(files, "cacert.pem").writeText("test-ca")
        val assetManager = mockk<AssetManager> {
            every { open("cacert.pem") } returns ByteArrayInputStream("test-ca".toByteArray())
        }
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns files
            every { assets } returns assetManager
        }
        return AndroidMpvBackend(
            context,
            MpvAdapterPlan(
                url = "https://example.test/live",
                headers = emptyMap(),
                preInitOptions = mapOf("vo" to "gpu"),
                runtimeProperties = emptyMap(),
                surfaceMode = SurfaceMode.GPU_RENDER,
                startPaused = false,
            ),
            StandardTestDispatcher(testScheduler),
            core,
        )
    }

    private fun assertSuccess(result: PlaybackResult<Unit>) = assertTrue(result is PlaybackResult.Success)

    private class FakeLease : MpvSurfaceLease {
        override val mode = SurfaceMode.GPU_RENDER
        override val secure = false
        override val surface: Surface = mockk(relaxed = true)
        private var attached = false
        val canRelease get() = !attached
        override fun markAttached() { attached = true }
        override fun confirmDetached() { attached = false }
        override fun confirmCoreDestroyed() { attached = false }
        override fun release(): Boolean = !attached
    }

    private class FakeCore : MpvNativeCore {
        var observer: MpvNativeObserver? = null
        var detachResult = true
        var destroyResult = true
        var destroyCalls = 0
        override fun create(context: Context) = Unit
        override fun setOption(name: String, value: String) = true
        override fun initialize() = Unit
        override fun addObserver(observer: MpvNativeObserver) { this.observer = observer }
        override fun removeObserver(observer: MpvNativeObserver) = Unit
        override fun observeLong(name: String) = Unit
        override fun observeDouble(name: String) = Unit
        override fun observeBoolean(name: String) = Unit
        override fun observeString(name: String) = Unit
        override fun observeNode(name: String) = Unit
        override fun attachSurface(surface: Surface) = true
        override fun detachSurfaceWithResult() = detachResult
        override fun command(vararg values: String) = Unit
        override fun setString(name: String, value: String) = Unit
        override fun setBoolean(name: String, value: Boolean) = Unit
        override fun long(name: String): Long? = 0L
        override fun node(name: String): MPVNode = MPVNode.None
        override fun destroyWithResult(): Boolean {
            destroyCalls++
            return destroyResult
        }
    }
}
