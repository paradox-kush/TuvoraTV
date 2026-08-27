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
    fun `video success records exact graph once with decoder identity and explicit expiry`() = runTest {
        val history = FakeCompatibilityHistory()
        val engine = FakeEngine()
        val scope = CompatibilityScopeKey("hashed-scope")
        val session = session(
            engine = engine,
            requestResolver = scopedResolver(scope),
            compatibilityRecording = compatibilityRecording(history),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        engine.emit(PlaybackEvent.VideoDecoderInitialized(1, "c2.android.avc.decoder"))
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        val record = history.values.single()
        assertEquals(scope, record.scopeKey)
        assertEquals(CompatibilityOutcome.SUCCESS, record.outcome)
        assertEquals("c2.android.avc.decoder", record.graph.decoderStableId)
        assertEquals(10_000L, record.expiresAtEpochMs)
        close(session)
    }

    @Test
    fun `audio success waits for tracks to prove audio-only`() = runTest {
        val history = FakeCompatibilityHistory()
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            requestResolver = scopedResolver(CompatibilityScopeKey("audio-only")),
            compatibilityRecording = compatibilityRecording(history),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        engine.emit(PlaybackEvent.FirstAudio(1))
        advanceUntilIdle()
        assertTrue(history.values.isEmpty())

        engine.emit(PlaybackEvent.TracksAvailable(1, hasVideo = false, audioTrackCount = 1, subtitleTrackCount = 0))
        advanceUntilIdle()
        assertEquals(listOf(CompatibilityOutcome.SUCCESS), history.values.map { it.outcome })
        close(session)
    }

    @Test
    fun `audio callback on a video stream never records success`() = runTest {
        val history = FakeCompatibilityHistory()
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            requestResolver = scopedResolver(CompatibilityScopeKey("video-with-audio")),
            compatibilityRecording = compatibilityRecording(history),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        engine.emit(PlaybackEvent.TracksAvailable(1, hasVideo = true, audioTrackCount = 1, subtitleTrackCount = 0))
        engine.emit(PlaybackEvent.FirstAudio(1))
        advanceUntilIdle()

        assertTrue(history.values.isEmpty())
        close(session)
    }

    @Test
    fun `later learnable deterministic fatal is recorded after success for the same graph`() = runTest {
        val history = FakeCompatibilityHistory()
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            requestResolver = scopedResolver(CompatibilityScopeKey("success-then-fatal")),
            compatibilityRecording = compatibilityRecording(history),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        engine.emit(
            PlaybackEvent.Failed(
                1,
                PlaybackFailure(
                    FailureCode.VIDEO_DECODER_FAILED,
                    FailureDomain.VIDEO_DECODER,
                    FailurePhase.PLAYBACK,
                    Retryability.FATAL,
                    deterministic = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf(CompatibilityOutcome.SUCCESS, CompatibilityOutcome.DETERMINISTIC_FATAL),
            history.values.map { it.outcome },
        )
        close(session)
    }

    @Test
    fun `stale generation and released lifecycle events cannot record compatibility`() = runTest {
        val history = FakeCompatibilityHistory()
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            requestResolver = PlaybackRequestResolver { request ->
                PlaybackResult.Success(
                    request.resolved(CompatibilityScopeKey("scope-${request.url}")),
                )
            },
            compatibilityRecording = compatibilityRecording(history),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        session.dispatch(PlaybackCommand.Zap(secondLiveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        engine.emit(
            PlaybackEvent.Failed(
                1,
                PlaybackFailure(
                    FailureCode.VIDEO_DECODER_FAILED,
                    FailureDomain.VIDEO_DECODER,
                    FailurePhase.PLAYBACK,
                    Retryability.FATAL,
                    deterministic = true,
                ),
            ),
        )
        advanceUntilIdle()
        assertTrue(history.values.isEmpty())

        session.dispatch(PlaybackCommand.Stop)
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(2))
        advanceUntilIdle()
        assertTrue(history.values.isEmpty())
        close(session)
    }

    @Test
    fun `history storage failure is diagnostic only and playback continues`() = runTest {
        val history = FakeCompatibilityHistory(failWrites = true)
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            requestResolver = scopedResolver(CompatibilityScopeKey("storage-failure")),
            compatibilityRecording = compatibilityRecording(history),
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)
        assertTrue(diagnostics.any { it.code == PlaybackDiagnosticCode.COMPATIBILITY_HISTORY_RECORD_FAILED })
        close(session)
    }

    @Test
    fun `missing scope or engine version makes recording a no-op`() = runTest {
        val missingScopeHistory = FakeCompatibilityHistory()
        val missingScopeEngine = FakeEngine()
        val missingScopeSession = session(
            engine = missingScopeEngine,
            compatibilityRecording = compatibilityRecording(missingScopeHistory),
        )
        missingScopeSession.dispatch(PlaybackCommand.SurfaceAvailable)
        missingScopeSession.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        missingScopeEngine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()
        assertTrue(missingScopeHistory.values.isEmpty())
        close(missingScopeSession)

        val missingVersionHistory = FakeCompatibilityHistory()
        val missingVersionEngine = FakeEngine()
        val environment = compatibilityRecording(missingVersionHistory).copy(engineVersions = emptyMap())
        val missingVersionSession = session(
            engine = missingVersionEngine,
            requestResolver = scopedResolver(CompatibilityScopeKey("missing-version")),
            compatibilityRecording = environment,
        )
        missingVersionSession.dispatch(PlaybackCommand.SurfaceAvailable)
        missingVersionSession.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        missingVersionEngine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()
        assertTrue(missingVersionHistory.values.isEmpty())
        close(missingVersionSession)
    }

    @Test
    fun `network authorization tls drm resource and unknown failures never record history`() = runTest {
        val excluded = listOf(
            FailureDomain.NETWORK to FailureCode.NETWORK_TIMEOUT,
            FailureDomain.AUTHORIZATION_PROVIDER_LIMIT to FailureCode.PROVIDER_CONNECTION_LIMIT,
            FailureDomain.TLS to FailureCode.TLS_HANDSHAKE_FAILED,
            FailureDomain.DRM to FailureCode.DRM_LICENSE_FAILED,
            FailureDomain.DEVICE_RESOURCE to FailureCode.RESOURCE_BUDGET_EXCEEDED,
            FailureDomain.AUDIO to FailureCode.AUDIO_OUTPUT_FAILED,
            FailureDomain.UNKNOWN to FailureCode.UNKNOWN,
        )
        excluded.forEachIndexed { index, (domain, code) ->
            val history = FakeCompatibilityHistory()
            val engine = FakeEngine()
            val session = session(
                engine = engine,
                requestResolver = scopedResolver(CompatibilityScopeKey("excluded-$index")),
                compatibilityRecording = compatibilityRecording(history),
            )
            session.dispatch(PlaybackCommand.SurfaceAvailable)
            session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
            advanceUntilIdle()
            engine.emit(
                PlaybackEvent.Failed(
                    1,
                    PlaybackFailure(
                        code,
                        domain,
                        FailurePhase.PLAYBACK,
                        Retryability.FATAL,
                        deterministic = true,
                    ),
                ),
            )
            advanceUntilIdle()
            assertTrue("$domain/$code", history.values.isEmpty())
            close(session)
        }
    }
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
    fun `deferred provider resolution starts only after the previous engine release proof`() = runTest {
        val engine = FakeEngine()
        var resolvedWhileConnected = false
        val selection = providerSelection("deferred")
        val session = session(
            engine = engine,
            providerPlaybackResolver = ProviderPlaybackResolver { selection, _ ->
                resolvedWhileConnected = engine.activeConnections > 0
                PlaybackResult.Success(providerResolved(selection))
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        assertEquals(1, engine.activeConnections)

        session.dispatch(PlaybackCommand.Zap(selection, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertFalse(resolvedWhileConnected)
        assertEquals(1, engine.releaseCalls)
        assertEquals(2, engine.startCalls)
        assertEquals(selection.contentKey.value, engine.lastStartInput?.request?.contentKey?.value)
        assertEquals(ContainerType.MPEG_TS, engine.lastStartInput?.evidence?.container?.value)
        close(session)
    }

    @Test
    fun `failed release barrier never invokes the deferred provider resolver`() = runTest {
        val engine = FakeEngine().apply {
            releaseFailuresRemaining = 1
            hardAbortFailuresRemaining = 1
        }
        var providerResolveCalls = 0
        val session = session(
            engine = engine,
            providerPlaybackResolver = ProviderPlaybackResolver { selection, _ ->
                providerResolveCalls++
                PlaybackResult.Success(providerResolved(selection))
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        session.dispatch(
            PlaybackCommand.Zap(providerSelection("blocked"), SessionProfile.FULLSCREEN),
        )
        advanceUntilIdle()

        assertEquals(PlaybackState.FAILED, session.snapshot.value.state)
        assertEquals(FailureCode.RESOURCE_RELEASE_FAILED, session.snapshot.value.failure?.code)
        assertEquals(0, providerResolveCalls)
        close(session)
    }

    @Test
    fun `superseded deferred resolution is cancelled and cannot start a stale generation`() = runTest {
        val engine = FakeEngine()
        val first = providerSelection("first")
        val second = providerSelection("second")
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val resolvedIds = mutableListOf<String>()
        val session = session(
            engine = engine,
            providerPlaybackResolver = ProviderPlaybackResolver { selection, _ ->
                if (selection.itemId == first.itemId) {
                    firstStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        firstCancelled.complete(Unit)
                    }
                }
                resolvedIds += selection.itemId.value
                PlaybackResult.Success(providerResolved(selection))
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(first, SessionProfile.FULLSCREEN))
        runCurrent()
        firstStarted.await()

        session.dispatch(PlaybackCommand.Zap(second, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertTrue(firstCancelled.isCompleted)
        assertEquals(listOf(second.itemId.value), resolvedIds)
        assertEquals(1, engine.startCalls)
        assertEquals(2L, engine.lastStartInput?.generation)
        assertEquals(second.contentKey.value, engine.lastStartInput?.request?.contentKey?.value)
        close(session)
    }

    @Test
    fun `deferred resolver failures remain typed secret-safe request-resolution facts`() = runTest {
        val selection = providerSelection("private-provider-token")
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val session = session(
            engine = FakeEngine(),
            providerPlaybackResolver = ProviderPlaybackResolver { _, _ ->
                PlaybackResult.Failure(
                    PlaybackFailure(
                        code = FailureCode.PROVIDER_CONNECTION_LIMIT,
                        domain = FailureDomain.AUTHORIZATION_PROVIDER_LIMIT,
                        phase = FailurePhase.PLAYBACK,
                        retryability = Retryability.FATAL,
                        deterministic = true,
                    ),
                )
            },
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        session.dispatch(PlaybackCommand.Tune(selection, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        val failure = session.snapshot.value.failure
        assertEquals(FailureCode.PROVIDER_CONNECTION_LIMIT, failure?.code)
        assertEquals(FailureDomain.AUTHORIZATION_PROVIDER_LIMIT, failure?.domain)
        assertEquals(FailurePhase.REQUEST_RESOLUTION, failure?.phase)
        assertFalse(diagnostics.joinToString().contains(selection.contentKey.value))
        close(session)
    }

    @Test
    fun `concrete resolver cannot change request identity or ownership metadata`() = runTest {
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            requestResolver = PlaybackRequestResolver { original ->
                val invalid = PlaybackRequest(
                    url = original.url,
                    contentType = ContentType.VOD,
                    providerConnectionLimit = 2,
                )
                PlaybackResult.Success(
                    ResolvedPlaybackRequest(
                        invalid,
                        original.summary(),
                        StreamEvidence(),
                    ),
                )
            },
        )

        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertEquals(PlaybackState.FAILED, session.snapshot.value.state)
        assertEquals(FailurePhase.REQUEST_RESOLUTION, session.snapshot.value.failure?.phase)
        assertEquals(0, engine.startCalls)
        close(session)
    }

    @Test
    fun `resolved summary is recomputed from the accepted concrete request`() = runTest {
        val request = PlaybackRequest(
            url = "https://example.invalid/live",
            headers = mapOf("Authorization" to "secret"),
            contentType = ContentType.LIVE,
        )
        val session = session(
            engine = FakeEngine(),
            requestResolver = PlaybackRequestResolver {
                PlaybackResult.Success(
                    ResolvedPlaybackRequest(
                        request,
                        request.summary().copy(hasAuthorization = false),
                        StreamEvidence(),
                    ),
                )
            },
        )

        session.dispatch(PlaybackCommand.Tune(request, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertTrue(session.snapshot.value.requestSummary?.hasAuthorization == true)
        close(session)
    }

    @Test
    fun `deferred handoff remints after release and decoder failure cannot advance catch-up dialect`() = runTest {
        val media3 = FakeEngine(type = EngineType.MEDIA3)
        val mpv = FakeEngine(type = EngineType.LIBMPV)
        val contexts = mutableListOf<ProviderResolutionContext>()
        var mint = 0
        val selection = providerSelection("handoff")
        val session = session(
            engine = media3,
            otherEngine = mpv,
            providerPlaybackResolver = ProviderPlaybackResolver { selected, context ->
                contexts += context
                mint++
                val request = PlaybackRequest(
                    url = "https://resolved.invalid/live/$mint",
                    contentType = selected.contentType,
                    contentKey = SecretValue(selected.contentKey.value),
                    providerConnectionLimit = selected.providerConnectionLimit,
                )
                PlaybackResult.Success(
                    ResolvedPlaybackRequest(request, request.summary(), StreamEvidence()),
                )
            },
            requirementsResolver = PlaybackRequirementsResolver {
                PlaybackResult.Success(requirements(setOf(EngineType.MEDIA3, EngineType.LIBMPV)))
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(selection, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        media3.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

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

        assertEquals(2, contexts.size)
        assertEquals(ProviderResolutionTrigger.HANDOFF, contexts.last().trigger)
        assertEquals(
            ProviderDialectAdvanceEligibility.INELIGIBLE_PLAYBACK_FAILURE,
            contexts.last().previousFailure?.dialectAdvanceEligibility,
        )
        assertEquals("https://resolved.invalid/live/2", mpv.lastStartInput?.request?.url)
        assertEquals(0, media3.activeConnections)
        close(session)
    }

    @Test
    fun `fresh live resolution commits current request facts and transport feedback`() = runTest {
        val contexts = mutableListOf<ProviderResolutionContext>()
        var mint = 0
        val engine = FakeEngine { start, input, events ->
            if (start == 2) events.emit(PlaybackEvent.FirstVideoFrame(input.generation))
        }
        val selection = providerSelection("fresh-live")
        val session = session(
            engine = engine,
            providerPlaybackResolver = ProviderPlaybackResolver { selected, context ->
                contexts += context
                mint++
                val request = PlaybackRequest(
                    url = "https://resolved.invalid/live/$mint",
                    cookies = if (mint == 2) mapOf("session" to "secret") else emptyMap(),
                    contentType = selected.contentType,
                    contentKey = SecretValue(selected.contentKey.value),
                    providerConnectionLimit = selected.providerConnectionLimit,
                )
                PlaybackResult.Success(
                    ResolvedPlaybackRequest(request, request.summary(), StreamEvidence()),
                )
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(selection, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

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

        assertEquals(2, contexts.size)
        assertEquals(
            ProviderDialectAdvanceEligibility.TRANSPORT_OR_DEMUX_FAILURE,
            contexts.last().previousFailure?.dialectAdvanceEligibility,
        )
        assertTrue(session.snapshot.value.requestSummary?.hasCookies == true)
        assertEquals("https://resolved.invalid/live/2", engine.lastStartInput?.request?.url)
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
    fun `no media bytes expires through network policy without blaming an engine`() = runTest {
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            policy = PlaybackPolicy(),
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(vodRequest, SessionProfile.FULLSCREEN))
        runCurrent()

        advanceTimeBy(PlaybackNetworkRequest.DEFAULT_READ_TIMEOUT_MS.toLong())
        runCurrent()

        val expired = diagnostics.single { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED }
        assertEquals(FailureCode.NETWORK_TIMEOUT, expired.failure?.code)
        assertEquals(FailureDomain.NETWORK, expired.failure?.domain)
        assertEquals(1, engine.releaseCalls)
        assertEquals(2, engine.startCalls)
        close(session)
    }

    @Test
    fun `bytes without HLS tracks classify manifest phase and issue one incident action`() = runTest {
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val hlsEvidence = StreamEvidence(
            delivery = EvidenceFact(DeliveryType.HLS, EvidenceProvenance.MANIFEST_CONFIRMED),
        )
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            requestResolver = PlaybackRequestResolver { request ->
                PlaybackResult.Success(ResolvedPlaybackRequest(request, request.summary(), hlsEvidence))
            },
            policy = PlaybackPolicy(),
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        engine.emit(PlaybackEvent.BytesReceived(1))
        runCurrent()

        advanceTimeBy(1_499)
        runCurrent()
        assertEquals(0, engine.releaseCalls)
        advanceTimeBy(1)
        runCurrent()

        val expired = diagnostics.single { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED }
        assertEquals(FailureCode.MANIFEST_INVALID, expired.failure?.code)
        assertEquals(FailureDomain.MANIFEST, expired.failure?.domain)
        assertEquals(1, engine.releaseCalls)
        close(session)
    }

    @Test
    fun `decoder ready without first video frame classifies renderer output`() = runTest {
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            policy = PlaybackPolicy(),
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        engine.emit(PlaybackEvent.TracksAvailable(1, hasVideo = true, 1, 0))
        engine.emit(PlaybackEvent.VideoDecoderInitialized(1, "decoder"))
        runCurrent()

        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, engine.releaseCalls)
        advanceTimeBy(1)
        runCurrent()

        val expired = diagnostics.single { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED }
        assertEquals(FailureCode.VIDEO_RENDERER_FAILED, expired.failure?.code)
        assertEquals(FailureDomain.VIDEO_RENDERER_SURFACE, expired.failure?.domain)
        assertEquals(1, engine.releaseCalls)
        close(session)
    }

    @Test
    fun `video tracks without decoder callback cannot wedge startup indefinitely`() = runTest {
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            policy = PlaybackPolicy(),
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        engine.emit(PlaybackEvent.TracksAvailable(1, hasVideo = true, 1, 0))
        runCurrent()

        advanceTimeBy(1_000)
        runCurrent()

        val expired = diagnostics.single { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED }
        assertEquals(FailureCode.VIDEO_DECODER_FAILED, expired.failure?.code)
        assertEquals(FailureDomain.VIDEO_DECODER, expired.failure?.domain)
        assertEquals(false, expired.failure?.deterministic)
        assertEquals(1, engine.releaseCalls)
        close(session)
    }

    @Test
    fun `runtime freeze uses continuing rendered frames rather than audio or playhead`() = runTest {
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val engine = FakeEngine().apply { videoFramesRendered = 10 }
        val session = session(
            engine = engine,
            policy = PlaybackPolicy(),
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        engine.emit(PlaybackEvent.TracksAvailable(1, hasVideo = true, 1, 0))
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()

        val expired = diagnostics.single { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED }
        assertEquals(FailureCode.NO_PROGRESS, expired.failure?.code)
        assertEquals(FailureDomain.VIDEO_RENDERER_SURFACE, expired.failure?.domain)
        assertEquals(2, engine.snapshotMetricsCalls)
        assertEquals(1, engine.releaseCalls)
        close(session)
    }

    @Test
    fun `advancing rendered frames rearm runtime observation without recovery`() = runTest {
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val engine = FakeEngine().apply { videoFramesRendered = 10 }
        val session = session(
            engine = engine,
            policy = PlaybackPolicy(),
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        engine.emit(PlaybackEvent.TracksAvailable(1, hasVideo = true, 1, 0))
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        runCurrent()

        engine.videoFramesRendered = 11
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)
        assertTrue(diagnostics.none { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED })
        assertEquals(0, engine.releaseCalls)
        close(session)
    }

    @Test
    fun `confirmed audio-only playback never arms a video watchdog`() = runTest {
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val engine = FakeEngine().apply { videoFramesRendered = 0 }
        val session = session(
            engine = engine,
            policy = PlaybackPolicy(),
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        engine.emit(PlaybackEvent.FirstAudio(1))
        engine.emit(PlaybackEvent.TracksAvailable(1, hasVideo = false, 1, 0))
        runCurrent()

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)
        assertEquals(0, engine.snapshotMetricsCalls)
        assertTrue(diagnostics.none { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED })
        close(session)
    }

    @Test
    fun `inactive lifecycle cancels the armed startup watchdog`() = runTest {
        val lifecycleEvents = MutableSharedFlow<PlaybackLifecycleEvent>(extraBufferCapacity = 2)
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            lifecycle = PlaybackLifecyclePort { lifecycleEvents },
            policy = PlaybackPolicy(),
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        runCurrent()
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        lifecycleEvents.emit(PlaybackLifecycleEvent.INACTIVE)
        runCurrent()

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(PlaybackState.STOPPED, session.snapshot.value.state)
        assertTrue(diagnostics.none { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED })
        close(session)
    }

    @Test
    fun `superseded generation watchdog cannot fail the replacement request`() = runTest {
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            policy = PlaybackPolicy(),
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()

        session.dispatch(PlaybackCommand.Zap(secondLiveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        engine.emit(PlaybackEvent.TracksAvailable(2, hasVideo = false, 1, 0))
        engine.emit(PlaybackEvent.FirstAudio(2))
        runCurrent()

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(2, session.snapshot.value.generation)
        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)
        assertTrue(diagnostics.none { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED })
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
        providerPlaybackResolver: ProviderPlaybackResolver? = null,
        compatibilityRecording: CompatibilityRecordingEnvironment? = null,
        releaseTimeoutMs: Long = 5_000L,
        policy: PlaybackPolicy = PlaybackPolicy(
            PlaybackPolicy.WatchdogConfiguration(enabled = false),
        ),
        diagnostics: PlaybackDiagnostics = PlaybackDiagnostics { },
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
        diagnostics = diagnostics,
        lifecycle = lifecycle,
        providerPlaybackResolver = providerPlaybackResolver,
        compatibilityRecording = compatibilityRecording,
        policy = policy,
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
        var snapshotMetricsCalls = 0
        var videoFramesRendered: Long? = null

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
        ): PlaybackResult<PlaybackEngineMetricsSnapshot> {
            snapshotMetricsCalls++
            return PlaybackResult.Success(
                PlaybackEngineMetricsSnapshot(
                generation = generation,
                videoFramesRendered = videoFramesRendered,
                videoFramesSkipped = null,
                videoFramesDropped = null,
                audioBuffersRendered = null,
                audioBuffersSkipped = null,
                audioBuffersDropped = null,
                ),
            )
        }

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

    private class FakeCompatibilityHistory(
        private val failWrites: Boolean = false,
    ) : PlaybackCompatibilityHistory {
        val values = mutableListOf<CompatibilityRecord>()

        override suspend fun records(scopeKey: CompatibilityScopeKey): List<CompatibilityRecord> =
            values.filter { it.scopeKey == scopeKey }

        override suspend fun record(value: CompatibilityRecord) {
            if (failWrites) error("storage unavailable")
            values += value
        }
    }

    private fun compatibilityRecording(history: PlaybackCompatibilityHistory) =
        CompatibilityRecordingEnvironment(
            history = history,
            runtime = CompatibilityRuntimeFingerprint("device", "firmware", "capabilities"),
            appVersion = "test-app",
            engineVersions = mapOf(
                EngineType.MEDIA3 to "media3-test",
                EngineType.LIBMPV to "mpv-test",
            ),
            successTtlMs = 10_000,
            fatalTtlMs = 5_000,
        )

    private fun scopedResolver(scope: CompatibilityScopeKey) = PlaybackRequestResolver { request ->
        PlaybackResult.Success(request.resolved(scope))
    }

    private fun PlaybackRequest.resolved(scope: CompatibilityScopeKey? = null) =
        ResolvedPlaybackRequest(
            this,
            summary(),
            StreamEvidence(),
            compatibilityScopeKey = scope,
        )

    private fun providerSelection(suffix: String) = ProviderPlaybackSelection(
        sourceType = ProviderSourceType.STALKER,
        accountId = ProviderSelectionId("account-$suffix"),
        itemId = ProviderSelectionId("item-$suffix"),
        contentKey = ProviderSelectionId("content-$suffix"),
        contentType = ContentType.LIVE,
        declaredEvidence = StreamEvidence(
            container = EvidenceFact(ContainerType.MPEG_TS, EvidenceProvenance.PROVIDER_DECLARED),
        ),
    )

    private fun providerResolved(selection: ProviderPlaybackSelection): ResolvedPlaybackRequest {
        val request = PlaybackRequest(
            url = "https://resolved.invalid/live",
            contentType = selection.contentType,
            contentKey = SecretValue(selection.contentKey.value),
            providerConnectionLimit = selection.providerConnectionLimit,
        )
        return ResolvedPlaybackRequest(request, request.summary(), StreamEvidence())
    }

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
