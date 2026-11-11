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
    fun `requirements receive the complete effective preferences from environment policy`() = runTest {
        val engine = FakeEngine()
        val effective = PlaybackPreferences(
            engine = EnginePreference.MEDIA3,
            buffering = BufferingPreference.CUSTOM,
            customBuffer = CustomBufferPreference(1_000, 12_000, 500, 1_000),
            audio = AudioPreference(
                output = AudioOutputPreference.PCM,
                downmixToStereo = true,
                normalization = true,
            ),
            subtitles = SubtitlePreference(fidelity = SubtitleFidelity.FULL),
            video = VideoPreference(hdr = HdrPreference.SDR),
        )
        var requirementsInput: PlaybackPreferences? = null
        val session = session(
            engine = engine,
            environmentProvider = PlaybackEnvironmentProvider { input ->
                PlaybackResult.Success(PlaybackEnvironmentResolution(environment(), effective))
            },
            requirementsResolver = PlaybackRequirementsResolver { input ->
                requirementsInput = input.effectivePreferences
                PlaybackResult.Success(requirements())
            },
        )

        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertEquals(effective, requirementsInput)
        close(session)
    }

    @Test
    fun `video success records exact graph once with decoder identity and explicit expiry`() = runTest {
        val history = FakeCompatibilityHistory()
        val engine = FakeEngine()
        val scope = CompatibilityScopeKey("hashed-scope")
        val environmentScopes = mutableListOf<CompatibilityScopeKey?>()
        val session = session(
            engine = engine,
            requestResolver = scopedResolver(scope),
            environmentProvider = PlaybackEnvironmentProvider { input ->
                environmentScopes += input.compatibilityScopeKey
                environmentResult(input)
            },
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
        assertEquals(listOf(scope), environmentScopes)
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

    // B50: even a host that wrongly marks it deterministic must not teach "this engine is broken".
    @Test
    fun `surface host failure never records engine compatibility`() = runTest {
        val history = FakeCompatibilityHistory()
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            requestResolver = scopedResolver(CompatibilityScopeKey("surface-host")),
            compatibilityRecording = compatibilityRecording(history),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.GUIDE))
        advanceUntilIdle()
        engine.emit(
            PlaybackEvent.Failed(
                1,
                PlaybackFailure(
                    FailureCode.SURFACE_HOST_UNAVAILABLE,
                    FailureDomain.VIDEO_RENDERER_SURFACE,
                    FailurePhase.SURFACE_ATTACHMENT,
                    Retryability.HANDOFF_ELIGIBLE,
                    deterministic = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertTrue("no compatibility record: ${history.values}", history.values.isEmpty())
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
        runCurrent()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        runCurrent()

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
    fun `a live picture freeze is remembered as a runtime instability strike`() = runTest {
        val history = FakeCompatibilityHistory()
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            requestResolver = scopedResolver(CompatibilityScopeKey("freezes")),
            compatibilityRecording = compatibilityRecording(history),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        engine.emit(
            PlaybackEvent.Failed(
                1,
                PlaybackFailure(
                    FailureCode.NO_PROGRESS,
                    FailureDomain.VIDEO_RENDERER_SURFACE,
                    FailurePhase.PLAYBACK,
                    Retryability.RETRYABLE_WITH_FRESH_REQUEST,
                ),
            ),
        )
        advanceUntilIdle()

        val strike = history.values.single { it.outcome == CompatibilityOutcome.RUNTIME_UNSTABLE }
        assertEquals(FailureCode.NO_PROGRESS, strike.failureCode)
        assertEquals(EngineType.MEDIA3, strike.engine)
        close(session)
    }

    @Test
    fun `many zaps retain compatibility bookkeeping for only the active generation`() = runTest {
        val history = FakeCompatibilityHistory()
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            requestResolver = scopedResolver(CompatibilityScopeKey("many-zaps")),
            compatibilityRecording = compatibilityRecording(history),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)

        repeat(20) { index ->
            val request = PlaybackRequest(
                url = "https://example.invalid/live/$index",
                contentType = ContentType.LIVE,
            )
            val command = if (index == 0) {
                PlaybackCommand.Tune(request, SessionProfile.FULLSCREEN)
            } else {
                PlaybackCommand.Zap(request, SessionProfile.FULLSCREEN)
            }
            session.dispatch(command)
            advanceUntilIdle()
            val generation = (index + 1).toLong()
            engine.emit(PlaybackEvent.VideoDecoderInitialized(generation, "decoder-$generation"))
            engine.emit(PlaybackEvent.FirstVideoFrame(generation))
            advanceUntilIdle()
        }

        val decoderIds = PlaybackSession::class.java.getDeclaredField("decoderStableIds")
            .apply { isAccessible = true }
            .get(session) as Map<*, *>
        val recordedOutcomes = PlaybackSession::class.java
            .getDeclaredField("recordedCompatibilityOutcomes")
            .apply { isAccessible = true }
            .get(session) as Set<*>
        assertEquals(1, decoderIds.size)
        assertEquals(1, recordedOutcomes.size)
        assertEquals(20, history.values.size)
        close(session)
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
    fun `replacement barrier releases the adapter generation before starting the next generation`() = runTest {
        val engine = FakeEngine()
        val session = session(engine)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.GUIDE))
        advanceUntilIdle()

        session.dispatch(PlaybackCommand.Zap(secondLiveRequest, SessionProfile.GUIDE))
        advanceUntilIdle()

        assertEquals(listOf(1L), engine.releaseGenerations)
        assertEquals(listOf(1L, 2L), engine.startGenerations)
        assertEquals(1, engine.maxActiveConnections)
        close(session)
    }

    @Test
    fun `graph selection rejection records why and the stream facts on the failure diagnostic`() = runTest {
        val evidence = StreamEvidence(
            delivery = EvidenceFact(DeliveryType.RAW_TRANSPORT_STREAM, EvidenceProvenance.PROVIDER_DECLARED),
            videoCodec = EvidenceFact(VideoCodec.MPEG2, EvidenceProvenance.PROVIDER_DECLARED),
        )
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val engine = FakeEngine()
        val session = session(
            engine,
            requestResolver = PlaybackRequestResolver {
                PlaybackResult.Success(ResolvedPlaybackRequest(it, it.summary(), evidence))
            },
            // The only candidate is Media3; requirements admit only libmpv.
            requirementsResolver = PlaybackRequirementsResolver {
                PlaybackResult.Success(requirements(eligibleEngines = setOf(EngineType.LIBMPV)))
            },
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        val failure = diagnostics.mapNotNull { it.failure }.single { it.code == FailureCode.NO_ELIGIBLE_GRAPH }
        val rejection = requireNotNull(failure.graphRejection) { "NO_ELIGIBLE_GRAPH must say why" }
        assertEquals(GraphRejectionReason.ENGINE_OR_SURFACE, rejection.rejectedBy)
        assertEquals(1, rejection.candidateGraphs)
        assertEquals(StreamFactCodes(codec = StreamCodecCode.MPEG2, container = StreamContainerCode.TS), rejection.stream)
        assertEquals(0, engine.startCalls)
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
    fun `first failed release barrier abandons the stuck player and the next channel plays`() = runTest {
        val engine = FakeEngine().apply {
            releaseFailuresRemaining = 1
            hardAbortFailuresRemaining = 1
            abandonSucceeds = true
            stepName = "DETACH_SURFACE"
        }
        val recorded = mutableListOf<PlaybackDiagnosticEvent>()
        val budget = AbandonedPlayerBudget()
        val session = session(
            engine,
            diagnostics = PlaybackDiagnostics(recorded::add),
            abandonedPlayerBudget = budget,
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        session.dispatch(PlaybackCommand.Zap(secondLiveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertEquals("the zap proceeded", 2, engine.startCalls)
        assertTrue("no fatal failure", session.snapshot.value.state != PlaybackState.FAILED)
        assertEquals("abandon was asked once", 1, engine.abandonCalls)
        assertEquals("budget spent", 1, budget.used)
        val abandoned = recorded.single { it.code == PlaybackDiagnosticCode.ADAPTER_ABANDONED_AFTER_RELEASE_FAILURE }
        assertEquals("DETACH_SURFACE", abandoned.releaseStep)
        assertTrue(
            "the failed barrier diagnostics carry the step too",
            recorded.filter { it.code == PlaybackDiagnosticCode.ENGINE_OPERATION_FAILED }
                .all { it.releaseStep == "DETACH_SURFACE" },
        )
        close(session)
    }

    @Test
    fun `second failed release barrier with the budget spent stays fatal`() = runTest {
        val engine = FakeEngine().apply {
            releaseFailuresRemaining = 2
            hardAbortFailuresRemaining = 2
            abandonSucceeds = true
        }
        val budget = AbandonedPlayerBudget()
        val session = session(engine, abandonedPlayerBudget = budget)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        session.dispatch(PlaybackCommand.Zap(secondLiveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        assertEquals("first failure was absorbed", 1, engine.abandonCalls)

        session.dispatch(PlaybackCommand.Zap(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertEquals(PlaybackState.FAILED, session.snapshot.value.state)
        assertEquals(FailureCode.RESOURCE_RELEASE_FAILED, session.snapshot.value.failure?.code)
        assertEquals("no second abandon", 1, engine.abandonCalls)
        close(session)
    }

    @Test
    fun `a zero budget keeps the failed release fatal and never calls abandon`() = runTest {
        val engine = FakeEngine().apply {
            releaseFailuresRemaining = 1
            hardAbortFailuresRemaining = 1
            abandonSucceeds = true
        }
        val session = session(engine, abandonedPlayerBudget = AbandonedPlayerBudget(maxAbandons = 0))
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        session.dispatch(PlaybackCommand.Zap(secondLiveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertEquals(PlaybackState.FAILED, session.snapshot.value.state)
        assertEquals(FailureCode.RESOURCE_RELEASE_FAILED, session.snapshot.value.failure?.code)
        assertEquals(0, engine.abandonCalls)
        close(session)
    }

    @Test
    fun `an abandon the engine refuses leaves the release failure fatal`() = runTest {
        val engine = FakeEngine().apply {
            releaseFailuresRemaining = 1
            hardAbortFailuresRemaining = 1
            abandonSucceeds = false
        }
        val session = session(engine)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        session.dispatch(PlaybackCommand.Zap(secondLiveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertEquals(1, engine.abandonCalls)
        assertEquals(PlaybackState.FAILED, session.snapshot.value.state)
        assertEquals(FailureCode.RESOURCE_RELEASE_FAILED, session.snapshot.value.failure?.code)
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
            environmentProvider = PlaybackEnvironmentProvider { input ->
                val profile = input.profile
                capturedProfiles += profile
                environmentResult(
                    input,
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
            environmentProvider = PlaybackEnvironmentProvider { input ->
                val profile = input.profile
                environmentResult(
                    input,
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
            environmentProvider = PlaybackEnvironmentProvider { input ->
                val profile = input.profile
                if (profile == SessionProfile.FULLSCREEN) {
                    fullscreenEntered.complete(Unit)
                    releaseFullscreen.await()
                }
                environmentResult(input)
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
            environmentProvider = PlaybackEnvironmentProvider { input ->
                val profile = input.profile
                when (profile) {
                    SessionProfile.FULLSCREEN -> {
                        fullscreenEntered.complete(Unit)
                        releaseFullscreen.await()
                        environmentResult(input)
                    }
                    SessionProfile.GUIDE -> {
                        guideSnapshots++
                        if (guideSnapshots == 1) environmentResult(input)
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
        assertEquals(1, engine.releaseSourceCalls)
        assertEquals(1, engine.releaseCalls)
        assertEquals(2, engine.startCalls)
        close(session)
    }

    @Test
    fun `a channel change after a handoff returns to the preferred engine`() = runTest {
        // Fleet (30 days): after one real handoff, every later zap in the session "failed" in
        // <100 ms and handed off again, because the warm engine from the previous channel still
        // held the one surface. ~40% of live zaps ended on the handoff engine nobody chose.
        val surface = FakeSurface()
        val preferred = FakeEngine(type = EngineType.MEDIA3, surface = surface)
        val fallback = FakeEngine(type = EngineType.LIBMPV, surface = surface)
        val session = session(
            engine = preferred,
            otherEngine = fallback,
            requirementsResolver = PlaybackRequirementsResolver {
                PlaybackResult.Success(requirements(setOf(EngineType.MEDIA3, EngineType.LIBMPV)))
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        preferred.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()
        preferred.emit(
            PlaybackEvent.Failed(
                1,
                PlaybackFailure(
                    FailureCode.VIDEO_DECODER_FAILED,
                    FailureDomain.VIDEO_DECODER,
                    FailurePhase.PLAYBACK,
                    Retryability.HANDOFF_ELIGIBLE,
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals("the handoff ran on the fallback", 1, fallback.startCalls)
        fallback.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        session.dispatch(PlaybackCommand.Zap(secondLiveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        val zapGeneration = session.snapshot.value.generation
        assertTrue(
            "the next channel starts on the preferred engine, not the warm fallback",
            zapGeneration in preferred.startGenerations,
        )
        assertEquals("the zap is not a handoff", 1, fallback.startCalls)
        assertEquals(EngineType.MEDIA3, session.snapshot.value.graph?.engine)
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

        assertEquals(session.snapshot.value.toString(), 4, engine.startCalls)
        assertEquals(4, resolutions)
        assertTrue(engine.releaseCalls >= 3)
        assertEquals(1, engine.hardAbortCalls)
        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)
        close(session)
    }

    // Review follow-up: a preferences change resolved while primary graph selection was still in
    // flight was a reducer no-op (no graph existed yet), and the in-flight SelectionFinished then
    // stored requirements from the OLD preferences snapshot — the user's change was silently lost
    // for the whole session even though the changeId machinery exists to prevent exactly this.
    @Test
    fun `preferences change during graph selection is never lost`() = runTest {
        val engine = FakeEngine()
        val selectionGate = CompletableDeferred<Unit>()
        var environmentCalls = 0
        val session = session(
            engine = engine,
            environmentProvider = PlaybackEnvironmentProvider { input ->
                environmentCalls += 1
                if (environmentCalls == 1) selectionGate.await()
                environmentResult(input)
            },
            requirementsResolver = PlaybackRequirementsResolver { input ->
                PlaybackResult.Success(
                    requirements().copy(
                        subtitleDelayMs = input.effectivePreferences.subtitles.delayMs,
                    ),
                )
            },
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()

        session.dispatch(
            PlaybackCommand.PreferencesChanged(
                PlaybackPreferences(subtitles = SubtitlePreference(delayMs = 250)),
            ),
        )
        advanceUntilIdle()
        selectionGate.complete(Unit)
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        val effectiveDelay = engine.lastAppliedRequirements?.subtitleDelayMs
            ?: engine.lastStartInput?.requirements?.subtitleDelayMs
        assertEquals(250L, effectiveDelay)
        close(session)
    }

    // Review follow-up: the reconnect loop hardcoded startPaused=false while recoverOnce honored
    // machine.paused — a pause issued during LIVE_RECONNECTING played audio out loud on reconnect
    // while the snapshot claimed paused.
    @Test
    fun `pause during live reconnect is honored by the next reconnect start`() = runTest {
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
        val session = session(engine)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        engine.emit(PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF))
        runCurrent()
        session.dispatch(PlaybackCommand.Pause)
        advanceUntilIdle()

        assertEquals(3, engine.startCalls)
        assertTrue(requireNotNull(engine.lastStartInput).startPaused)
        assertFalse(session.snapshot.value.isPlaying)
        close(session)
    }

    @Test
    fun `rapid frame death incidents exhaust rolling reconnect pressure`() = runTest {
        val engine = FakeEngine { start, input, events ->
            if (start > 1) events.emit(PlaybackEvent.FirstVideoFrame(input.generation))
        }
        val session = session(engine)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        repeat(7) {
            engine.emit(PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF))
            advanceUntilIdle()
        }

        assertEquals(7, engine.startCalls) // initial + six budgeted reconnect provider opens
        assertEquals(PlaybackState.FAILED, session.snapshot.value.state)
        assertEquals(FailureCode.LIVE_RECONNECT_EXHAUSTED, session.snapshot.value.failure?.code)
        close(session)
    }

    // 2026-11-04 Onn 4K (TV 1.16.4): the provider closed a live stream after 3 minutes and every
    // reconnect's mpv ended again within ~1s with an EOF and no frame. The screen ended black and
    // idle with no message. Whatever the profile, the session must end in FAILED (error + Retry),
    // stop opening the provider, and stay there.
    private suspend fun TestScope.eofStormEnds(profile: SessionProfile) {
        val engine = FakeEngine { start, input, events ->
            if (start > 1) events.emit(PlaybackEvent.PlaybackEnded(input.generation, PlaybackEndReason.EOF))
        }
        val session = session(engine)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, profile))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        engine.emit(PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF))
        advanceUntilIdle()
        advanceTimeBy(10 * 60_000L)
        advanceUntilIdle()

        val state = session.snapshot.value
        assertEquals(state.toString(), PlaybackState.FAILED, state.state)
        assertEquals(FailureCode.LIVE_RECONNECT_EXHAUSTED, state.failure?.code)
        val startsAtFailure = engine.startCalls
        advanceTimeBy(10 * 60_000L)
        advanceUntilIdle()
        assertEquals("a Failed live session must not keep opening the provider", startsAtFailure, engine.startCalls)
        close(session)
    }

    @Test
    fun `fullscreen eof storm ends in failed and stays quiet`() = runTest { eofStormEnds(SessionProfile.FULLSCREEN) }

    @Test
    fun `guide preview eof storm ends in failed and stays quiet`() = runTest { eofStormEnds(SessionProfile.GUIDE) }

    // The same Onn log: ~40 s after the ladder gave up the viewer pressed Retry; the provider still
    // closed every stream at once. Retry must end in FAILED again (error + Retry stay reachable),
    // never in a state with no attempts running and no error.
    @Test
    fun `retry after exhausted live reconnect ends in failed again`() = runTest {
        val engine = FakeEngine { start, input, events ->
            if (start > 1) events.emit(PlaybackEvent.PlaybackEnded(input.generation, PlaybackEndReason.EOF))
        }
        val session = session(engine)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.GUIDE))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF))
        advanceUntilIdle()
        assertEquals(PlaybackState.FAILED, session.snapshot.value.state)

        advanceTimeBy(40_000L)
        session.dispatch(PlaybackCommand.Retry)
        advanceUntilIdle()
        advanceTimeBy(5 * 60_000L)
        advanceUntilIdle()

        val state = session.snapshot.value
        assertEquals(state.toString(), PlaybackState.FAILED, state.state)
        assertTrue(state.failure != null)
        val starts = engine.startCalls
        advanceTimeBy(5 * 60_000L)
        advanceUntilIdle()
        assertEquals("no further provider opens once Failed", starts, engine.startCalls)
        close(session)
    }

    // Live pipeline audit 2026-11-04 §3c, fits B52 ("recovers after zapping and waiting 1-2 min"): the
    // rolling reconnect window is session-scoped and cleared only by 30 s of stable playback, so it
    // survived a user Retry and a channel change. A healthy stream after Retry/zap inherited the old
    // channel's pressure and went straight to FAILED on its first blip.
    private suspend fun TestScope.exhaustThenRestart(restart: PlaybackCommand): Pair<PlaybackSession, FakeEngine> {
        val engine = FakeEngine { start, input, events ->
            if (start > 1) events.emit(PlaybackEvent.FirstVideoFrame(input.generation))
        }
        val session = session(engine)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()
        repeat(7) {
            engine.emit(PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF))
            advanceUntilIdle()
        }
        assertEquals(FailureCode.LIVE_RECONNECT_EXHAUSTED, session.snapshot.value.failure?.code)

        session.dispatch(restart)
        advanceUntilIdle()
        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)
        return session to engine
    }

    private suspend fun TestScope.firstBlipReconnects(session: PlaybackSession, engine: FakeEngine) {
        val generation = session.snapshot.value.generation
        val starts = engine.startCalls
        engine.emit(PlaybackEvent.PlaybackEnded(generation, PlaybackEndReason.EOF))
        advanceUntilIdle()
        val state = session.snapshot.value
        assertEquals("the first blip gets a reconnect, not inherited exhaustion", starts + 1, engine.startCalls)
        assertEquals(state.toString(), PlaybackState.PLAYING, state.state)
    }

    @Test
    fun `user retry after exhaustion gives a healthy stream a fresh reconnect budget`() = runTest {
        val (session, engine) = exhaustThenRestart(PlaybackCommand.Retry)
        firstBlipReconnects(session, engine)
        close(session)
    }

    @Test
    fun `zap after exhaustion gives the new channel a fresh reconnect budget`() = runTest {
        val (session, engine) = exhaustThenRestart(
            PlaybackCommand.Zap(secondLiveRequest, SessionProfile.FULLSCREEN),
        )
        firstBlipReconnects(session, engine)
        close(session)
    }

    // Live pipeline audit 2026-11-04 §3a inferred that a surface which never returns during a live
    // reconnect leaves the LIVE_RECONNECTING spinner up forever (the loop's `delay(500); continue`).
    // Pin of the real path: the reducer turns surface loss during a reconnect into a SURFACE_LOST
    // barrier, so the session waits in ATTACHING_SURFACE under the production surface watchdog and
    // ends in FAILED (error + Retry) - it never spins in the reconnect loop.
    @Test
    fun `a surface that never returns during live reconnect ends in failed`() = runTest {
        val lifecycleEvents = MutableSharedFlow<PlaybackLifecycleEvent>(extraBufferCapacity = 4)
        val engine = FakeEngine { start, input, events ->
            if (start == 2) {
                events.emit(
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
            }
        }
        val session = session(
            engine,
            lifecycle = PlaybackLifecyclePort { lifecycleEvents },
            policy = PlaybackPolicy(),
        )
        runCurrent()
        lifecycleEvents.emit(PlaybackLifecycleEvent.ACTIVE)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        runCurrent()
        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)

        engine.emit(PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF))
        runCurrent()
        lifecycleEvents.emit(PlaybackLifecycleEvent.INACTIVE)
        runCurrent()
        session.dispatch(PlaybackCommand.SurfaceUnavailable)
        runCurrent()
        lifecycleEvents.emit(PlaybackLifecycleEvent.ACTIVE)
        runCurrent()
        // The surface never comes back while the screen is resumed. Step virtual time rather than
        // advanceUntilIdle, which an unbounded wait would never let return.
        repeat(20) {
            advanceTimeBy(30_000L)
            runCurrent()
        }

        val state = session.snapshot.value
        assertEquals(state.toString(), PlaybackState.FAILED, state.state)
        assertEquals(FailureCode.SURFACE_LOST, state.failure?.code)
        close(session)
    }

    @Test
    fun `live recovery joins default position while VOD restores its checkpoint`() = runTest {
        suspend fun TestScope.recoveredStart(request: PlaybackRequest): PlaybackEngineStart {
            val engine = FakeEngine { start, input, events ->
                if (start == 2) events.emit(PlaybackEvent.FirstVideoFrame(input.generation))
            }
            val session = session(engine)
            session.dispatch(PlaybackCommand.SurfaceAvailable)
            session.dispatch(PlaybackCommand.Tune(request, SessionProfile.FULLSCREEN))
            advanceUntilIdle()
            engine.emit(PlaybackEvent.FirstVideoFrame(1))
            engine.emit(
                PlaybackEvent.TimelineUpdated(
                    1,
                    PlaybackTimelineFacts(positionMs = 1_800_000, durationMs = 3_600_000, seekable = true),
                ),
            )
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
            val recovered = requireNotNull(engine.lastStartInput)
            close(session)
            return recovered
        }

        assertEquals(0L, recoveredStart(liveRequest).startPositionMs)
        assertEquals(1_800_000L, recoveredStart(vodRequest).startPositionMs)
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
    fun `reconnect audio without video frame expires through startup watchdog`() = runTest {
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
                events.emit(PlaybackEvent.VideoDecoderInitialized(input.generation, "decoder"))
            }
        }
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val session = session(
            engine,
            policy = PlaybackPolicy(),
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        runCurrent()

        engine.emit(PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(engine.startCalls >= 2)
        assertTrue(
            session.snapshot.value.state in setOf(
                PlaybackState.LIVE_RECONNECTING,
                PlaybackState.STARTING_PRIMARY,
            ),
        )
        assertTrue(session.snapshot.value.progress.renderedAudio)
        val expired = diagnostics.single {
            it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED &&
                it.failure?.code == FailureCode.NO_PROGRESS
        }
        assertEquals(FailureCode.NO_PROGRESS, expired.failure?.code)
        assertEquals(FailurePhase.RECOVERY, expired.failure?.phase)
        assertTrue(engine.releaseCalls >= 1)
        close(session)
    }

    @Test
    fun `an error raised by the engine itself is recorded with its engine and code`() = runTest {
        // Fleet analysis (2026-10-03): ~67% of Media3's terminal failures and nearly all libmpv
        // handoff causes carried no code because engine-raised errors were never recorded.
        val raised = PlaybackFailure(
            FailureCode.DEMUX_FAILED,
            FailureDomain.DEMUX,
            FailurePhase.PLAYBACK,
            Retryability.HANDOFF_ELIGIBLE,
            deterministic = true,
        )
        val engine = FakeEngine()
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val session = session(engine, diagnostics = PlaybackDiagnostics(diagnostics::add))
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        engine.emit(PlaybackEvent.Failed(1, raised))
        advanceUntilIdle()

        val recorded = diagnostics.single { it.code == PlaybackDiagnosticCode.ENGINE_REPORTED_FAILURE }
        assertEquals(engine.type, recorded.engine)
        assertEquals(FailureCode.DEMUX_FAILED, recorded.failure?.code)
        assertEquals(1L, recorded.generation)
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

        advanceTimeBy(2_500)
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

        engine.videoFramesRendered = 135
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
    fun `attaching surface without host progress is bounded`() = runTest {
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val engine = FakeEngine()
        val session = session(
            engine = engine,
            policy = PlaybackPolicy(),
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        assertEquals(PlaybackState.ATTACHING_SURFACE, session.snapshot.value.state)

        // B50: never while the coordinator may still be waiting on its fresh-view retry.
        advanceTimeBy(SurfaceAttachmentBudget.ATTEMPTS * SurfaceAttachmentBudget.WAIT_MS)
        runCurrent()
        assertTrue(diagnostics.none { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED })

        advanceTimeBy(SurfaceAttachmentBudget.WATCHDOG_MS - SurfaceAttachmentBudget.ATTEMPTS * SurfaceAttachmentBudget.WAIT_MS)
        runCurrent()

        val expired = diagnostics.single { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED }
        assertEquals(FailureCode.SURFACE_LOST, expired.failure?.code)
        assertEquals(FailurePhase.SURFACE_ATTACHMENT, expired.failure?.phase)
        assertEquals(PlaybackState.FAILED, session.snapshot.value.state)
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
    fun `release from failed state completes a fresh barrier without opening the next provider request`() = runTest {
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

        val close = launch { session.release() }
        advanceUntilIdle()
        close.join()

        assertEquals(2, engine.releaseCalls)
        assertEquals(1, engine.hardAbortCalls)
        assertEquals(0, engine.activeConnections)
        assertEquals(PlaybackState.STOPPED, session.snapshot.value.state)
    }

    @Test
    fun `session applies output once before start and again from committed runtime facts`() = runTest {
        val engine = FakeEngine()
        val output = RecordingOutputController()
        val session = session(engine, outputController = output)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertEquals(1, output.requests.size)
        assertFalse(output.requests.single().committed)
        assertEquals(VideoOutputFacts(), output.requests.single().facts)

        engine.emit(PlaybackEvent.VideoFrameRateChanged(1, 25f))
        engine.emit(PlaybackEvent.VideoSizeChanged(1, 1_920, 1_080))
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        val runtime = output.requests.last()
        assertTrue(runtime.committed)
        assertTrue(runtime.facts.revision > output.requests.first().facts.revision)
        assertEquals(25f, runtime.facts.frameRate)
        assertEquals(VideoDimensions(1_920, 1_080), runtime.facts.dimensions)
        assertEquals(PlaybackOutputStatus.APPLIED, session.snapshot.value.playbackOutputStatus)
        close(session)
    }

    @Test
    fun `replacement reset identifies the released graph generation`() = runTest {
        val engine = FakeEngine()
        val output = RecordingOutputController()
        val session = session(engine, outputController = output)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        session.dispatch(PlaybackCommand.Zap(secondLiveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertTrue(output.resets.contains(1L to ActiveWorkReleaseReason.REPLACE_REQUEST))
        assertFalse(output.resets.contains(2L to ActiveWorkReleaseReason.REPLACE_REQUEST))
        close(session)
    }

    @Test
    fun `optional output failure remains playing and emits a sanitized diagnostic`() = runTest {
        val engine = FakeEngine()
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val output = RecordingOutputController(committedStatus = PlaybackOutputStatus.APPLY_FAILED)
        val session = session(
            engine,
            outputController = output,
            diagnostics = PlaybackDiagnostics(diagnostics::add),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.VideoFrameRateChanged(1, 24f))
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        advanceUntilIdle()

        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)
        assertEquals(PlaybackOutputStatus.APPLY_FAILED, session.snapshot.value.playbackOutputStatus)
        assertTrue(
            diagnostics.any {
                it.code == PlaybackDiagnosticCode.PLAYBACK_OUTPUT_NONFATAL &&
                    it.outputStatus == PlaybackOutputStatus.APPLY_FAILED &&
                    it.failure == null
            },
        )
        close(session)
    }

    @Test
    fun `session owns audio focus across engine-neutral duck loss gain and release`() = runTest {
        val engine = FakeEngine()
        val focus = FakeAudioFocusPort()
        val session = session(engine, audioFocus = focus)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()

        assertEquals(1, focus.acquireCalls)
        focus.emit(AudioFocusEvent.LOSS_DUCK)
        focus.emit(AudioFocusEvent.LOSS_TRANSIENT)
        focus.emit(AudioFocusEvent.GAIN)
        advanceUntilIdle()

        assertEquals(listOf(0.2f, 1f), engine.volumeValues)
        assertEquals(listOf(true, false), engine.pausedValues)
        close(session)
        assertEquals(1, focus.abandonCalls)
    }

    @Test
    fun `audio sink failure applies PCM audio ladder without entering video recovery`() = runTest {
        val engine = FakeEngine { start, input, events ->
            if (start == 2) events.emit(PlaybackEvent.FirstVideoFrame(input.generation))
        }
        val session = session(engine)
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(vodRequest, SessionProfile.FULLSCREEN))
        advanceUntilIdle()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        engine.emit(
            PlaybackEvent.Failed(
                1,
                PlaybackFailure(
                    FailureCode.AUDIO_SINK_FAILED,
                    FailureDomain.AUDIO_SINK,
                    FailurePhase.PLAYBACK,
                    Retryability.HANDOFF_ELIGIBLE,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(2, engine.startCalls)
        assertEquals(AudioOutputPreference.PCM, engine.lastStartInput?.requirements?.audioOutput)
        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)
        close(session)
    }


    @Test
    fun `stuck live buffering reconnects with fresh request despite trickle and duplicate buffering events`() = runTest {
        var resolutions = 0
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        val engine = FakeEngine { count, input, events ->
            if (count > 1) events.emit(PlaybackEvent.FirstVideoFrame(input.generation))
        }
        val session = session(engine, policy = PlaybackPolicy(), diagnostics = PlaybackDiagnostics(diagnostics::add),
            requestResolver = PlaybackRequestResolver { request ->
                resolutions++
                PlaybackResult.Success(request.resolved())
            })
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        engine.emit(PlaybackEvent.BufferingStarted(1))
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(0, engine.releaseCalls)
        engine.emit(PlaybackEvent.BytesReceived(1))
        engine.emit(PlaybackEvent.BufferingStarted(1))
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        val expired = diagnostics.single { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED }
        assertEquals(FailureDomain.NETWORK, expired.failure?.domain)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, resolutions)
        assertEquals(2, engine.startCalls)
        assertEquals(1, engine.releaseCalls)
        assertEquals(1, engine.maxActiveConnections)
        close(session)
    }

    @Test
    fun `live buffering watchdog cancels after refill pause stop lifecycle exit or zap`() = runTest {
        for (action in listOf("refill", "pause", "stop", "inactive", "zap")) {
            val lifecycleEvents = MutableSharedFlow<PlaybackLifecycleEvent>(extraBufferCapacity = 4)
            val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
            val engine = FakeEngine { count, input, events ->
                if (count > 1) events.emit(PlaybackEvent.FirstVideoFrame(input.generation))
            }
            val session = session(engine, policy = PlaybackPolicy(), diagnostics = PlaybackDiagnostics(diagnostics::add),
                lifecycle = PlaybackLifecyclePort { lifecycleEvents })
            runCurrent()
            session.dispatch(PlaybackCommand.SurfaceAvailable)
            session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
            runCurrent()
            engine.emit(PlaybackEvent.FirstVideoFrame(1))
            engine.emit(PlaybackEvent.BufferingStarted(1))
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            when (action) {
                "refill" -> engine.emit(PlaybackEvent.BufferingEnded(1))
                "pause" -> session.dispatch(PlaybackCommand.Pause)
                "stop" -> session.dispatch(PlaybackCommand.Stop)
                "inactive" -> lifecycleEvents.emit(PlaybackLifecycleEvent.INACTIVE)
                "zap" -> session.dispatch(PlaybackCommand.Tune(secondLiveRequest, SessionProfile.FULLSCREEN))
            }
            runCurrent()
            advanceTimeBy(35_000)
            runCurrent()
            assertTrue(action, diagnostics.none { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED })
            close(session)
        }
    }

    @Test
    fun `audio only live buffering gets bounded recovery but VOD buffering does not`() = runTest {
        for (request in listOf(liveRequest, vodRequest)) {
            val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
            val engine = FakeEngine()
            val session = session(engine, policy = PlaybackPolicy(), diagnostics = PlaybackDiagnostics(diagnostics::add))
            session.dispatch(PlaybackCommand.SurfaceAvailable)
            session.dispatch(PlaybackCommand.Tune(request, SessionProfile.FULLSCREEN))
            runCurrent()
            engine.emit(PlaybackEvent.TracksAvailable(1, hasVideo = false, 1, 0))
            engine.emit(PlaybackEvent.FirstAudio(1))
            engine.emit(PlaybackEvent.BufferingStarted(1))
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(request.contentType == ContentType.LIVE,
                diagnostics.any { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED })
            close(session)
        }
    }

    @Test
    fun `HD decoder initialization may take eighteen hundred milliseconds without fallback`() = runTest {
        val engine = FakeEngine()
        val session = session(engine, policy = PlaybackPolicy())
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        engine.emit(PlaybackEvent.TracksAvailable(1, hasVideo = true, 1, 0, VideoDimensions(1920, 1080)))
        runCurrent()
        advanceTimeBy(1_800)
        runCurrent()
        engine.emit(PlaybackEvent.VideoDecoderInitialized(1, "decoder"))
        engine.emit(PlaybackEvent.FirstVideoFrame(1))
        runCurrent()
        assertEquals(PlaybackState.PLAYING, session.snapshot.value.state)
        assertEquals(0, engine.releaseCalls)
        close(session)
    }

    @Test
    fun `B178 a request resolution that never returns ends in failed with a timeout`() = runTest {
        val resolver = PlaybackRequestResolver { awaitCancellation() }
        val session = session(
            engine = FakeEngine(),
            requestResolver = resolver,
            policy = PlaybackPolicy(),
        )
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.GUIDE))
        runCurrent()
        assertEquals(PlaybackState.RESOLVING, session.snapshot.value.state)

        advanceTimeBy(PlaybackStageDeadlinePolicy.RESOLVING_MS - 1)
        runCurrent()
        assertEquals("still inside the deadline", PlaybackState.RESOLVING, session.snapshot.value.state)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(PlaybackState.FAILED, session.snapshot.value.state)
        assertEquals(FailureCode.NETWORK_TIMEOUT, session.snapshot.value.failure?.code)
        assertEquals(FailurePhase.REQUEST_RESOLUTION, session.snapshot.value.failure?.phase)
        close(session)
    }

    @Test
    fun `B178 a healthy resolution trips no stage deadline in any stage`() = runTest {
        val session = session(engine = FakeEngine(), policy = PlaybackPolicy())
        session.dispatch(PlaybackCommand.SurfaceAvailable)
        session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.GUIDE))
        runCurrent()
        // Past the pre-engine stage window but inside the engine's own 60 s read timeout.
        advanceTimeBy(PlaybackStageDeadlinePolicy.PRE_ENGINE_START_MS + 1)
        runCurrent()
        assertTrue("not failed: ${session.snapshot.value.failure}", session.snapshot.value.state != PlaybackState.FAILED)
        assertEquals(null, session.snapshot.value.failure)
        close(session)
    }

    @Test
    fun `B178 stage deadlines outlast the per-call http limit`() {
        val httpLimit = PlaybackNetworkRequest.DEFAULT_READ_TIMEOUT_MS.toLong()
        assertTrue(PlaybackStageDeadlinePolicy.RESOLVING_MS > httpLimit)
        assertTrue(PlaybackStageDeadlinePolicy.SELECTING_GRAPH_MS > httpLimit)
    }

    // ---- Live frozen picture (2026-11-08): video freezes while audio plays --------------------

    private class FreezeRig(
        val preferred: FakeEngine,
        val fallback: FakeEngine,
        val session: PlaybackSession,
        val diagnostics: MutableList<PlaybackDiagnosticEvent>,
        val history: FakeCompatibilityHistory,
        val resolveCount: () -> Int,
    )

    /** Media3 primary plus libmpv fallback on one surface; every reconnect shows its first frame. */
    private fun TestScope.freezeRig(
        fallbackAudioMoving: Boolean = true,
        fallbackVideoMoving: Boolean = false,
        withFallback: Boolean = true,
        policy: PlaybackPolicy = PlaybackPolicy(),
        clock: PlaybackClock = TestClock,
    ): FreezeRig {
        val surface = FakeSurface()
        val history = FakeCompatibilityHistory()
        val diagnostics = mutableListOf<PlaybackDiagnosticEvent>()
        var resolves = 0
        val showFirstFrame: suspend (Int, PlaybackEngineStart, MutableSharedFlow<PlaybackEvent>) -> Unit =
            { start, input, events ->
                if (start >= 2) {
                    events.emit(PlaybackEvent.TracksAvailable(input.generation, hasVideo = true, 1, 0))
                    events.emit(PlaybackEvent.FirstVideoFrame(input.generation))
                }
            }
        val preferred = FakeEngine(type = EngineType.MEDIA3, surface = surface, onStart = showFirstFrame)
        val fallback = FakeEngine(type = EngineType.LIBMPV, surface = surface, onStart = { _, input, events ->
            events.emit(PlaybackEvent.TracksAvailable(input.generation, hasVideo = true, 1, 0))
            events.emit(PlaybackEvent.FirstVideoFrame(input.generation))
        })
        preferred.videoFramesRendered = 10
        preferred.audioBuffersRendered = 0
        fallback.videoFramesRendered = 10
        fallback.audioBuffersRendered = 0
        fallback.videoMoving = fallbackVideoMoving
        fallback.audioMoving = fallbackAudioMoving
        val scope = CompatibilityScopeKey("freezing-channel")
        val session = session(
            engine = preferred,
            otherEngine = fallback.takeIf { withFallback },
            requestResolver = PlaybackRequestResolver { request ->
                resolves++
                PlaybackResult.Success(request.resolved(scope))
            },
            requirementsResolver = PlaybackRequirementsResolver {
                PlaybackResult.Success(
                    requirements(
                        if (withFallback) setOf(EngineType.MEDIA3, EngineType.LIBMPV) else setOf(EngineType.MEDIA3),
                    ),
                )
            },
            policy = policy,
            diagnostics = PlaybackDiagnostics(diagnostics::add),
            compatibilityRecording = compatibilityRecording(history),
            clock = clock,
        )
        return FreezeRig(preferred, fallback, session, diagnostics, history) { resolves }
    }

    private suspend fun TestScope.startFrozenChannel(rig: FreezeRig) {
        rig.session.dispatch(PlaybackCommand.SurfaceAvailable)
        rig.session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        rig.preferred.emit(PlaybackEvent.TracksAvailable(1, hasVideo = true, 1, 0))
        rig.preferred.emit(PlaybackEvent.FirstVideoFrame(1))
        runCurrent()
    }

    private fun TestScope.virtualClock() = object : PlaybackClock {
        override fun nowEpochMs(): Long = testScheduler.currentTime
        override suspend fun delayMs(durationMs: Long) = delay(durationMs)
    }

    private suspend fun TestScope.runUntil(maxMs: Long = 60_000, condition: () -> Boolean) {
        var elapsed = 0L
        while (!condition() && elapsed < maxMs) {
            advanceTimeBy(250)
            runCurrent()
            elapsed += 250
        }
    }

    private suspend fun TestScope.runFor(ms: Long) {
        var elapsed = 0L
        while (elapsed < ms) {
            advanceTimeBy(250)
            runCurrent()
            elapsed += 250
        }
    }

    @Test
    fun `first frame then refreeze escalates to handoff on the second video only freeze`() = runTest {
        val rig = freezeRig()
        rig.preferred.audioMoving = true
        startFrozenChannel(rig)

        runUntil { rig.preferred.startCalls >= 2 }
        assertEquals("the first video only freeze reconnects once", 2, rig.preferred.startCalls)
        assertEquals(0, rig.fallback.startCalls)

        runUntil { rig.fallback.startCalls >= 1 }
        assertEquals("the second video only freeze hands off to libmpv", 1, rig.fallback.startCalls)
        assertEquals("no third plain reconnect", 2, rig.preferred.startCalls)
        close(rig.session)
    }

    @Test
    fun `a source freeze still reconnects with a fresh request`() = runTest {
        val rig = freezeRig()
        startFrozenChannel(rig)
        val resolvesBefore = rig.resolveCount()

        runUntil { rig.preferred.startCalls >= 2 }
        runCurrent()

        assertEquals("the first source freeze reconnects", 2, rig.preferred.startCalls)
        assertEquals(0, rig.fallback.startCalls)
        assertTrue("the reconnect asks the resolver again", rig.resolveCount() - resolvesBefore >= 1)
        close(rig.session)
    }

    @Test
    fun `a second source freeze on media3 means a wedged pipeline and hands off`() = runTest {
        val rig = freezeRig()
        startFrozenChannel(rig)

        runUntil { rig.fallback.startCalls >= 1 }

        assertEquals("one reconnect did not cure it", 2, rig.preferred.startCalls)
        assertEquals(1, rig.fallback.startCalls)
        close(rig.session)
    }

    @Test
    fun `without libmpv a repeated source freeze keeps reconnecting`() = runTest {
        val rig = freezeRig(withFallback = false)
        startFrozenChannel(rig)

        runUntil { rig.preferred.startCalls >= 3 }

        assertEquals(3, rig.preferred.startCalls)
        close(rig.session)
    }

    @Test
    fun `a freeze during probation hands off`() = runTest {
        val rig = freezeRig()
        startFrozenChannel(rig)
        runUntil { rig.preferred.startCalls >= 2 } // a source freeze: one plain reconnect
        rig.preferred.videoMoving = true
        rig.preferred.audioMoving = true
        runFor(10_000) // two healthy windows: probation has begun but is not over
        assertEquals(0, rig.fallback.startCalls)

        rig.preferred.videoMoving = false // picture freezes, audio plays on
        runUntil { rig.fallback.startCalls >= 1 }

        assertEquals("a video only freeze inside probation hands off", 1, rig.fallback.startCalls)
        assertEquals(2, rig.preferred.startCalls)
        close(rig.session)
    }

    @Test
    fun `probation passing clears the incident and pressure`() = runTest {
        // Budget of two reconnects per window: a third source freeze is only allowed because each
        // passed probation handed the budget back (and ended the incident, so none hands off).
        val tight = PlaybackPolicy(
            liveReconnectConfiguration = PlaybackPolicy.LiveReconnectConfiguration(
                maxAttemptsPerIncident = 2,
                maxAttemptsPerRollingWindow = 2,
            ),
        )
        val rig = freezeRig(policy = tight)
        startFrozenChannel(rig)

        repeat(3) { cycle ->
            rig.preferred.videoMoving = false
            rig.preferred.audioMoving = false
            runUntil { rig.preferred.startCalls >= cycle + 2 || rig.session.snapshot.value.state == PlaybackState.FAILED }
            rig.preferred.videoMoving = true
            rig.preferred.audioMoving = true
            runFor(35_000) // probation passes
        }

        assertEquals(4, rig.preferred.startCalls)
        assertEquals(PlaybackState.PLAYING, rig.session.snapshot.value.state)
        assertEquals("each freeze was a fresh incident", 0, rig.fallback.startCalls)
        close(rig.session)
    }

    @Test
    fun `reconnect pressure is not cleared by wall time while probation is still open`() = runTest {
        val tight = PlaybackPolicy(
            liveReconnectConfiguration = PlaybackPolicy.LiveReconnectConfiguration(
                maxAttemptsPerIncident = 2,
                maxAttemptsPerRollingWindow = 2,
            ),
        )
        val rig = freezeRig(withFallback = false, policy = tight, clock = virtualClock())
        startFrozenChannel(rig)
        runUntil { rig.preferred.startCalls >= 2 } // first freeze, first reconnect

        repeat(2) { round ->
            rig.preferred.videoMoving = true
            rig.preferred.audioMoving = true
            runFor(29_000) // five healthy windows and most of the sixth: probation is not done
            rig.preferred.videoMoving = false // the sixth window freezes, 30 s after the first frame
            runUntil { rig.preferred.startCalls >= round + 3 || rig.session.snapshot.value.state == PlaybackState.FAILED }
        }

        assertEquals(
            "three freezes inside one unfinished probation exhaust a budget of two",
            PlaybackState.FAILED,
            rig.session.snapshot.value.state,
        )
        close(rig.session)
    }

    @Test
    fun `six freezes of one channel in ten minutes end in failed even when every probation passes`() = runTest {
        val rig = freezeRig(withFallback = false)
        startFrozenChannel(rig)

        repeat(8) { cycle ->
            if (rig.session.snapshot.value.state == PlaybackState.FAILED) return@repeat
            rig.preferred.videoMoving = false
            rig.preferred.audioMoving = false
            runUntil { rig.preferred.startCalls >= cycle + 2 || rig.session.snapshot.value.state == PlaybackState.FAILED }
            rig.preferred.videoMoving = true
            rig.preferred.audioMoving = true
            runFor(35_000)
        }

        assertEquals(PlaybackState.FAILED, rig.session.snapshot.value.state)
        assertEquals("initial start plus five reconnects", 6, rig.preferred.startCalls)
        close(rig.session)
    }

    @Test
    fun `a low frame rate live stream plays without any freeze or reconnect`() = runTest {
        listOf(5L, 1L).forEach { perWindow -> // 1 fps and 0.2 fps
            val rig = freezeRig(withFallback = false)
            rig.preferred.videoMoving = true
            rig.preferred.videoPerRead = perWindow
            rig.preferred.audioMoving = true
            startFrozenChannel(rig)

            runFor(60_000)

            assertEquals("$perWindow frames per window", 1, rig.preferred.startCalls)
            assertEquals(PlaybackState.PLAYING, rig.session.snapshot.value.state)
            assertTrue(rig.diagnostics.none { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED })
            close(rig.session)
        }
    }

    @Test
    fun `a known 25 fps stream rendering one frame in five seconds is frozen`() = runTest {
        val rig = freezeRig(withFallback = false)
        rig.preferred.videoMoving = true
        rig.preferred.videoPerRead = 1
        rig.preferred.audioMoving = true
        startFrozenChannel(rig)
        rig.preferred.emit(PlaybackEvent.VideoFrameRateChanged(1, 25f))
        runCurrent()

        runUntil { rig.preferred.startCalls >= 2 }

        assertEquals(2, rig.preferred.startCalls)
        close(rig.session)
    }

    @Test
    fun `a video only freeze after a passed probation reconnects instead of handing off`() = runTest {
        val rig = freezeRig()
        rig.preferred.audioMoving = true
        startFrozenChannel(rig)
        runUntil { rig.preferred.startCalls >= 2 }
        rig.preferred.videoMoving = true
        runFor(35_000) // probation passes: the incident is over

        rig.preferred.videoMoving = false
        runUntil { rig.preferred.startCalls >= 3 }

        assertEquals("a new incident starts again at one reconnect", 3, rig.preferred.startCalls)
        assertEquals(0, rig.fallback.startCalls)
        close(rig.session)
    }

    @Test
    fun `runtime instability strike is counted per freeze not per generation`() = runTest {
        val rig = freezeRig()
        rig.preferred.audioMoving = true
        startFrozenChannel(rig)

        runUntil { rig.fallback.startCalls >= 1 } // two video only freezes in the same generation
        runCurrent()

        val strikes = rig.history.values.filter { it.outcome == CompatibilityOutcome.RUNTIME_UNSTABLE }
        assertEquals(2, strikes.size)
        close(rig.session)
    }

    @Test
    fun `a source freeze is a provider stall and records no strike`() = runTest {
        val rig = freezeRig(withFallback = false)
        startFrozenChannel(rig)

        runUntil { rig.preferred.startCalls >= 3 }
        runCurrent()

        assertTrue(rig.history.values.none { it.outcome == CompatibilityOutcome.RUNTIME_UNSTABLE })
        close(rig.session)
    }

    @Test
    fun `a handoff after repeated freezes leaves a proven libmpv success behind the strikes`() = runTest {
        val rig = freezeRig()
        rig.preferred.audioMoving = true
        rig.fallback.videoMoving = true
        startFrozenChannel(rig)

        runUntil { rig.fallback.startCalls >= 1 }
        runFor(2_000)

        val strikes = rig.history.values.filter {
            it.outcome == CompatibilityOutcome.RUNTIME_UNSTABLE && it.engine == EngineType.MEDIA3
        }
        val mpvSuccess = rig.history.values.filter {
            it.outcome == CompatibilityOutcome.SUCCESS && it.engine == EngineType.LIBMPV
        }
        assertEquals("two freezes are two strikes", 2, strikes.size)
        assertEquals(1, mpvSuccess.size)
        assertTrue(mpvSuccess.single().recordedAtEpochMs >= strikes.maxOf { it.recordedAtEpochMs })
        close(rig.session)
    }

    @Test
    fun `still image radio never goes terminal while audio advances`() = runTest {
        val rig = freezeRig(fallbackAudioMoving = true, fallbackVideoMoving = false)
        rig.preferred.audioMoving = true
        startFrozenChannel(rig)
        runUntil { rig.fallback.startCalls >= 1 }

        runFor(60_000)

        assertEquals(PlaybackState.PLAYING, rig.session.snapshot.value.state)
        assertEquals("libmpv is neither restarted nor released", 1, rig.fallback.startCalls)
        assertEquals(0, rig.fallback.releaseCalls)
        assertEquals(
            "the stuck picture is reported once",
            1,
            rig.diagnostics.count { it.code == PlaybackDiagnosticCode.LIVE_PICTURE_STUCK },
        )
        close(rig.session)
    }

    @Test
    fun `libmpv freeze with audio also flat still ends in failed with retry`() = runTest {
        val rig = freezeRig(fallbackAudioMoving = false, fallbackVideoMoving = false)
        rig.preferred.audioMoving = true
        startFrozenChannel(rig)
        runUntil { rig.fallback.startCalls >= 1 }

        runUntil(maxMs = 300_000) { rig.session.snapshot.value.state == PlaybackState.FAILED }

        assertEquals(PlaybackState.FAILED, rig.session.snapshot.value.state)
        close(rig.session)
    }

    @Test
    fun `audio only channel never trips the watchdog or hands off`() = runTest {
        val rig = freezeRig()
        rig.preferred.videoFramesRendered = 0
        rig.preferred.audioMoving = true
        rig.session.dispatch(PlaybackCommand.SurfaceAvailable)
        rig.session.dispatch(PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        runCurrent()
        rig.preferred.emit(PlaybackEvent.FirstAudio(1))
        rig.preferred.emit(PlaybackEvent.TracksAvailable(1, hasVideo = false, 1, 0))
        runCurrent()

        runFor(90_000)

        assertEquals(PlaybackState.PLAYING, rig.session.snapshot.value.state)
        assertEquals(0, rig.fallback.startCalls)
        assertEquals(1, rig.preferred.startCalls)
        assertTrue(rig.diagnostics.none { it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED })
        close(rig.session)
    }

    @Test
    fun `a runtime freeze diagnostic says what froze and which rung answered`() = runTest {
        val rig = freezeRig()
        rig.preferred.audioMoving = true
        startFrozenChannel(rig)
        rig.preferred.emit(PlaybackEvent.VideoDecoderInitialized(1, "c2.mtk.avc.decoder"))
        runUntil { rig.fallback.startCalls >= 1 }

        val freezes = rig.diagnostics.filter {
            it.code == PlaybackDiagnosticCode.WATCHDOG_EXPIRED && it.failure?.code == FailureCode.NO_PROGRESS
        }
        assertEquals(2, freezes.size)
        assertEquals(LiveFreezePolicy.FreezeKind.VIDEO_ONLY, freezes[0].freeze?.kind)
        assertEquals(LiveFreezePolicy.Action.RECONNECT, freezes[0].freeze?.rung)
        assertEquals(LiveFreezePolicy.Action.HANDOFF, freezes[1].freeze?.rung)
        assertEquals("c2.mtk.avc.decoder", freezes[0].freeze?.decoder)
        assertTrue(freezes[0].freeze?.sessionInstance?.isNotBlank() == true)
        assertEquals(freezes[0].freeze?.sessionInstance, freezes[1].freeze?.sessionInstance)
        close(rig.session)
    }

    private fun TestScope.session(
        engine: FakeEngine,
        otherEngine: FakeEngine? = null,
        requestResolver: PlaybackRequestResolver = PlaybackRequestResolver { request ->
            PlaybackResult.Success(request.resolved())
        },
        environmentProvider: PlaybackEnvironmentProvider = PlaybackEnvironmentProvider { input ->
            environmentResult(input)
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
        outputController: PlaybackOutputController = FakeOutputController,
        audioFocus: AudioFocusPort = NoopAudioFocusPort,
        clock: PlaybackClock = TestClock,
        abandonedPlayerBudget: AbandonedPlayerBudget = AbandonedPlayerBudget(),
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
        outputController = outputController,
        clock = clock,
        diagnostics = diagnostics,
        lifecycle = lifecycle,
        audioFocus = audioFocus,
        providerPlaybackResolver = providerPlaybackResolver,
        compatibilityRecording = compatibilityRecording,
        policy = policy,
        releaseTimeoutMs = releaseTimeoutMs,
        abandonedPlayerBudget = abandonedPlayerBudget,
    )

    private suspend fun close(session: PlaybackSession) {
        session.release()
    }

    /** The one playback surface: like the coordinator, a second engine cannot take it while held. */
    private class FakeSurface {
        var owner: EngineType? = null
    }

    private class FakeEngine(
        override val type: EngineType = EngineType.MEDIA3,
        private val onApply: suspend (PlaybackRequirements) -> Unit = { },
        private val surface: FakeSurface? = null,
        private val onStart: suspend (Int, PlaybackEngineStart, MutableSharedFlow<PlaybackEvent>) -> Unit =
            { _, _, _ -> },
    ) : PlaybackEngine {
        private val eventFlow = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
        override val events: Flow<PlaybackEvent> = eventFlow
        var startCalls = 0
        val startGenerations = mutableListOf<Long>()
        var lastStartInput: PlaybackEngineStart? = null
        var applyRequirementsCalls = 0
        var lastAppliedRequirements: PlaybackRequirements? = null
        var releaseCalls = 0
        var releaseSourceCalls = 0
        val releaseGenerations = mutableListOf<Long>()
        var hardAbortCalls = 0
        val hardAbortGenerations = mutableListOf<Long>()
        var activeConnections = 0
        var maxActiveConnections = 0
        var releaseTimeoutsRemaining = 0
        var releaseFailuresRemaining = 0
        var hardAbortFailuresRemaining = 0
        var abandonCalls = 0
        val abandonGenerations = mutableListOf<Long>()
        var abandonSucceeds = false
        var stepName: String? = null
        var snapshotMetricsCalls = 0
        var videoFramesRendered: Long? = null
        var audioBuffersRendered: Long? = null

        /** While true every metrics read finds [videoPerRead] more frames (default 25 fps over the 5 s window). */
        var videoMoving = false
        var videoPerRead = 125L

        /** While true every metrics read finds 100 more audio buffers. */
        var audioMoving = false
        val pausedValues = mutableListOf<Boolean>()
        val volumeValues = mutableListOf<Float>()

        val attachGenerations = mutableListOf<Long>()

        override suspend fun attachSurface(
            generation: Long,
            graph: PlaybackGraph,
        ): PlaybackResult<Unit> {
            val held = surface?.owner
            if (held != null && held != type) {
                return PlaybackResult.Failure(
                    PlaybackFailure(
                        FailureCode.SURFACE_LOST,
                        FailureDomain.VIDEO_RENDERER_SURFACE,
                        FailurePhase.SURFACE_ATTACHMENT,
                        Retryability.HANDOFF_ELIGIBLE,
                    ),
                )
            }
            surface?.owner = type
            attachGenerations += generation
            return PlaybackResult.Success(Unit)
        }

        override suspend fun detachSurface(generation: Long): PlaybackResult<Unit> =
            PlaybackResult.Success(Unit)

        override suspend fun start(input: PlaybackEngineStart): PlaybackResult<Unit> {
            startCalls++
            startGenerations += input.generation
            lastStartInput = input
            activeConnections++
            maxActiveConnections = maxOf(maxActiveConnections, activeConnections)
            onStart(startCalls, input, eventFlow)
            return PlaybackResult.Success(Unit)
        }

        override suspend fun setPaused(generation: Long, paused: Boolean): PlaybackResult<Unit> {
            pausedValues += paused
            return PlaybackResult.Success(Unit)
        }

        override suspend fun setVolume(generation: Long, volume: Float): PlaybackResult<Unit> {
            volumeValues += volume
            return PlaybackResult.Success(Unit)
        }

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
            if (videoMoving) videoFramesRendered = (videoFramesRendered ?: 0L) + videoPerRead
            if (audioMoving) audioBuffersRendered = (audioBuffersRendered ?: 0L) + 100L
            return PlaybackResult.Success(
                PlaybackEngineMetricsSnapshot(
                generation = generation,
                videoFramesRendered = videoFramesRendered,
                videoFramesSkipped = null,
                videoFramesDropped = null,
                audioBuffersRendered = audioBuffersRendered,
                audioBuffersSkipped = null,
                audioBuffersDropped = null,
                ),
            )
        }

        override suspend fun release(generation: Long): PlaybackResult<Unit> {
            releaseCalls++
            releaseGenerations += generation
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
            if (!releasingSourceOnly && surface?.owner == type) surface.owner = null
            return PlaybackResult.Success(Unit)
        }

        private var releasingSourceOnly = false

        override suspend fun releaseSource(generation: Long): PlaybackResult<Unit> {
            releaseSourceCalls++
            // A zap keeps the player and its surface warm; only a full release frees the surface.
            releasingSourceOnly = true
            return try {
                release(generation)
            } finally {
                releasingSourceOnly = false
            }
        }

        override suspend fun hardAbort(generation: Long): PlaybackResult<Unit> {
            hardAbortCalls++
            hardAbortGenerations += generation
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

        override fun releaseStepName(): String? = stepName

        override suspend fun abandon(generation: Long): PlaybackResult<Unit> {
            abandonCalls++
            abandonGenerations += generation
            if (!abandonSucceeds) return super.abandon(generation)
            // The stuck player is dropped: its provider connection is no longer ours to count.
            activeConnections = 0
            surface?.owner = null
            return PlaybackResult.Success(Unit)
        }

        suspend fun emit(event: PlaybackEvent) {
            eventFlow.emit(event)
        }
    }

    private data object FakeOutputController : PlaybackOutputController {
        override suspend fun apply(request: PlaybackOutputRequest): PlaybackResult<PlaybackOutputApplication> =
            PlaybackResult.Success(PlaybackOutputApplication(PlaybackOutputStatus.DISABLED))

        override suspend fun reset(
            releasedGeneration: Long?,
            reason: ActiveWorkReleaseReason,
        ): PlaybackResult<Unit> = PlaybackResult.Success(Unit)
    }

    private class RecordingOutputController(
        private val committedStatus: PlaybackOutputStatus = PlaybackOutputStatus.APPLIED,
    ) : PlaybackOutputController {
        val requests = mutableListOf<PlaybackOutputRequest>()
        val resets = mutableListOf<Pair<Long?, ActiveWorkReleaseReason>>()

        override suspend fun apply(
            request: PlaybackOutputRequest,
        ): PlaybackResult<PlaybackOutputApplication> {
            requests += request
            val status = if (request.committed) {
                committedStatus
            } else {
                PlaybackOutputStatus.WAITING_FOR_COMMIT
            }
            return PlaybackResult.Success(PlaybackOutputApplication(status))
        }

        override suspend fun reset(
            releasedGeneration: Long?,
            reason: ActiveWorkReleaseReason,
        ): PlaybackResult<Unit> {
            resets += releasedGeneration to reason
            return PlaybackResult.Success(Unit)
        }
    }

    private data object TestClock : PlaybackClock {
        override fun nowEpochMs(): Long = 0
        override suspend fun delayMs(durationMs: Long) {
            delay(durationMs)
        }
    }

    private class FakeAudioFocusPort : AudioFocusPort {
        private val flow = MutableSharedFlow<AudioFocusEvent>(extraBufferCapacity = 8)
        var acquireCalls = 0
        var abandonCalls = 0
        override fun events(): Flow<AudioFocusEvent> = flow
        override suspend fun acquire(): PlaybackResult<Unit> {
            acquireCalls++
            return PlaybackResult.Success(Unit)
        }
        override suspend fun abandon(): PlaybackResult<Unit> {
            abandonCalls++
            return PlaybackResult.Success(Unit)
        }
        fun emit(event: AudioFocusEvent) { flow.tryEmit(event) }
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

        fun environmentResult(
            input: PlaybackEnvironmentInput,
            snapshot: PlaybackEnvironmentSnapshot = environment(),
        ): PlaybackResult.Success<PlaybackEnvironmentResolution> = PlaybackResult.Success(
            PlaybackEnvironmentResolution(snapshot, input.effectivePreferences),
        )
    }
}
