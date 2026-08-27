package com.nuvio.tv.playback.mpv

import android.view.Surface
import com.nuvio.tv.playback.core.AudioMode
import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.DecoderMode
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.GraphOutputProfile
import com.nuvio.tv.playback.core.HdrPreference
import com.nuvio.tv.playback.core.PlaybackEndReason
import com.nuvio.tv.playback.core.PlaybackEngineStart
import com.nuvio.tv.playback.core.PlaybackEvent
import com.nuvio.tv.playback.core.PlaybackGraph
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.PlaybackRequirements
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.ResourceBudget
import com.nuvio.tv.playback.core.SessionPriority
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.core.SubtitleFidelity
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.VideoQualityIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MpvEngineTest {
    @Test
    fun `typed EOF stays a fact and is not retried by adapter`() = runTest {
        val backend = FakeBackend()
        val engine = engine(backend, backgroundScope)
        success(engine.attachSurface(4, graph()))
        success(engine.start(start(4)))
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            engine.events.first { it is PlaybackEvent.PlaybackEnded }
        }
        backend.emit(MpvBackendEvent.Ended(PlaybackEndReason.EOF))
        assertEquals(PlaybackEvent.PlaybackEnded(4, PlaybackEndReason.EOF), event.await())
        assertEquals(1, backend.startCalls)
    }

    @Test
    fun `collector sees a terminal event emitted synchronously during start`() = runTest {
        val backend = FakeBackend().apply { eventOnStart = MpvBackendEvent.Ended(PlaybackEndReason.EOF) }
        val engine = engine(backend, backgroundScope)
        success(engine.attachSurface(5, graph()))
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            engine.events.first { it is PlaybackEvent.PlaybackEnded }
        }
        success(engine.start(start(5)))
        assertEquals(PlaybackEvent.PlaybackEnded(5, PlaybackEndReason.EOF), event.await())
    }

    @Test
    fun `release failure retains the provider until initiate-once hard abort proves death`() = runTest {
        val backend = FakeBackend().apply { releaseSucceeds = false }
        val engine = engine(backend, backgroundScope)
        success(engine.attachSurface(1, graph()))
        success(engine.start(start(1)))
        assertTrue(engine.release(1) is PlaybackResult.Failure)
        assertTrue(engine.attachSurface(2, graph()) is PlaybackResult.Failure)
        backend.releaseSucceeds = true
        success(engine.hardAbort(1))
        assertEquals(1, backend.releaseCalls)
        assertEquals(1, backend.abortCalls)
    }

    @Test
    fun `unproven detach keeps surface ownership fail closed`() = runTest {
        val backend = FakeBackend().apply { detachSucceeds = false }
        val engine = engine(backend, backgroundScope)
        success(engine.attachSurface(8, graph()))
        success(engine.start(start(8)))
        assertTrue(engine.detachSurface(8) is PlaybackResult.Failure)
        assertTrue(engine.attachSurface(9, graph()) is PlaybackResult.Failure)
    }

    @Test
    fun `metrics expose VO-presented frame truth and remain generation bound`() = runTest {
        val backend = FakeBackend().apply { metrics = MpvMetrics(90, 2, 3) }
        val engine = engine(backend, backgroundScope)
        success(engine.attachSurface(3, graph()))
        success(engine.start(start(3)))
        val metrics = (engine.snapshotMetrics(3) as PlaybackResult.Success).value
        assertEquals(90L, metrics.videoFramesRendered)
        assertEquals(3L, metrics.videoFramesDropped)
        assertEquals(null, metrics.audioBuffersRendered)
        assertTrue(engine.snapshotMetrics(2) is PlaybackResult.Failure)
    }

    @Test
    fun `surface recreation reuses one libmpv backend and provider load`() = runTest {
        val backend = FakeBackend()
        val host = CountingSurfaceHost()
        var backendCreations = 0
        val engine = MpvEngine(
            backgroundScope,
            host,
            MpvBackendFactory {
                backendCreations++
                PlaybackResult.Success(backend)
            },
        )
        success(engine.attachSurface(11, graph()))
        success(engine.start(start(11)))

        success(engine.detachSurface(11))
        success(engine.attachSurface(11, graph()))

        assertEquals(2, host.acquireCalls)
        assertEquals(2, backend.attachCalls)
        assertEquals(1, backendCreations)
        assertEquals(1, backend.startCalls)
    }

    @Test
    fun `libmpv decoder format and dimensions remain generation facts`() = runTest {
        val backend = FakeBackend()
        val engine = engine(backend, backgroundScope)
        success(engine.attachSurface(12, graph()))
        success(engine.start(start(12)))
        val expected = listOf(
            MpvBackendEvent.VideoDecoderInitialized("mediacodec"),
            MpvBackendEvent.VideoInputFormatChanged("video/hevc"),
            MpvBackendEvent.VideoFrameRateChanged(50f),
            MpvBackendEvent.VideoSizeChanged(1920, 1080),
        )
        val received = expected.map { fact ->
            val event = async(start = CoroutineStart.UNDISPATCHED) { engine.events.first() }
            backend.emit(fact)
            event.await()
        }

        assertEquals(PlaybackEvent.VideoDecoderInitialized(12, "mediacodec"), received[0])
        assertEquals(PlaybackEvent.VideoInputFormatChanged(12, "video/hevc"), received[1])
        assertEquals(PlaybackEvent.VideoFrameRateChanged(12, 50f), received[2])
        assertEquals(PlaybackEvent.VideoSizeChanged(12, 1920, 1080), received[3])
    }

    @Test
    fun `end file parser preserves all public mpv reasons`() {
        listOf("eof", "error", "stop", "quit", "redirect", "future").forEach { reason ->
            val parsed = parseEndFile(
                `is`.xyz.mpv.MPVNode.MapNode(
                    mapOf(
                        "reason" to `is`.xyz.mpv.MPVNode.StringNode(reason),
                        "file_error" to `is`.xyz.mpv.MPVNode.StringNode("hidden detail"),
                    ),
                ),
            )
            assertEquals("hidden detail", parsed.fileError)
        }
        assertEquals(
            MpvEndReason.REDIRECT,
            parseEndFile(`is`.xyz.mpv.MPVNode.MapNode(mapOf("reason" to `is`.xyz.mpv.MPVNode.StringNode("redirect")))).reason,
        )
    }

    private fun engine(backend: FakeBackend, scope: CoroutineScope) = MpvEngine(
        scope,
        FakeSurfaceHost(),
        MpvBackendFactory { PlaybackResult.Success(backend) },
    )

    private fun start(generation: Long) = PlaybackEngineStart(
        generation,
        PlaybackRequest("https://example.test/live", contentType = ContentType.LIVE),
        StreamEvidence(),
        graph(),
        requirements(),
        false,
    )

    private fun graph() = PlaybackGraph(
        "mpv",
        EngineType.LIBMPV,
        GraphOutputProfile.MPV_RENDER,
        DecoderMode.HARDWARE,
        AudioMode.DECODE,
        SurfaceMode.GPU_RENDER,
    )

    private fun requirements() = PlaybackRequirements(
        profile = SessionProfile.FULLSCREEN,
        priority = SessionPriority.QUALITY_AND_STABILITY,
        qualityIntent = VideoQualityIntent.FULL,
        displayModeSwitchAllowed = true,
        frameRatePreference = FrameRatePreference.ON_START,
        hdrPreference = HdrPreference.AUTO,
        decoderPreference = DecoderPreference.AUTO,
        softwareDecodeFallbackAllowed = true,
        subtitleFidelity = SubtitleFidelity.FULL,
        subtitlesEnabled = true,
        audioOutput = AudioOutputPreference.PCM,
        pcmProcessingAllowed = true,
        buffering = BufferingPreference.RECOMMENDED,
        gpuRenderingAllowed = true,
        eligibleEngines = setOf(EngineType.LIBMPV),
        allowedSurfaceModes = setOf(SurfaceMode.GPU_RENDER),
        secureOutputRequired = false,
        resourceBudget = ResourceBudget(),
    )

    private fun success(result: PlaybackResult<Unit>) = assertTrue(result is PlaybackResult.Success)

    private class FakeSurfaceHost : MpvSurfaceHost {
        override suspend fun acquire(mode: SurfaceMode, secure: Boolean): PlaybackResult<MpvSurfaceLease> =
            PlaybackResult.Success(FakeLease(mode, secure))
    }

    private class CountingSurfaceHost : MpvSurfaceHost {
        var acquireCalls = 0
        override suspend fun acquire(mode: SurfaceMode, secure: Boolean): PlaybackResult<MpvSurfaceLease> {
            acquireCalls++
            return PlaybackResult.Success(FakeLease(mode, secure))
        }
    }

    private class FakeLease(
        override val mode: SurfaceMode,
        override val secure: Boolean,
    ) : MpvSurfaceLease {
        override val surface: Surface get() = error("Fake backend must not access Android Surface")
        private var attached = false
        override fun markAttached() { attached = true }
        override fun confirmDetached() { attached = false }
        override fun confirmCoreDestroyed() { attached = false }
        override fun release(): Boolean = !attached
    }

    private class FakeBackend : MpvBackend {
        private val flow = MutableSharedFlow<MpvBackendEvent>(extraBufferCapacity = 8)
        override val events: Flow<MpvBackendEvent> = flow.asSharedFlow()
        var eventOnStart: MpvBackendEvent? = null
        var startCalls = 0
        var attachCalls = 0
        var releaseCalls = 0
        var abortCalls = 0
        var releaseSucceeds = true
        var detachSucceeds = true
        var metrics = MpvMetrics(0, 0, 0)
        private var lease: MpvSurfaceLease? = null

        override suspend fun attachSurface(lease: MpvSurfaceLease): PlaybackResult<Unit> {
            attachCalls++
            lease.markAttached()
            this.lease = lease
            return PlaybackResult.Success(Unit)
        }
        override suspend fun start(): PlaybackResult<Unit> {
            startCalls++
            eventOnStart?.let(flow::tryEmit)
            return PlaybackResult.Success(Unit)
        }
        override suspend fun setPaused(paused: Boolean) = PlaybackResult.Success(Unit)
        override suspend fun apply(plan: MpvAdapterPlan) = PlaybackResult.Success(Unit)
        override suspend fun detachSurface(): PlaybackResult<Unit> {
            if (!detachSucceeds) return failure()
            lease?.confirmDetached()
            return PlaybackResult.Success(Unit)
        }
        override suspend fun metrics() = PlaybackResult.Success(metrics)
        override suspend fun release(): PlaybackResult<Unit> {
            releaseCalls++
            if (!releaseSucceeds) return failure()
            lease?.confirmCoreDestroyed()
            return PlaybackResult.Success(Unit)
        }
        override suspend fun hardAbort(): PlaybackResult<Unit> {
            abortCalls++
            if (!releaseSucceeds) return failure()
            lease?.confirmCoreDestroyed()
            return PlaybackResult.Success(Unit)
        }
        fun emit(event: MpvBackendEvent) { flow.tryEmit(event) }
        private fun failure() = PlaybackResult.Failure(
            com.nuvio.tv.playback.core.PlaybackFailure(
                com.nuvio.tv.playback.core.FailureCode.RESOURCE_RELEASE_FAILED,
                com.nuvio.tv.playback.core.FailureDomain.DEVICE_RESOURCE,
                com.nuvio.tv.playback.core.FailurePhase.RELEASE,
                com.nuvio.tv.playback.core.Retryability.FATAL,
            ),
        )
    }
}
