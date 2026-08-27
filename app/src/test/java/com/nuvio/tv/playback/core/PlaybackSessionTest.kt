package com.nuvio.tv.playback.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionTest {
    @Test
    fun `one actor drives request selection surface engine and immutable snapshot`() = runTest {
        val engine = FakeEngine()
        val session = session(engine)

        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertEquals(1, engine.startCalls)
        assertEquals(PlaybackState.STARTING_PRIMARY, session.snapshot.value.state)
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)
        assertTrue(session.snapshot.value.isPlaying)
        close(session)
    }

    @Test
    fun `zap releases the provider connection before resolving the next request`() = runTest {
        val engine = FakeEngine()
        var resolutionWhileConnected = false
        val resolver = PlaybackRequestResolver { request ->
            if (engine.activeConnections > 0) resolutionWhileConnected = true
            PlaybackResult.Success(request.resolved())
        }
        val session = session(engine, requestResolver = resolver)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()
        engine.releaseFailuresRemaining = 1

        session.dispatch(PlaybackCommand.Zap(secondLiveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertFalse(resolutionWhileConnected)
        assertEquals(2, engine.startCalls)
        assertEquals(1, engine.maxActiveConnections)
        assertTrue(engine.releaseCalls >= 2)
        close(session)
    }

    @Test
    fun `release cancels graphless request resolution and completes without engine callback`() = runTest {
        val resolverEntered = CompletableDeferred<Unit>()
        val resolver = PlaybackRequestResolver {
            resolverEntered.complete(Unit)
            awaitCancellation()
        }
        val engine = FakeEngine()
        val session = session(engine, requestResolver = resolver)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        resolverEntered.await()

        session.dispatch(PlaybackCommand.Release)
        advanceUntilIdle()

        assertEquals(PlaybackState.STOPPED, session.snapshot.value.state)
        assertEquals(0, engine.releaseCalls)
        close(session)
    }

    @Test
    fun `rapid zap coalesces behind one release barrier and opens only the latest request`() = runTest {
        val engine = FakeEngine()
        val resolvedUrls = mutableListOf<String>()
        val resolver = PlaybackRequestResolver { request ->
            resolvedUrls += request.url
            PlaybackResult.Success(request.resolved())
        }
        val session = session(engine, requestResolver = resolver)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        session.dispatch(PlaybackCommand.Zap(secondLiveRequest, SessionProfile.FULLSCREEN))
        session.dispatch(PlaybackCommand.Zap(thirdLiveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertEquals(listOf(liveRequest.url, thirdLiveRequest.url), resolvedUrls)
        assertEquals(1, engine.releaseCalls)
        assertEquals(2, engine.startCalls)
        close(session)
    }

    @Test
    fun `one live reconnect action retries indefinitely until progress`() = runTest {
        val engine = FakeEngine { start, input, events ->
            when (start) {
                2, 3 -> events.emit(
                    PlaybackEvent.Failed(
                        input.generation,
                        PlaybackFailure(
                            FailureCode.NETWORK_TIMEOUT,
                            FailureDomain.NETWORK,
                            FailurePhase.RECOVERY,
                            Retryability.RETRYABLE_WITH_FRESH_REQUEST,
                        ),
                    ),
                )
                4 -> events.emit(PlaybackEvent.FirstVideoFrame(input.generation))
            }
        }
        var resolutions = 0
        val resolver = PlaybackRequestResolver { request ->
            resolutions++
            PlaybackResult.Success(request.resolved())
        }
        val session = session(engine, requestResolver = resolver)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()
        engine.releaseFailuresRemaining = 1

        engine.emit(PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF))
        advanceUntilIdle()

        assertEquals(4, engine.startCalls)
        assertEquals(4, resolutions)
        assertTrue(engine.releaseCalls >= 4)
        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)
        close(session)
    }

    @Test
    fun `failed VOD recovery releases the connection before becoming terminal`() = runTest {
        val fatalRecovery = PlaybackFailure(
            FailureCode.VIDEO_DECODER_FAILED,
            FailureDomain.VIDEO_DECODER,
            FailurePhase.RECOVERY,
            Retryability.FATAL,
            deterministic = true,
        )
        val engine = FakeEngine { start, input, events ->
            if (start == 2) events.emit(PlaybackEvent.Failed(input.generation, fatalRecovery))
        }
        val session = session(engine)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(vodRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()
        engine.releaseFailuresRemaining = 1

        engine.emit(
            PlaybackEvent.Failed(
                1,
                PlaybackFailure(
                    FailureCode.NETWORK_TIMEOUT,
                    FailureDomain.NETWORK,
                    FailurePhase.PLAYBACK,
                    Retryability.RETRYABLE_IN_PLACE,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(PlaybackState.FAILED, session.snapshot.value.state)
        assertEquals(fatalRecovery, session.snapshot.value.failure)
        assertEquals(0, engine.activeConnections)
        assertTrue(engine.releaseCalls >= 3)
        close(session)
    }

    @Test
    fun `handoff retries an explicit release failure before opening the fallback engine`() = runTest {
        val media3 = FakeEngine(type = EngineType.MEDIA3)
        var fallbackStartedWhilePrimaryConnected = false
        val mpv = FakeEngine(type = EngineType.LIBMPV) { _, _, _ ->
            fallbackStartedWhilePrimaryConnected = media3.activeConnections > 0
        }
        val session = session(
            engine = media3,
            otherEngine = mpv,
            requirementsResolver = PlaybackRequirementsResolver {
                PlaybackResult.Success(requirements(setOf(EngineType.MEDIA3, EngineType.LIBMPV)))
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(vodRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        media3.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()
        media3.releaseFailuresRemaining = 1

        media3.emit(
            PlaybackEvent.Failed(
                1,
                PlaybackFailure(
                    FailureCode.VIDEO_DECODER_FAILED,
                    FailureDomain.VIDEO_DECODER,
                    FailurePhase.PLAYBACK,
                    Retryability.HANDOFF_ELIGIBLE,
                    deterministic = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(2, media3.releaseCalls)
        assertEquals(1, mpv.startCalls)
        assertFalse(fallbackStartedWhilePrimaryConnected)
        close(session)
    }

    @Test
    fun `inactive lifecycle cancels reconnect attempts and active restarts one loop`() = runTest {
        val lifecycleEvents = MutableSharedFlow<PlaybackLifecycleEvent>(extraBufferCapacity = 4)
        val engine = FakeEngine { start, input, events ->
            when (start) {
                2 -> events.emit(
                    PlaybackEvent.Failed(
                        input.generation,
                        PlaybackFailure(
                            FailureCode.NETWORK_TIMEOUT,
                            FailureDomain.NETWORK,
                            FailurePhase.RECOVERY,
                            Retryability.RETRYABLE_WITH_FRESH_REQUEST,
                        ),
                    ),
                )
                3 -> events.emit(PlaybackEvent.FirstVideoFrame(input.generation))
            }
        }
        val session = session(
            engine,
            lifecycle = PlaybackLifecyclePort { lifecycleEvents },
        )
        runCurrent()
        lifecycleEvents.emit(PlaybackLifecycleEvent.ACTIVE)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        engine.emit(PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF))
        runCurrent()
        assertEquals(2, engine.startCalls)
        lifecycleEvents.emit(PlaybackLifecycleEvent.INACTIVE)
        runCurrent()
        val startsWhileInactive = engine.startCalls
        runCurrent()
        assertEquals(startsWhileInactive, engine.startCalls)

        lifecycleEvents.emit(PlaybackLifecycleEvent.ACTIVE)
        advanceUntilIdle()
        assertEquals(3, engine.startCalls)
        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)
        close(session)
    }

    @Test
    fun `release timeout never opens another connection and retries the barrier`() = runTest {
        val engine = FakeEngine()
        engine.releaseTimeoutsRemaining = 1
        val session = session(engine, releaseTimeoutMs = 1_000L)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        val close = launch { session.release() }
        advanceUntilIdle()
        close.join()

        assertEquals(2, engine.releaseCalls)
        assertEquals(1, engine.maxActiveConnections)
        assertEquals(PlaybackState.STOPPED, session.snapshot.value.state)
    }

    private fun TestScope.session(
        engine: FakeEngine,
        otherEngine: FakeEngine? = null,
        requestResolver: PlaybackRequestResolver = PlaybackRequestResolver { request ->
            PlaybackResult.Success(request.resolved())
        },
        requirementsResolver: PlaybackRequirementsResolver = PlaybackRequirementsResolver {
            PlaybackResult.Success(requirements())
        },
        lifecycle: PlaybackLifecyclePort? = null,
        releaseTimeoutMs: Long = 5_000L,
    ) = PlaybackSession(
        parentScope = this,
        requestResolver = requestResolver,
        requirementsResolver = requirementsResolver,
        graphProvider = PlaybackGraphProvider {
            PlaybackResult.Success(
                if (otherEngine == null) listOf(media3Graph) else listOf(media3Graph, mpvGraph),
            )
        },
        engineRegistry = PlaybackEngineRegistry { type ->
            when (type) {
                engine.type -> engine
                otherEngine?.type -> otherEngine
                else -> null
            }
        },
        outputController = FakeOutputController,
        clock = TestClock,
        diagnostics = PlaybackDiagnostics { },
        lifecycle = lifecycle,
        releaseTimeoutMs = releaseTimeoutMs,
    )

    private suspend fun close(session: PlaybackSession) {
        session.release()
    }

    private class FakeEngine(
        override val type: EngineType = EngineType.MEDIA3,
        private val onStart: suspend (Int, PlaybackEngineStart, MutableSharedFlow<PlaybackEvent>) -> Unit =
            { _, _, _ -> },
    ) : PlaybackEngine {
        private val eventFlow = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
        override val events: Flow<PlaybackEvent> = eventFlow
        var startCalls = 0
        var releaseCalls = 0
        var activeConnections = 0
        var maxActiveConnections = 0
        var releaseTimeoutsRemaining = 0
        var releaseFailuresRemaining = 0

        override suspend fun attachSurface(
            generation: Long,
            graph: PlaybackGraph,
        ): PlaybackResult<Unit> = PlaybackResult.Success(Unit)

        override suspend fun detachSurface(generation: Long): PlaybackResult<Unit> =
            PlaybackResult.Success(Unit)

        override suspend fun start(input: PlaybackEngineStart): PlaybackResult<Unit> {
            startCalls++
            activeConnections++
            maxActiveConnections = maxOf(maxActiveConnections, activeConnections)
            onStart(startCalls, input, eventFlow)
            return PlaybackResult.Success(Unit)
        }

        override suspend fun setPaused(generation: Long, paused: Boolean): PlaybackResult<Unit> =
            PlaybackResult.Success(Unit)

        override suspend fun applyRequirements(
            generation: Long,
            requirements: PlaybackRequirements,
        ): PlaybackResult<Unit> = PlaybackResult.Success(Unit)

        override suspend fun release(generation: Long): PlaybackResult<Unit> {
            releaseCalls++
            if (releaseTimeoutsRemaining > 0) {
                releaseTimeoutsRemaining--
                awaitCancellation()
            }
            if (releaseFailuresRemaining > 0) {
                releaseFailuresRemaining--
                return PlaybackResult.Failure(
                    PlaybackFailure(
                        FailureCode.UNKNOWN,
                        FailureDomain.UNKNOWN,
                        FailurePhase.RELEASE,
                        Retryability.FATAL,
                    ),
                )
            }
            activeConnections = 0
            return PlaybackResult.Success(Unit)
        }

        suspend fun emit(event: PlaybackEvent) {
            eventFlow.emit(event)
        }
    }

    private data object FakeOutputController : PlaybackOutputController {
        override suspend fun apply(
            generation: Long,
            requirements: PlaybackRequirements,
        ): PlaybackResult<Unit> = PlaybackResult.Success(Unit)

        override suspend fun reset(generation: Long): PlaybackResult<Unit> = PlaybackResult.Success(Unit)
    }

    private data object TestClock : PlaybackClock {
        override fun nowEpochMs(): Long = 0
        override suspend fun delayMs(durationMs: Long) {
            delay(durationMs)
        }
    }

    private fun PlaybackRequest.resolved() = ResolvedPlaybackRequest(this, summary(), StreamEvidence())

    private companion object {
        val liveRequest = PlaybackRequest("https://example.invalid/live", contentType = ContentType.LIVE)
        val vodRequest = PlaybackRequest("https://example.invalid/vod", contentType = ContentType.VOD)
        val secondLiveRequest = PlaybackRequest("https://example.invalid/live-2", contentType = ContentType.LIVE)
        val thirdLiveRequest = PlaybackRequest("https://example.invalid/live-3", contentType = ContentType.LIVE)
        val media3Graph = PlaybackGraph(
            id = "media3",
            engine = EngineType.MEDIA3,
            outputProfile = GraphOutputProfile.MEDIA3_STANDARD,
            decoderMode = DecoderMode.HARDWARE,
            audioMode = AudioMode.DECODE,
            surfaceMode = SurfaceMode.SURFACE_VIEW,
        )
        val mpvGraph = PlaybackGraph(
            id = "mpv",
            engine = EngineType.LIBMPV,
            outputProfile = GraphOutputProfile.MPV_RENDER,
            decoderMode = DecoderMode.HARDWARE,
            audioMode = AudioMode.DECODE,
            surfaceMode = SurfaceMode.GPU_RENDER,
        )

        fun requirements(
            eligibleEngines: Set<EngineType> = setOf(EngineType.MEDIA3),
        ) = PlaybackRequirements(
            profile = SessionProfile.FULLSCREEN,
            priority = SessionPriority.QUALITY_AND_STABILITY,
            qualityIntent = VideoQualityIntent.FULL,
            displayModeSwitchAllowed = true,
            frameRatePreference = FrameRatePreference.OFF,
            hdrPreference = HdrPreference.AUTO,
            decoderPreference = DecoderPreference.AUTO,
            softwareDecodeFallbackAllowed = false,
            subtitleFidelity = SubtitleFidelity.COMPATIBLE,
            subtitlesEnabled = false,
            audioOutput = AudioOutputPreference.AUTO,
            pcmProcessingAllowed = true,
            buffering = BufferingPreference.RECOMMENDED,
            gpuRenderingAllowed = true,
            eligibleEngines = eligibleEngines,
            secureOutputRequired = false,
            resourceBudget = ResourceBudget(),
        )
    }
}
