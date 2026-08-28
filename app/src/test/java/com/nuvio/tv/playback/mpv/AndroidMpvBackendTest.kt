package com.nuvio.tv.playback.mpv

import android.content.Context
import android.content.res.AssetManager
import android.view.Surface
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.ExternalSubtitleId
import com.nuvio.tv.playback.core.ExternalSubtitleRegistration
import com.nuvio.tv.playback.core.ExternalSubtitleResolver
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

    @Test
    fun `surface recreation does not recreate core or reload provider`() = runTest {
        val core = FakeCore()
        val backend = backend(core)
        val first = FakeLease()
        val second = FakeLease()
        assertSuccess(backend.attachSurface(first))
        assertSuccess(backend.start())

        assertSuccess(backend.detachSurface())
        assertTrue(first.canRelease)
        assertSuccess(backend.attachSurface(second))

        assertEquals(1, core.createCalls)
        assertEquals(1, core.initializeCalls)
        assertEquals(2, core.attachCalls)
        assertEquals(1, core.loadFileCalls)
    }

    @Test
    fun `opaque external subtitle resolves privately and attaches once`() = runTest {
        val core = FakeCore()
        val id = ExternalSubtitleId("subtitle-1")
        val registration = ExternalSubtitleRegistration(
            uri = "https://subtitle.example/private.srt?token=secret",
            mimeType = "application/x-subrip",
            language = "en",
            label = "English",
        )
        val backend = backend(core, ExternalSubtitleResolver { requested ->
            registration.takeIf { requested == id }
        })
        assertSuccess(backend.attachSurface(FakeLease()))
        assertSuccess(backend.start())

        assertSuccess(backend.attachExternalSubtitle(id))

        assertEquals(
            listOf("sub-add", registration.uri, "select", "English", "en"),
            core.commands.single { it.firstOrNull() == "sub-add" },
        )
        assertTrue(!id.toString().contains("subtitle-1"))
        assertTrue(!registration.toString().contains("token=secret"))
    }

    @Test
    fun `audio presentation and video codec properties publish factual events`() = runTest {
        val core = FakeCore()
        val backend = backend(core)
        assertSuccess(backend.attachSurface(FakeLease()))
        val audio = async(start = CoroutineStart.UNDISPATCHED) {
            backend.events.first { it == MpvBackendEvent.FirstAudio }
        }
        core.observer?.property("audio-pts", 1.25)
        runCurrent()
        assertEquals(MpvBackendEvent.FirstAudio, audio.await())

        val codec = async(start = CoroutineStart.UNDISPATCHED) {
            backend.events.first { it is MpvBackendEvent.VideoInputFormatChanged }
        }
        core.observer?.property("video-codec", "hevc")
        runCurrent()
        assertEquals(MpvBackendEvent.VideoInputFormatChanged("video/hevc"), codec.await())
    }

    @Test
    fun `selected track demux fps publishes a factual frame rate`() = runTest {
        val core = FakeCore()
        val backend = backend(core)
        assertSuccess(backend.attachSurface(FakeLease()))
        val frameRate = async(start = CoroutineStart.UNDISPATCHED) {
            backend.events.first { it is MpvBackendEvent.VideoFrameRateChanged }
        }

        core.observer?.property("track-list", trackList(selectedFrameRate = 59.94))
        runCurrent()

        assertEquals(MpvBackendEvent.VideoFrameRateChanged(59.94f), frameRate.await())

        val changed = async(start = CoroutineStart.UNDISPATCHED) {
            backend.events.first { it is MpvBackendEvent.VideoFrameRateChanged }
        }
        core.observer?.property("track-list", trackList(selectedFrameRate = 59.94))
        runCurrent()
        assertTrue(!changed.isCompleted)
        core.observer?.property("track-list", trackList(selectedFrameRate = 50.0))
        runCurrent()
        assertEquals(MpvBackendEvent.VideoFrameRateChanged(50f), changed.await())
    }

    @Test
    fun `mpv track parser ignores unselected estimated and implausible rates`() {
        assertEquals(24f, parseMpvTracks(trackList(selectedFrameRate = 24.0)).selectedVideoFrameRate)
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 9.99, 120.01).forEach { rate ->
            assertEquals(null, parseMpvTracks(trackList(selectedFrameRate = rate)).selectedVideoFrameRate)
        }
        assertEquals(
            null,
            parseMpvTracks(trackList(selectedFrameRate = null, estimatedFrameRate = 60.0))
                .selectedVideoFrameRate,
        )
    }

    private fun TestScope.backend(
        core: FakeCore,
        externalSubtitleResolver: ExternalSubtitleResolver = ExternalSubtitleResolver { null },
    ): AndroidMpvBackend {
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
                dnsMode = MpvDnsMode.SYSTEM,
            ),
            StandardTestDispatcher(testScheduler),
            core,
            externalSubtitleResolver,
        )
    }

    private fun assertSuccess(result: PlaybackResult<Unit>) = assertTrue(result is PlaybackResult.Success)

    private fun trackList(
        selectedFrameRate: Double?,
        estimatedFrameRate: Double? = null,
    ): MPVNode = MPVNode.ArrayNode(
        arrayOf(
            MPVNode.MapNode(
                buildMap {
                    put("type", MPVNode.StringNode("video"))
                    put("selected", MPVNode.BooleanNode(true))
                    selectedFrameRate?.let { put("demux-fps", MPVNode.DoubleNode(it)) }
                    estimatedFrameRate?.let { put("estimated-vf-fps", MPVNode.DoubleNode(it)) }
                },
            ),
            MPVNode.MapNode(
                mapOf(
                    "type" to MPVNode.StringNode("video"),
                    "selected" to MPVNode.BooleanNode(false),
                    "demux-fps" to MPVNode.DoubleNode(120.0),
                ),
            ),
        ),
    )

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
        var createCalls = 0
        var initializeCalls = 0
        var attachCalls = 0
        var loadFileCalls = 0
        val commands = mutableListOf<List<String>>()
        override fun create(context: Context) { createCalls++ }
        override fun setOption(name: String, value: String) = true
        override fun initialize() { initializeCalls++ }
        override fun addObserver(observer: MpvNativeObserver) { this.observer = observer }
        override fun removeObserver(observer: MpvNativeObserver) = Unit
        override fun observeLong(name: String) = Unit
        override fun observeDouble(name: String) = Unit
        override fun observeBoolean(name: String) = Unit
        override fun observeString(name: String) = Unit
        override fun observeNode(name: String) = Unit
        override fun attachSurface(surface: Surface): Boolean {
            attachCalls++
            return true
        }
        override fun detachSurfaceWithResult() = detachResult
        override fun command(vararg values: String) {
            commands += values.toList()
            if (values.firstOrNull() == "loadfile") loadFileCalls++
        }
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
