package com.nuvio.tv.playback.media3

import androidx.media3.exoplayer.ExoPlayer
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Media3EngineTest {
    @Test
    fun `backend facts map to generation events and ENDED remains factual EOF`() = runTest {
        val backend = FakeBackend()
        val engine = engine(backend, backgroundScope)
        assertSuccess(engine.attachSurface(7, graph()))
        assertSuccess(engine.start(start(7)))
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            engine.events.first { it is PlaybackEvent.PlaybackEnded }
        }
        runCurrent()
        backend.emit(Media3BackendEvent.Ended)
        advanceUntilIdle()
        assertEquals(PlaybackEvent.PlaybackEnded(7, PlaybackEndReason.EOF), event.await())
        assertEquals(1, backend.startCalls)
        assertEquals(0, backend.retryCalls)
    }

    @Test
    fun `collector is subscribed before backend start can emit synchronously`() = runTest {
        val backend = FakeBackend().apply { eventOnStart = Media3BackendEvent.Ended }
        val engine = engine(backend, backgroundScope)
        assertSuccess(engine.attachSurface(8, graph()))
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            engine.events.first { it is PlaybackEvent.PlaybackEnded }
        }

        assertSuccess(engine.start(start(8)))

        assertEquals(PlaybackEvent.PlaybackEnded(8, PlaybackEndReason.EOF), event.await())
    }

    @Test
    fun `second start is rejected while one provider backend is active`() = runTest {
        val backend = FakeBackend()
        val engine = engine(backend, backgroundScope)
        assertSuccess(engine.attachSurface(1, graph()))
        assertSuccess(engine.start(start(1)))
        assertTrue(engine.start(start(1)) is PlaybackResult.Failure)
        assertEquals(1, backend.startCalls)
    }

    @Test
    fun `graceful release failure retains ownership until hard abort proves release`() = runTest {
        val first = FakeBackend().apply { releaseSucceeds = false }
        val second = FakeBackend()
        var next = first
        val engine = Media3Engine(
            scope = backgroundScope,
            surfaceHost = FakeSurfaceHost(),
            backendFactory = Media3BackendFactory { PlaybackResult.Success(next) },
        )
        assertSuccess(engine.attachSurface(1, graph()))
        assertSuccess(engine.start(start(1)))
        assertTrue(engine.release(1) is PlaybackResult.Failure)
        assertTrue(engine.attachSurface(2, graph()) is PlaybackResult.Failure)
        assertSuccess(engine.hardAbort(1))
        next = second
        assertSuccess(engine.attachSurface(2, graph()))
        assertSuccess(engine.start(start(2)))
        assertEquals(1, first.hardAbortCalls)
        assertEquals(1, second.startCalls)
    }

    @Test
    fun `in place requirements update reaches backend without rebuilding provider request`() = runTest {
        val backend = FakeBackend()
        val engine = engine(backend, backgroundScope)
        assertSuccess(engine.attachSurface(3, graph()))
        assertSuccess(engine.start(start(3)))
        assertSuccess(
            engine.applyRequirements(
                3,
                requirements().copy(preferredAudioLanguage = "fr", subtitlesEnabled = false),
            ),
        )
        assertEquals(1, backend.applyCalls)
        assertEquals("fr", backend.lastPlan?.tracks?.preferredAudioLanguage)
        assertEquals(false, backend.lastPlan?.tracks?.subtitlesEnabled)
        assertEquals(1, backend.startCalls)
    }

    @Test
    fun `metrics are generation bound and expose continuing rendered frame truth`() = runTest {
        val backend = FakeBackend().apply {
            decoderMetrics = Media3DecoderMetrics(120, 3, 4, 300, 2, 1)
        }
        val engine = engine(backend, backgroundScope)
        assertSuccess(engine.attachSurface(9, graph()))
        assertSuccess(engine.start(start(9)))
        val snapshot = engine.snapshotMetrics(9).successValue()
        assertEquals(9, snapshot.generation)
        assertEquals(120L, snapshot.videoFramesRendered)
        assertEquals(4L, snapshot.videoFramesDropped)
        assertTrue(engine.snapshotMetrics(8) is PlaybackResult.Failure)
    }

    @Test
    fun `failed hard abort remains fail closed and prevents a new provider`() = runTest {
        val backend = FakeBackend().apply {
            releaseSucceeds = false
            hardAbortSucceeds = false
        }
        val engine = engine(backend, backgroundScope)
        assertSuccess(engine.attachSurface(1, graph()))
        assertSuccess(engine.start(start(1)))
        assertTrue(engine.release(1) is PlaybackResult.Failure)
        assertTrue(engine.hardAbort(1) is PlaybackResult.Failure)
        assertTrue(engine.attachSurface(2, graph()) is PlaybackResult.Failure)
    }

    @Test
    fun `unproven surface detach remains fail closed`() = runTest {
        val backend = FakeBackend().apply { detachSucceeds = false }
        val engine = engine(backend, backgroundScope)
        assertSuccess(engine.attachSurface(4, graph()))
        assertSuccess(engine.start(start(4)))
        assertTrue(engine.detachSurface(4) is PlaybackResult.Failure)
        assertTrue(engine.attachSurface(5, graph()) is PlaybackResult.Failure)
    }

    private fun engine(backend: FakeBackend, scope: kotlinx.coroutines.CoroutineScope) = Media3Engine(
        scope = scope,
        surfaceHost = FakeSurfaceHost(),
        backendFactory = Media3BackendFactory { PlaybackResult.Success(backend) },
    )

    private fun start(generation: Long) = PlaybackEngineStart(
        generation = generation,
        request = PlaybackRequest("https://example.test/live", contentType = ContentType.LIVE),
        evidence = StreamEvidence(),
        graph = graph(),
        requirements = requirements(),
        startPaused = false,
    )

    private fun graph() = PlaybackGraph(
        id = "media3",
        engine = EngineType.MEDIA3,
        outputProfile = GraphOutputProfile.MEDIA3_STANDARD,
        decoderMode = DecoderMode.HARDWARE,
        audioMode = AudioMode.DECODE,
        surfaceMode = SurfaceMode.SURFACE_VIEW,
    )

    private fun requirements() = PlaybackRequirements(
        profile = SessionProfile.FULLSCREEN,
        priority = SessionPriority.QUALITY_AND_STABILITY,
        qualityIntent = VideoQualityIntent.FULL,
        displayModeSwitchAllowed = true,
        frameRatePreference = FrameRatePreference.ON_COMMITTED_PLAYBACK,
        hdrPreference = HdrPreference.AUTO,
        decoderPreference = DecoderPreference.AUTO,
        softwareDecodeFallbackAllowed = false,
        subtitleFidelity = SubtitleFidelity.COMPATIBLE,
        subtitlesEnabled = true,
        audioOutput = AudioOutputPreference.PCM,
        pcmProcessingAllowed = true,
        buffering = BufferingPreference.RECOMMENDED,
        gpuRenderingAllowed = false,
        eligibleEngines = setOf(EngineType.MEDIA3),
        allowedSurfaceModes = setOf(SurfaceMode.SURFACE_VIEW),
        secureOutputRequired = false,
        resourceBudget = ResourceBudget(),
    )

    private fun assertSuccess(result: PlaybackResult<Unit>) {
        assertTrue("Expected success, got $result", result is PlaybackResult.Success)
    }

    private fun PlaybackResult<com.nuvio.tv.playback.core.PlaybackEngineMetricsSnapshot>.successValue() =
        (this as PlaybackResult.Success).value

    private class FakeSurfaceHost : Media3SurfaceHost {
        override suspend fun acquire(
            mode: SurfaceMode,
            secure: Boolean,
        ): PlaybackResult<Media3SurfaceLease> = PlaybackResult.Success(FakeLease(mode, secure))
    }

    private class FakeLease(
        override val mode: SurfaceMode,
        override val secure: Boolean,
    ) : Media3SurfaceLease {
        private var attached = false
        override fun attach(player: ExoPlayer) { attached = true }
        override fun detach(player: ExoPlayer): Boolean { attached = false; return true }
        override fun confirmPlayerReleased() { attached = false }
        override suspend fun release(): Boolean = !attached
    }

    private class FakeBackend : Media3Backend {
        private val mutableEvents = MutableSharedFlow<Media3BackendEvent>(extraBufferCapacity = 8)
        override val events: Flow<Media3BackendEvent> = mutableEvents
        var startCalls = 0
        var applyCalls = 0
        var retryCalls = 0
        var hardAbortCalls = 0
        var releaseSucceeds = true
        var hardAbortSucceeds = true
        var detachSucceeds = true
        var eventOnStart: Media3BackendEvent? = null
        var lastPlan: Media3AdapterPlan? = null
        var decoderMetrics = Media3DecoderMetrics(0, 0, 0, 0, 0, 0)

        fun emit(event: Media3BackendEvent) { mutableEvents.tryEmit(event) }
        override suspend fun attachSurface(lease: Media3SurfaceLease) = PlaybackResult.Success(Unit)
        override suspend fun start(paused: Boolean): PlaybackResult<Unit> {
            startCalls++
            eventOnStart?.let(mutableEvents::tryEmit)
            return PlaybackResult.Success(Unit)
        }
        override suspend fun setPaused(paused: Boolean) = PlaybackResult.Success(Unit)
        override suspend fun apply(plan: Media3AdapterPlan): PlaybackResult<Unit> {
            applyCalls++
            lastPlan = plan
            return PlaybackResult.Success(Unit)
        }
        override suspend fun detachSurface(): PlaybackResult<Unit> = if (detachSucceeds) {
            PlaybackResult.Success(Unit)
        } else {
            PlaybackResult.Failure(surfaceFailure())
        }
        override suspend fun release(): PlaybackResult<Unit> = if (releaseSucceeds) {
            PlaybackResult.Success(Unit)
        } else {
            PlaybackResult.Failure(surfaceFailure())
        }
        override suspend fun hardAbort(): PlaybackResult<Unit> {
            hardAbortCalls++
            return if (hardAbortSucceeds) PlaybackResult.Success(Unit) else PlaybackResult.Failure(surfaceFailure())
        }
        override suspend fun metrics(): PlaybackResult<Media3DecoderMetrics> =
            PlaybackResult.Success(decoderMetrics)
    }
}
