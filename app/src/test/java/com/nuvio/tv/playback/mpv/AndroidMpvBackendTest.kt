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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun `mpv authorization parsing is narrow and records inferred status provenance`() {
        listOf("HTTP error 401", "server returned: 403", "status code=401", "403 Forbidden").forEach { raw ->
            val failure = normalizeMpvError(raw)
            assertEquals(com.nuvio.tv.playback.core.FailureCode.AUTHORIZATION_REJECTED, failure.code)
            assertEquals(
                com.nuvio.tv.playback.core.HttpStatusProvenance.INFERRED_FROM_NETWORK_ERROR,
                failure.statusProvenance,
            )
        }
        listOf(
            "https://example.test/channel/401/segment.ts",
            "decoder returned frame 4031",
            "file id is 401",
        ).forEach { raw ->
            assertTrue("false positive for $raw", normalizeMpvError(raw).code != com.nuvio.tv.playback.core.FailureCode.AUTHORIZATION_REJECTED)
        }
    }
    @Test
    fun `hard abort after proven release does not terminate twice`() = runTest {
        val core = FakeCore()
        val backend = backend(core)
        val lease = FakeLease()
        assertSuccess(backend.attachSurface(lease))
        assertSuccess(backend.start())

        assertSuccess(backend.release())
        assertSuccess(backend.hardAbort())

        assertEquals(1, core.destroyCalls)
        assertEquals(0, core.forceTerminateCalls)
        assertTrue(lease.canRelease)
        assertEquals(MpvBackendLifecycle.DEAD, backend.lifecycle)
    }

    @Test
    fun `failed graceful release makes hard abort invoke independent native termination`() = runTest {
        val core = FakeCore().apply {
            destroyResult = false
            forceTerminateResult = true
        }
        val backend = backend(core)
        val lease = FakeLease()
        assertSuccess(backend.attachSurface(lease))
        assertSuccess(backend.start())

        assertTrue(backend.release() is PlaybackResult.Failure)
        assertSuccess(backend.hardAbort())

        assertEquals(1, core.destroyCalls)
        assertEquals(1, core.forceTerminateCalls)
        assertTrue(lease.canRelease)
        assertEquals(MpvBackendLifecycle.DEAD, backend.lifecycle)
    }

    // 1.5.8-proven zap-session invariant: a core that is not idle=yes self-terminates after
    // `stop` (mpv default idle=no), wedging every later native command — the emulator's
    // alternating second-zap wedge. idle=yes must be asserted again AFTER init because wrapper
    // layers have historically overwritten it post-init.
    @Test
    fun `idle yes is re-asserted after core initialization`() = runTest {
        val core = FakeCore()
        val backend = backend(core)

        assertSuccess(backend.attachSurface(FakeLease()))

        val init = core.callOrder.indexOf("init")
        val idleReassert = core.callOrder.indexOf("set:idle=yes")
        assertTrue(init >= 0)
        assertTrue(idleReassert > init)
    }

    // Upstream teardown order (mpv-android BaseMPVView): the VO must be stopped (`vo=null`)
    // BEFORE the surface is detached — detaching while the VO still owns the surface is the
    // documented native deadlock race (device-observed as the emulator "stuck on releasing").
    @Test
    fun `graceful release stops the video output before detaching the surface`() = runTest {
        val core = FakeCore()
        val backend = backend(core)
        assertSuccess(backend.attachSurface(FakeLease()))
        assertSuccess(backend.start())

        assertSuccess(backend.release())

        val stop = core.callOrder.indexOf("cmd:stop")
        val voNull = core.callOrder.indexOf("set:vo=null")
        val detach = core.callOrder.indexOf("detach")
        assertTrue(stop in 0 until voNull)
        assertTrue(voNull in 0 until detach)
    }

    // Regression: mpv_command("stop") is synchronous and blocks until the core's playloop
    // services it; a wedged playloop (thread-dump-anchored at MPV.command via stopSource) blocked
    // the old withContext body forever, and no caller timeout could abandon a structured child —
    // the release barrier hung indefinitely. stopSource must abandon the native task and fail.
    @Test
    fun `wedged native stop command cannot hang stopSource`() {
        val wedge = java.util.concurrent.CountDownLatch(1)
        val core = FakeCore().apply { stopCommandBlocksOn = wedge }
        val backend = realLaneBackend(core)
        try {
            runBlocking {
                assertSuccess(backend.attachSurface(FakeLease()))
                assertSuccess(backend.start())

                val result = withTimeout(15_000) { backend.stopSource() }

                assertTrue(result is PlaybackResult.Failure)
            }
        } finally {
            wedge.countDown()
        }
    }

    // Regression: a native teardown call wedged inside the serialized backend lane
    // (device-observed on the emulator: `stop` blocking in ANGLE/goldfish teardown) starved
    // forced termination, which queued behind it on the same limitedParallelism(1) dispatcher,
    // while hardAbort awaited the graceful result unbounded — the session's release barrier
    // then never completed and the host retried "releasing" forever. Hard abort must escalate
    // past a wedged graceful task and prove death on an independent lane.
    @Test
    fun `wedged graceful destroy cannot starve forced termination`() {
        val destroyEntered = java.util.concurrent.CountDownLatch(1)
        val wedge = java.util.concurrent.CountDownLatch(1)
        val core = FakeCore().apply {
            forceTerminateResult = true
            destroyEnteredLatch = destroyEntered
            destroyBlocksOn = wedge
        }
        val backend = realLaneBackend(core)
        val lease = FakeLease()
        try {
            runBlocking {
                assertSuccess(backend.attachSurface(lease))
                assertSuccess(backend.start())
                val releaseJob = launch(kotlinx.coroutines.Dispatchers.IO) { backend.release() }
                assertTrue(destroyEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))

                val aborted = withTimeout(10_000) { backend.hardAbort() }

                assertSuccess(aborted)
                assertEquals(1, core.forceTerminateCalls)
                assertEquals(MpvBackendLifecycle.DEAD, backend.lifecycle)
                assertTrue(lease.canRelease)
                releaseJob.cancel()
            }
        } finally {
            wedge.countDown()
        }
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
    fun `source stop does not complete until libmpv acknowledges END_FILE stop`() = runTest {
        val core = FakeCore()
        val backend = backend(core)
        assertSuccess(backend.attachSurface(FakeLease()))
        assertSuccess(backend.start())

        val stopped = async { backend.stopSource() }
        runCurrent()
        assertEquals(listOf("stop"), core.commands.last())
        assertTrue(!stopped.isCompleted)

        core.observer?.event(
            7,
            MPVNode.MapNode(mapOf("reason" to MPVNode.StringNode("stop"))),
        )
        runCurrent()

        assertSuccess(stopped.await())
        assertEquals(MpvBackendLifecycle.ATTACHED, backend.lifecycle)
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
    fun `manual subtitle selection survives unrelated runtime profile apply`() = runTest {
        val core = FakeCore()
        val backend = backend(core)
        assertSuccess(backend.attachSurface(FakeLease()))
        assertSuccess(backend.start())
        core.observer?.property(
            "track-list",
            MPVNode.ArrayNode(
                arrayOf(
                    MPVNode.MapNode(
                        mapOf(
                            "type" to MPVNode.StringNode("sub"),
                            "selected" to MPVNode.BooleanNode(false),
                            "lang" to MPVNode.StringNode("fr"),
                        ),
                    ),
                ),
            ),
        )
        runCurrent()
        assertSuccess(backend.selectSubtitleTrack(com.nuvio.tv.playback.core.PlaybackTrackId("mpv:subtitle:0")))
        core.strings.clear()

        assertSuccess(backend.apply(testPlan().copy(runtimeProperties = mapOf("video-sync" to "display-resample"))))

        assertTrue(core.strings.none { it.first == "sid" || it.first == "slang" })
        assertTrue(core.strings.contains("video-sync" to "display-resample"))
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
            testPlan(),
            StandardTestDispatcher(testScheduler),
            core,
            externalSubtitleResolver,
        )
    }

    /** Backend on a REAL single-thread lane so a blocking native call genuinely occupies it. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun realLaneBackend(core: FakeCore): AndroidMpvBackend {
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
            testPlan(),
            kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1),
            core,
            ExternalSubtitleResolver { null },
        )
    }

    private fun testPlan() = MpvAdapterPlan(
        url = "https://example.test/live",
        headers = emptyMap(),
        preInitOptions = mapOf("vo" to "gpu", "sid" to "auto", "slang" to "eng"),
        runtimeProperties = emptyMap(),
        surfaceMode = SurfaceMode.GPU_RENDER,
        startPaused = false,
        dnsMode = MpvDnsMode.SYSTEM,
    )

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
        var forceTerminateResult = true
        var destroyCalls = 0
        var forceTerminateCalls = 0
        var createCalls = 0
        var initializeCalls = 0
        var attachCalls = 0
        var loadFileCalls = 0
        val commands = mutableListOf<List<String>>()
        val strings = mutableListOf<Pair<String, String>>()
        val options = mutableListOf<Pair<String, String>>()
        override fun create(context: Context) { createCalls++ }
        override fun setOption(name: String, value: String): Boolean {
            options += name to value
            return true
        }
        override fun initialize() {
            initializeCalls++
            callOrder += "init"
        }
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
        val callOrder = mutableListOf<String>()
        override fun detachSurfaceWithResult(): Boolean {
            callOrder += "detach"
            return detachResult
        }
        var stopCommandBlocksOn: java.util.concurrent.CountDownLatch? = null
        override fun command(vararg values: String) {
            commands += values.toList()
            callOrder += "cmd:${values.firstOrNull()}"
            if (values.firstOrNull() == "loadfile") loadFileCalls++
            if (values.firstOrNull() == "stop") stopCommandBlocksOn?.await()
        }
        override fun setString(name: String, value: String) {
            strings += name to value
            callOrder += "set:$name=$value"
        }
        override fun setBoolean(name: String, value: Boolean) = Unit
        override fun long(name: String): Long? = 0L
        override fun node(name: String): MPVNode = MPVNode.None
        var destroyEnteredLatch: java.util.concurrent.CountDownLatch? = null
        var destroyBlocksOn: java.util.concurrent.CountDownLatch? = null
        override fun destroyWithResult(): Boolean {
            destroyCalls++
            destroyEnteredLatch?.countDown()
            destroyBlocksOn?.await()
            return destroyResult
        }
        override fun forceTerminateWithResult(): Boolean {
            forceTerminateCalls++
            return forceTerminateResult
        }
    }
}
