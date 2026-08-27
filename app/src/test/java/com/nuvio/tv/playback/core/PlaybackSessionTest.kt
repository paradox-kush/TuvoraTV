package com.nuvio.tv.playback.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
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
    fun `engine start receives the resolved stream evidence and exact network intent`() = runTest {
        val evidence = StreamEvidence(
            delivery = EvidenceFact(
                DeliveryType.RAW_TRANSPORT_STREAM,
                EvidenceProvenance.PROVIDER_DECLARED,
            ),
            container = EvidenceFact(ContainerType.MPEG_TS, EvidenceProvenance.PROVIDER_DECLARED),
        )
        val request = PlaybackRequest(
            url = "https://example.invalid/raw-live",
            contentType = ContentType.LIVE,
            network = PlaybackNetworkRequest(
                proxyMode = ProxyMode.DIRECT,
                connectTimeoutMs = 8_000,
                readTimeoutMs = 90_000,
                transientLoadRetryPolicy = TransientLoadRetryPolicy.SESSION_ONLY,
            ),
        )
        val engine = FakeEngine()
        val session = session(
            engine,
            requestResolver = PlaybackRequestResolver {
                PlaybackResult.Success(ResolvedPlaybackRequest(it, it.summary(), evidence))
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(request, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertEquals(evidence, engine.lastStartInput?.evidence)
        assertTrue(engine.lastStartInput?.request === request)
        assertEquals(ProxyMode.DIRECT, engine.lastStartInput?.request?.network?.proxyMode)
        close(session)
    }

    @Test
    fun `session owns profile impact and applies same graph quality promotion in place`() = runTest {
        val engine = FakeEngine()
        val capturedProfiles = mutableListOf<SessionProfile>()
        val adaptiveEvidence = StreamEvidence(
            delivery = EvidenceFact(DeliveryType.HLS, EvidenceProvenance.MANIFEST_CONFIRMED),
            adaptive = EvidenceFact(true, EvidenceProvenance.MANIFEST_CONFIRMED),
        )
        val session = session(
            engine = engine,
            requestResolver = PlaybackRequestResolver { request ->
                PlaybackResult.Success(ResolvedPlaybackRequest(request, request.summary(), adaptiveEvidence))
            },
            environmentProvider = PlaybackEnvironmentProvider { _, _, profile, _ ->
                capturedProfiles += profile
                PlaybackResult.Success(
                    environment().copy(
                        previewViewport = VideoDimensions(640, 360),
                        eligibleEngines = setOf(EngineType.MEDIA3),
                        preferredEngineOrder = listOf(EngineType.MEDIA3),
                        allowedSurfaceModes = setOf(SurfaceMode.SURFACE_VIEW),
                    ),
                )
            },
            requirementsResolver = DefaultPlaybackRequirementsResolver(),
        )
        val stablePreferences = PlaybackPreferences(
            buffering = BufferingPreference.LOW_LATENCY_LIVE,
            display = DisplayPreference(frameRate = FrameRatePreference.OFF),
        )
        session.dispatch(PlaybackCommand.PreferencesChanged(stablePreferences))
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.GUIDE))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        session.dispatch(PlaybackCommand.SessionProfileChanged(SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertEquals(listOf(SessionProfile.GUIDE, SessionProfile.FULLSCREEN), capturedProfiles)
        assertEquals(SessionProfile.FULLSCREEN, session.snapshot.value.profile)
        assertEquals(1, engine.startCalls)
        assertEquals(0, engine.releaseCalls)
        assertEquals(1, engine.applyRequirementsCalls)
        assertEquals(SessionProfile.FULLSCREEN, engine.lastAppliedRequirements?.profile)
        close(session)
    }

    @Test
    fun `profile requirements that change graph release before reselecting engine`() = runTest {
        val media3 = FakeEngine(type = EngineType.MEDIA3)
        var fallbackStartedWhilePrimaryConnected = false
        val mpv = FakeEngine(type = EngineType.LIBMPV) { _, _, _ ->
            fallbackStartedWhilePrimaryConnected = media3.activeConnections > 0
        }
        val session = session(
            engine = media3,
            otherEngine = mpv,
            environmentProvider = PlaybackEnvironmentProvider { _, _, profile, _ ->
                PlaybackResult.Success(
                    environment().copy(
                        eligibleEngines = if (profile == SessionProfile.GUIDE) {
                            setOf(EngineType.MEDIA3)
                        } else {
                            setOf(EngineType.LIBMPV)
                        },
                        allowedSurfaceModes = if (profile == SessionProfile.GUIDE) {
                            setOf(SurfaceMode.SURFACE_VIEW)
                        } else {
                            setOf(SurfaceMode.GPU_RENDER)
                        },
                    ),
                )
            },
            requirementsResolver = PlaybackRequirementsResolver { input ->
                val eligible = input.environment.eligibleEngines
                PlaybackResult.Success(
                    requirements(eligible, input.profile).copy(
                        preferredEngineOrder = eligible.toList(),
                        allowedSurfaceModes = input.environment.allowedSurfaceModes,
                        gpuRenderingAllowed = input.profile == SessionProfile.FULLSCREEN,
                    ),
                )
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.GUIDE))
        advanceUntilIdle()
        media3.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        session.dispatch(PlaybackCommand.SessionProfileChanged(SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertEquals(1, media3.releaseCalls)
        assertEquals(1, mpv.startCalls)
        assertFalse(fallbackStartedWhilePrimaryConnected)
        assertEquals(SessionProfile.FULLSCREEN, session.snapshot.value.profile)
        close(session)
    }

    @Test
    fun `newer profile command discards stale environment resolution`() = runTest {
        val engine = FakeEngine()
        val fullscreenEntered = CompletableDeferred<Unit>()
        val releaseFullscreen = CompletableDeferred<Unit>()
        val session = session(
            engine = engine,
            environmentProvider = PlaybackEnvironmentProvider { _, _, profile, _ ->
                if (profile == SessionProfile.FULLSCREEN) {
                    fullscreenEntered.complete(Unit)
                    releaseFullscreen.await()
                }
                PlaybackResult.Success(environment())
            },
            requirementsResolver = PlaybackRequirementsResolver { input ->
                PlaybackResult.Success(requirements(profile = input.profile))
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.GUIDE))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        session.dispatch(PlaybackCommand.SessionProfileChanged(SessionProfile.FULLSCREEN))
        runCurrent()
        fullscreenEntered.await()
        session.dispatch(PlaybackCommand.SessionProfileChanged(SessionProfile.GUIDE))
        runCurrent()
        releaseFullscreen.complete(Unit)
        advanceUntilIdle()

        assertEquals(SessionProfile.GUIDE, session.snapshot.value.profile)
        assertEquals(1, engine.startCalls)
        assertEquals(0, engine.releaseCalls)
        assertEquals(0, engine.applyRequirementsCalls)
        close(session)
    }

    @Test
    fun `rejected reversal restores last committed profile not discarded optimistic target`() = runTest {
        val engine = FakeEngine()
        val fullscreenEntered = CompletableDeferred<Unit>()
        val releaseFullscreen = CompletableDeferred<Unit>()
        var guideSnapshots = 0
        val rejected = PlaybackFailure(
            FailureCode.NO_ELIGIBLE_GRAPH,
            FailureDomain.DEVICE_RESOURCE,
            FailurePhase.PLAYBACK,
            Retryability.FATAL,
            deterministic = true,
        )
        val session = session(
            engine = engine,
            environmentProvider = PlaybackEnvironmentProvider { _, _, profile, _ ->
                when (profile) {
                    SessionProfile.FULLSCREEN -> {
                        fullscreenEntered.complete(Unit)
                        releaseFullscreen.await()
                        PlaybackResult.Success(environment())
                    }
                    SessionProfile.GUIDE -> {
                        guideSnapshots++
                        if (guideSnapshots == 1) PlaybackResult.Success(environment())
                        else PlaybackResult.Failure(rejected)
                    }
                }
            },
            requirementsResolver = PlaybackRequirementsResolver { input ->
                PlaybackResult.Success(requirements(profile = input.profile))
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.GUIDE))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        session.dispatch(PlaybackCommand.SessionProfileChanged(SessionProfile.FULLSCREEN))
        runCurrent()
        fullscreenEntered.await()
        session.dispatch(PlaybackCommand.SessionProfileChanged(SessionProfile.GUIDE))
        runCurrent()
        releaseFullscreen.complete(Unit)
        advanceUntilIdle()

        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)
        assertEquals(SessionProfile.GUIDE, session.snapshot.value.profile)
        assertEquals(0, engine.releaseCalls)
        close(session)
    }

    @Test
    fun `newer preference apply cancels and joins older apply before committing`() = runTest {
        val firstApplyEntered = CompletableDeferred<Unit>()
        val engine = FakeEngine(
            onApply = { effective ->
                if (effective.audioDelayMs == 100L) {
                    firstApplyEntered.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        val session = session(
            engine = engine,
            requirementsResolver = PlaybackRequirementsResolver { input ->
                PlaybackResult.Success(
                    requirements(profile = input.profile).copy(
                        preferredAudioLanguage = input.effectivePreferences.audio.preferredLanguage,
                        audioDelayMs = input.effectivePreferences.audio.delayMs,
                    ),
                )
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        session.dispatch(
            PlaybackCommand.PreferencesChanged(
                PlaybackPreferences(audio = AudioPreference(preferredLanguage = "eng", delayMs = 100)),
            ),
        )
        runCurrent()
        firstApplyEntered.await()
        session.dispatch(
            PlaybackCommand.PreferencesChanged(
                PlaybackPreferences(audio = AudioPreference(preferredLanguage = "spa", delayMs = 200)),
            ),
        )
        advanceUntilIdle()

        assertEquals(2, engine.applyRequirementsCalls)
        assertEquals("spa", engine.lastAppliedRequirements?.preferredAudioLanguage)
        assertEquals(200L, engine.lastAppliedRequirements?.audioDelayMs)
        assertEquals(0, engine.releaseCalls)
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
        assertEquals(1, engine.releaseCalls)
        assertEquals(1, engine.hardAbortCalls)
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
        assertTrue(engine.releaseCalls >= 3)
        assertEquals(1, engine.hardAbortCalls)
        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)
        close(session)
    }

    @Test
    fun `failed hard abort during live reconnect becomes terminal without reopening provider`() = runTest {
        val engine = FakeEngine()
        var resolutions = 0
        val session = session(
            engine,
            requestResolver = PlaybackRequestResolver { request ->
                resolutions++
                PlaybackResult.Success(request.resolved())
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()
        engine.releaseFailuresRemaining = 10
        engine.hardAbortFailuresRemaining = 10

        engine.emit(PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF))
        advanceUntilIdle()

        assertEquals(PlaybackState.FAILED, session.snapshot.value.state)
        assertEquals(FailureCode.RESOURCE_RELEASE_FAILED, session.snapshot.value.failure?.code)
        assertEquals(1, resolutions)
        assertEquals(1, engine.startCalls)
        assertEquals(1, engine.maxActiveConnections)
        assertEquals(1, engine.activeConnections)
        close(session)
    }

    @Test
    fun `failed hard abort during fresh VOD recovery never resolves or reopens provider`() = runTest {
        val engine = FakeEngine()
        var resolutions = 0
        val session = session(
            engine,
            requestResolver = PlaybackRequestResolver { request ->
                resolutions++
                PlaybackResult.Success(request.resolved())
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(vodRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()
        engine.releaseFailuresRemaining = 10
        engine.hardAbortFailuresRemaining = 10

        engine.emit(
            PlaybackEvent.Failed(
                1,
                PlaybackFailure(
                    FailureCode.NETWORK_TIMEOUT,
                    FailureDomain.NETWORK,
                    FailurePhase.PLAYBACK,
                    Retryability.RETRYABLE_WITH_FRESH_REQUEST,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(PlaybackState.FAILED, session.snapshot.value.state)
        assertEquals(FailureCode.RESOURCE_RELEASE_FAILED, session.snapshot.value.failure?.code)
        assertEquals(1, resolutions)
        assertEquals(1, engine.startCalls)
        assertEquals(1, engine.maxActiveConnections)
        assertEquals(1, engine.activeConnections)
        close(session)
    }

    @Test
    fun `video reconnect does not succeed from audio without a rendered frame`() = runTest {
        val engine = FakeEngine { start, input, events ->
            if (start == 2) {
                events.emit(
                    PlaybackEvent.TracksAvailable(
                        input.generation,
                        hasVideo = true,
                        audioTrackCount = 1,
                        subtitleTrackCount = 0,
                    ),
                )
                events.emit(PlaybackEvent.FirstAudio(input.generation))
            }
        }
        val session = session(engine)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        engine.emit(PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF))
        runCurrent()
        advanceTimeBy(1)
        runCurrent()

        assertEquals(2, engine.startCalls)
        assertEquals(PlaybackState.LIVE_RECONNECTING, session.snapshot.value.state)
        assertTrue(session.snapshot.value.progress.renderedAudio)

        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

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
        assertTrue(engine.releaseCalls >= 2)
        assertEquals(1, engine.hardAbortCalls)
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

        assertEquals(1, media3.releaseCalls)
        assertEquals(1, media3.hardAbortCalls)
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

        assertEquals(1, engine.releaseCalls)
        assertEquals(1, engine.hardAbortCalls)
        assertEquals(1, engine.maxActiveConnections)
        assertEquals(PlaybackState.STOPPED, session.snapshot.value.state)
    }

    @Test
    fun `failed hard abort fails closed without opening the next provider request`() = runTest {
        val engine = FakeEngine().apply {
            releaseFailuresRemaining = 1
            hardAbortFailuresRemaining = 1
        }
        val session = session(engine)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        session.dispatch(PlaybackCommand.Zap(secondLiveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertEquals(PlaybackState.FAILED, session.snapshot.value.state)
        assertEquals(FailureCode.RESOURCE_RELEASE_FAILED, session.snapshot.value.failure?.code)
        assertEquals(1, engine.startCalls)
        assertEquals(1, engine.activeConnections)
        assertEquals(1, engine.releaseCalls)
        assertEquals(1, engine.hardAbortCalls)

        close(session)
    }

    private fun TestScope.session(
        engine: FakeEngine,
        otherEngine: FakeEngine? = null,
        requestResolver: PlaybackRequestResolver = PlaybackRequestResolver { request ->
            PlaybackResult.Success(request.resolved())
        },
        environmentProvider: PlaybackEnvironmentProvider = PlaybackEnvironmentProvider { _, _, _, _ ->
            PlaybackResult.Success(environment())
        },
        requirementsResolver: PlaybackRequirementsResolver = PlaybackRequirementsResolver {
            PlaybackResult.Success(requirements())
        },
        lifecycle: PlaybackLifecyclePort? = null,
        releaseTimeoutMs: Long = 5_000L,
    ) = PlaybackSession(
        parentScope = this,
        requestResolver = requestResolver,
        environmentProvider = environmentProvider,
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
        private val onApply: suspend (PlaybackRequirements) -> Unit = { },
        private val onStart: suspend (Int, PlaybackEngineStart, MutableSharedFlow<PlaybackEvent>) -> Unit =
            { _, _, _ -> },
    ) : PlaybackEngine {
        private val eventFlow = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
        override val events: Flow<PlaybackEvent> = eventFlow
        var startCalls = 0
        var lastStartInput: PlaybackEngineStart? = null
        var applyRequirementsCalls = 0
        var lastAppliedRequirements: PlaybackRequirements? = null
        var releaseCalls = 0
        var hardAbortCalls = 0
        var activeConnections = 0
        var maxActiveConnections = 0
        var releaseTimeoutsRemaining = 0
        var releaseFailuresRemaining = 0
        var hardAbortFailuresRemaining = 0

        override suspend fun attachSurface(
            generation: Long,
            graph: PlaybackGraph,
        ): PlaybackResult<Unit> = PlaybackResult.Success(Unit)

        override suspend fun detachSurface(generation: Long): PlaybackResult<Unit> =
            PlaybackResult.Success(Unit)

        override suspend fun start(input: PlaybackEngineStart): PlaybackResult<Unit> {
            startCalls++
            lastStartInput = input
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
        ): PlaybackResult<Unit> {
            applyRequirementsCalls++
            onApply(requirements)
            lastAppliedRequirements = requirements
            return PlaybackResult.Success(Unit)
        }

        override suspend fun snapshotMetrics(
            generation: Long,
        ): PlaybackResult<PlaybackEngineMetricsSnapshot> = PlaybackResult.Success(
            PlaybackEngineMetricsSnapshot(
                generation = generation,
                videoFramesRendered = null,
                videoFramesSkipped = null,
                videoFramesDropped = null,
                audioBuffersRendered = null,
                audioBuffersSkipped = null,
                audioBuffersDropped = null,
            ),
        )

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

        override suspend fun hardAbort(generation: Long): PlaybackResult<Unit> {
            hardAbortCalls++
            if (hardAbortFailuresRemaining > 0) {
                hardAbortFailuresRemaining--
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
            profile: SessionProfile = SessionProfile.FULLSCREEN,
        ) = PlaybackRequirements(
            profile = profile,
            priority = if (profile == SessionProfile.GUIDE) {
                SessionPriority.STARTUP_SPEED
            } else {
                SessionPriority.QUALITY_AND_STABILITY
            },
            qualityIntent = if (profile == SessionProfile.GUIDE) {
                VideoQualityIntent.PREVIEW
            } else {
                VideoQualityIntent.FULL
            },
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

        fun environment() = PlaybackEnvironmentSnapshot(
            runtimeCapabilities = RuntimeCapabilities(
                snapshotVersion = 1,
                capturedAtEpochMs = 1,
                apiLevel = 36,
                display = DisplayCapabilities(VideoDimensions(1920, 1080)),
                audioRoute = AudioRouteCapabilities(AudioRoute.TV_SPEAKERS),
                resources = ResourceCapabilities(1_000_000_000, lowMemory = false),
                surfaces = SurfaceCapabilities(),
            ),
            secureOutputRequired = false,
        )
    }
}
