package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {
    @Test
    fun `authorization evidence has engine-neutral core recovery semantics`() {
        val policy = PlaybackPolicy()
        val media3 = PlaybackFailure(
            FailureCode.AUTHORIZATION_REJECTED,
            FailureDomain.AUTHORIZATION_PROVIDER_LIMIT,
            FailurePhase.PLAYBACK,
            Retryability.FATAL,
            httpStatus = 401,
            statusProvenance = HttpStatusProvenance.CONFIRMED,
        )
        val mpv = media3.copy(
            statusProvenance = HttpStatusProvenance.INFERRED_FROM_NETWORK_ERROR,
        )

        assertEquals(
            Retryability.RETRYABLE_WITH_FRESH_REQUEST,
            policy.normalizeAuthorizationRecovery(media3, providerCanMintFreshRequest = true).retryability,
        )
        assertEquals(
            Retryability.RETRYABLE_WITH_FRESH_REQUEST,
            policy.normalizeAuthorizationRecovery(mpv, providerCanMintFreshRequest = true).retryability,
        )
        assertEquals(
            Retryability.FATAL,
            policy.normalizeAuthorizationRecovery(media3, providerCanMintFreshRequest = false).retryability,
        )
    }

    @Test
    fun `live reconnect budget is per incident rolling and stable-reset gated`() {
        val policy = PlaybackPolicy(
            liveReconnectConfiguration = PlaybackPolicy.LiveReconnectConfiguration(
                maxAttemptsPerIncident = 3,
                minimumStablePlaybackBeforeResetMs = 30_000,
                rollingWindowMs = 120_000,
                maxAttemptsPerRollingWindow = 6,
            ),
        )
        assertTrue(policy.liveReconnectAllowed(2, listOf(0, 1, 2, 3, 4), 5))
        assertFalse(policy.liveReconnectAllowed(3, emptyList(), 5))
        assertFalse(policy.liveReconnectAllowed(0, listOf(0, 1, 2, 3, 4, 5), 5))
        assertFalse(policy.stablePlaybackResetsReconnectPressure(29_999))
        assertTrue(policy.stablePlaybackResetsReconnectPressure(30_000))
        assertEquals(FailureCode.LIVE_RECONNECT_EXHAUSTED, policy.liveReconnectExhaustedFailure().code)
    }

    @Test
    fun `audio decoder failure enters audio ladder while video decoder never does`() {
        val current = requirements(
            preferredEngineOrder = listOf(EngineType.MEDIA3, EngineType.LIBMPV),
        )
        val audio = PlaybackFailure(
            FailureCode.AUDIO_DECODER_FAILED,
            FailureDomain.AUDIO_DECODER,
            FailurePhase.PLAYBACK,
            Retryability.HANDOFF_ELIGIBLE,
        )
        val video = audio.copy(
            code = FailureCode.VIDEO_DECODER_FAILED,
            domain = FailureDomain.VIDEO_DECODER,
        )

        assertEquals(
            EngineType.LIBMPV,
            AudioOutputPolicy.nextRequirements(current, audio, EngineType.MEDIA3)
                ?.preferredEngineOrder?.first(),
        )
        assertEquals(null, AudioOutputPolicy.nextRequirements(current, video, EngineType.MEDIA3))
    }
    private val policy = PlaybackPolicy()

    @Test
    fun `watchdog budgets follow live and VOD bootstrap architecture`() {
        val network = PlaybackNetworkRequest(readTimeoutMs = 60_000, callTimeoutMs = 12_000)

        assertEquals(
            SurfaceAttachmentBudget.WATCHDOG_MS,
            policy.watchdogDelayMs(
                PlaybackPolicy.WatchdogPhase.WAITING_FOR_SURFACE,
                ContentType.LIVE,
                network,
            ),
        )

        assertEquals(
            12_000L,
            policy.watchdogDelayMs(
                PlaybackPolicy.WatchdogPhase.FIRST_MEDIA_BYTE,
                ContentType.LIVE,
                network,
            ),
        )
        assertEquals(
            1_500L,
            policy.watchdogDelayMs(
                PlaybackPolicy.WatchdogPhase.BYTES_TO_TRACKS,
                ContentType.LIVE,
                network,
            ),
        )
        assertEquals(
            3_000L,
            policy.watchdogDelayMs(
                PlaybackPolicy.WatchdogPhase.BYTES_TO_TRACKS,
                ContentType.VOD,
                network,
            ),
        )
        assertEquals(
            2_500L,
            policy.watchdogDelayMs(
                PlaybackPolicy.WatchdogPhase.VIDEO_TRACKS_TO_READY,
                ContentType.LIVE,
                network,
            ),
        )
        assertEquals(
            1_000L,
            policy.watchdogDelayMs(
                PlaybackPolicy.WatchdogPhase.READY_TO_FIRST_VIDEO_FRAME,
                ContentType.LIVE,
                network,
            ),
        )
        assertEquals(
            2_000L,
            policy.watchdogDelayMs(
                PlaybackPolicy.WatchdogPhase.READY_TO_FIRST_VIDEO_FRAME,
                ContentType.CATCH_UP,
                network,
            ),
        )
    }

    // B50: the coordinator's surface wait and this watchdog were both 3 s, so the watchdog fired
    // SURFACE_LOST at the instant the coordinator gave up and blamed the engine. With the
    // coordinator's one fresh-view retry, the watchdog must outlast every wait it can make.
    @Test
    fun `the surface watchdog outlasts every coordinator surface wait`() {
        val watchdog = policy.watchdogDelayMs(
            PlaybackPolicy.WatchdogPhase.WAITING_FOR_SURFACE,
            ContentType.LIVE,
            PlaybackNetworkRequest(),
        )
        val coordinatorWorstCase = SurfaceAttachmentBudget.ATTEMPTS * SurfaceAttachmentBudget.WAIT_MS

        assertTrue("one retry is the contract", SurfaceAttachmentBudget.ATTEMPTS >= 2)
        assertTrue(
            "watchdog $watchdog ms must outlast $coordinatorWorstCase ms of surface waits",
            watchdog > coordinatorWorstCase,
        )
    }

    @Test
    fun `watchdog failures stay in their proven failure domains`() {
        val hls = StreamEvidence(
            delivery = EvidenceFact(DeliveryType.HLS, EvidenceProvenance.MANIFEST_CONFIRMED),
        )
        val raw = StreamEvidence(
            delivery = EvidenceFact(
                DeliveryType.RAW_TRANSPORT_STREAM,
                EvidenceProvenance.EXTRACTOR_CONFIRMED,
            ),
        )

        val noBytes = policy.watchdogFailure(PlaybackPolicy.WatchdogPhase.FIRST_MEDIA_BYTE, hls)
        assertEquals(FailureDomain.NETWORK, noBytes.domain)
        assertEquals(Retryability.RETRYABLE_WITH_FRESH_REQUEST, noBytes.retryability)

        val noSurface = policy.watchdogFailure(PlaybackPolicy.WatchdogPhase.WAITING_FOR_SURFACE, hls)
        assertEquals(FailureCode.SURFACE_LOST, noSurface.code)
        assertEquals(FailureDomain.VIDEO_RENDERER_SURFACE, noSurface.domain)

        val noManifestTracks = policy.watchdogFailure(PlaybackPolicy.WatchdogPhase.BYTES_TO_TRACKS, hls)
        assertEquals(FailureCode.MANIFEST_INVALID, noManifestTracks.code)
        assertEquals(FailureDomain.MANIFEST, noManifestTracks.domain)

        val noDemuxTracks = policy.watchdogFailure(PlaybackPolicy.WatchdogPhase.BYTES_TO_TRACKS, raw)
        assertEquals(FailureCode.DEMUX_FAILED, noDemuxTracks.code)
        assertEquals(FailureDomain.DEMUX, noDemuxTracks.domain)

        val noFrame = policy.watchdogFailure(
            PlaybackPolicy.WatchdogPhase.READY_TO_FIRST_VIDEO_FRAME,
            raw,
        )
        assertEquals(FailureDomain.VIDEO_RENDERER_SURFACE, noFrame.domain)
        assertEquals(Retryability.HANDOFF_ELIGIBLE, noFrame.retryability)

        val noDecoder = policy.watchdogFailure(
            PlaybackPolicy.WatchdogPhase.VIDEO_TRACKS_TO_READY,
            raw,
        )
        assertEquals(FailureCode.VIDEO_DECODER_FAILED, noDecoder.code)
        assertEquals(FailureDomain.VIDEO_DECODER, noDecoder.domain)
        assertEquals(false, noDecoder.deterministic)
    }

    @Test
    fun `a live picture freeze reconnects with a fresh link before any engine handoff`() {
        // Owner decision 2026-10-02: for live IPTV a frozen picture with no buffering signal is
        // usually the source stalling, so the first answer is a fresh link on the same engine.
        // A freeze during that reconnect falls through to the engine handoff.
        val raw = StreamEvidence(
            delivery = EvidenceFact(DeliveryType.RAW_TRANSPORT_STREAM, EvidenceProvenance.EXTRACTOR_CONFIRMED),
        )
        val liveFreeze = policy.watchdogFailure(
            PlaybackPolicy.WatchdogPhase.RUNTIME_VIDEO_PROGRESS,
            raw,
            live = true,
            reconnecting = false,
        )
        assertEquals(FailureCode.NO_PROGRESS, liveFreeze.code)
        assertEquals(Retryability.RETRYABLE_WITH_FRESH_REQUEST, liveFreeze.retryability)

        val freezeWhileReconnecting = policy.watchdogFailure(
            PlaybackPolicy.WatchdogPhase.RUNTIME_VIDEO_PROGRESS,
            raw,
            live = true,
            reconnecting = true,
        )
        assertEquals(Retryability.HANDOFF_ELIGIBLE, freezeWhileReconnecting.retryability)

        val vodFreeze = policy.watchdogFailure(PlaybackPolicy.WatchdogPhase.RUNTIME_VIDEO_PROGRESS, raw)
        assertEquals(Retryability.HANDOFF_ELIGIBLE, vodFreeze.retryability)
    }

    @Test
    fun `runtime video truth is only an advancing same-generation rendered frame counter`() {
        fun metrics(generation: Long, frames: Long?) = PlaybackEngineMetricsSnapshot(
            generation = generation,
            videoFramesRendered = frames,
            videoFramesSkipped = null,
            videoFramesDropped = null,
            audioBuffersRendered = 99,
            audioBuffersSkipped = null,
            audioBuffersDropped = null,
        )

        assertEquals(true, policy.renderedVideoAdvanced(metrics(7, 10), metrics(7, 11)))
        assertEquals(false, policy.renderedVideoAdvanced(metrics(7, 10), metrics(7, 10)))
        assertEquals(null, policy.renderedVideoAdvanced(metrics(7, null), metrics(7, null)))
        assertEquals(null, policy.renderedVideoAdvanced(metrics(7, 10), metrics(8, 11)))
    }

    @Test
    fun `live runtime video must render a fifth of the expected frames when the rate is known`() {
        fun metrics(frames: Long) = PlaybackEngineMetricsSnapshot(
            generation = 7,
            videoFramesRendered = frames,
            videoFramesSkipped = null,
            videoFramesDropped = null,
            audioBuffersRendered = 99,
            audioBuffersSkipped = null,
            audioBuffersDropped = null,
        )

        assertEquals("unknown rate: any new frame", true, policy.renderedVideoAdvanced(metrics(10), metrics(11), live = true))
        assertEquals(false, policy.renderedVideoAdvanced(metrics(10), metrics(10), live = true))
        assertEquals(false, policy.renderedVideoAdvanced(metrics(10), metrics(11), live = true, frameRate = 25f))
        assertEquals(false, policy.renderedVideoAdvanced(metrics(10), metrics(34), live = true, frameRate = 25f))
        assertEquals(true, policy.renderedVideoAdvanced(metrics(10), metrics(35), live = true, frameRate = 25f))
        assertEquals("VOD keeps the any-frame rule", true, policy.renderedVideoAdvanced(metrics(10), metrics(11)))
    }

    @Test
    fun `Media3 is the deterministic default primary graph`() {
        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(requirements(), listOf(mpvRender, media3)),
        )

        assertEquals(media3, selection.selectedGraph())
        assertEquals(PlaybackPolicy.SelectionReason.MEDIA3_DEFAULT, selection.selectedReason())
    }

    @Test
    fun `eligible explicit engine order beats default policy`() {
        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(
                requirements(preferredEngineOrder = listOf(EngineType.LIBMPV, EngineType.MEDIA3)),
                listOf(media3, mpvRender),
            ),
        )

        assertEquals(mpvRender, selection.selectedGraph())
        assertEquals(PlaybackPolicy.SelectionReason.EFFECTIVE_ENGINE_ORDER, selection.selectedReason())
    }

    @Test
    fun `handoff excludes the failed engine and prefers direct output for guide`() {
        val selection = policy.selectHandoff(
            requirements = requirements(profile = SessionProfile.GUIDE),
            candidates = listOf(media3, mpvRender, mpvDirect),
            failedGraph = media3,
        )

        assertEquals(mpvDirect, selection.selectedGraph())
        assertEquals(PlaybackPolicy.SelectionReason.GUIDE_MPV_DIRECT_FALLBACK, selection.selectedReason())
    }

    @Test
    fun `fullscreen live prefers zero-copy direct mpv output over GPU render`() {
        // Field regression (Onn 4K HEVC): fullscreen ranked the GPU render path first, so every
        // 4K frame was copied through OpenGL and playback stuttered while the guide preview —
        // which ranks direct embed first — was smooth. Live is judged on motion, so direct wins.
        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(
                requirements(
                    profile = SessionProfile.FULLSCREEN,
                    preferredEngineOrder = listOf(EngineType.LIBMPV),
                    eligibleEngines = setOf(EngineType.LIBMPV),
                    liveContent = true,
                ),
                listOf(mpvRender, mpvDirect),
            ),
        )

        assertEquals(mpvDirect, selection.selectedGraph())
    }

    @Test
    fun `fullscreen non-live keeps GPU render first so mpv can draw subtitles`() {
        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(
                requirements(
                    profile = SessionProfile.FULLSCREEN,
                    preferredEngineOrder = listOf(EngineType.LIBMPV),
                    eligibleEngines = setOf(EngineType.LIBMPV),
                    liveContent = false,
                ),
                listOf(mpvRender, mpvDirect),
            ),
        )

        assertEquals(mpvRender, selection.selectedGraph())
    }

    @Test
    fun `full subtitle fidelity rejects feature-limited direct output`() {
        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(
                requirements(
                    profile = SessionProfile.FULLSCREEN,
                    preferredEngineOrder = listOf(EngineType.LIBMPV),
                    eligibleEngines = setOf(EngineType.LIBMPV),
                    subtitlesEnabled = true,
                    subtitleFidelity = SubtitleFidelity.FULL,
                ),
                listOf(mpvDirect, mpvRender),
            ),
        )

        assertEquals(mpvRender, selection.selectedGraph())
    }

    // Field regression (Onn support code 8YSK95Q6, 96 users in 30 days): Media3 refused a live tune
    // (audio processing it cannot apply), the handoff then dropped mpv render (no GPU budget for live)
    // and mpv direct (FULL subtitle fidelity), so every channel failed with "This device cannot play
    // this stream". Video is priority, subtitles best-effort: the fidelity filter may rank, never empty.
    @Test
    fun `live handoff keeps direct mpv when full subtitle fidelity would leave nothing playable`() {
        val selection = policy.selectHandoff(
            requirements = requirements(
                profile = SessionProfile.FULLSCREEN,
                subtitlesEnabled = true,
                subtitleFidelity = SubtitleFidelity.FULL,
                gpuRenderingAllowed = false,
                liveContent = true,
            ),
            candidates = listOf(media3, mpvDirect, mpvRender),
            failedGraph = media3,
        )

        assertEquals(mpvDirect, selection.selectedGraph())
    }

    @Test
    fun `full subtitle fidelity never empties primary selection`() {
        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(
                requirements(
                    preferredEngineOrder = listOf(EngineType.LIBMPV),
                    eligibleEngines = setOf(EngineType.LIBMPV),
                    subtitlesEnabled = true,
                    subtitleFidelity = SubtitleFidelity.FULL,
                    gpuRenderingAllowed = false,
                    liveContent = true,
                ),
                listOf(mpvDirect, mpvRender),
            ),
        )

        assertEquals(mpvDirect, selection.selectedGraph())
    }

    @Test
    fun `GPU budget can make render-only fallback ineligible`() {
        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(
                requirements(
                    preferredEngineOrder = listOf(EngineType.LIBMPV),
                    eligibleEngines = setOf(EngineType.LIBMPV),
                    gpuRenderingAllowed = false,
                ),
                listOf(mpvRender),
            ),
        )

        val rejected = selection as PlaybackPolicy.Selection.Rejected
        assertEquals(FailureCode.NO_ELIGIBLE_GRAPH, rejected.failure.code)
        assertTrue(rejected.failure.deterministic)
    }

    // B50: on a software-decode-only box (the emulator) the guide forbade GPU render, so once Media3
    // failed nothing was eligible and the preview ended "render path unavailable". In the guide, no GPU
    // render is a preference: it may rank, never leave nothing to play.
    @Test
    fun `guide falls back to GPU render when it is the only playable graph`() {
        val softwareMedia3 = media3.copy(id = "media3-sw", decoderMode = DecoderMode.SOFTWARE)
        val softwareRender = mpvRender.copy(id = "mpv-render-sw", decoderMode = DecoderMode.SOFTWARE)
        val guide = requirements(
            profile = SessionProfile.GUIDE,
            gpuRenderingAllowed = false,
            softwareDecodeFallbackAllowed = true,
            liveContent = true,
        )

        val handoff = policy.selectHandoff(guide, listOf(softwareMedia3, softwareRender), failedGraph = softwareMedia3)
        assertEquals(softwareRender, handoff.selectedGraph())

        val primary = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(guide.copy(eligibleEngines = setOf(EngineType.LIBMPV)), listOf(softwareRender)),
        )
        assertEquals(softwareRender, primary.selectedGraph())
    }

    @Test
    fun `guide keeps the no GPU render preference while another graph can play`() {
        // FULL subtitle fidelity would pick render over direct if the GPU filter were simply dropped.
        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(
                requirements(
                    profile = SessionProfile.GUIDE,
                    preferredEngineOrder = listOf(EngineType.LIBMPV),
                    eligibleEngines = setOf(EngineType.LIBMPV),
                    subtitlesEnabled = true,
                    subtitleFidelity = SubtitleFidelity.FULL,
                    gpuRenderingAllowed = false,
                    liveContent = true,
                ),
                listOf(mpvRender, mpvDirect),
            ),
        )

        assertEquals(mpvDirect, selection.selectedGraph())
    }

    @Test
    fun `a disallowed GPU budget stays a hard limit in the guide`() {
        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(
                requirements(
                    profile = SessionProfile.GUIDE,
                    eligibleEngines = setOf(EngineType.LIBMPV),
                    gpuRenderingAllowed = false,
                    resourceBudget = ResourceBudget(gpuCost = ResourceAllowance.DISALLOWED),
                ),
                listOf(mpvRender),
            ),
        )

        assertTrue(selection is PlaybackPolicy.Selection.Rejected)
    }

    @Test
    fun `Media3 cannot claim an mpv direct output graph`() {
        val malformed = mpvDirect.copy(id = "media3-mpv-direct", engine = EngineType.MEDIA3)

        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(requirements(), listOf(malformed, media3)),
        )

        assertEquals(media3, selection.selectedGraph())
    }

    @Test
    fun `libmpv cannot claim a Media3 standard output graph`() {
        val malformed = media3.copy(id = "libmpv-media3", engine = EngineType.LIBMPV)

        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(requirements(), listOf(malformed)),
        )

        assertTrue(selection is PlaybackPolicy.Selection.Rejected)
    }

    @Test
    fun `mpv direct cannot claim a GPU render surface`() {
        val malformed = mpvDirect.copy(id = "direct-gpu", surfaceMode = SurfaceMode.GPU_RENDER)

        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(requirements(), listOf(malformed)),
        )

        assertTrue(selection is PlaybackPolicy.Selection.Rejected)
    }

    @Test
    fun `GPU render eligibility requires an actual GPU render surface`() {
        val malformed = mpvRender.copy(id = "render-without-gpu", surfaceMode = SurfaceMode.SURFACE_VIEW)

        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(
                requirements(
                    eligibleEngines = setOf(EngineType.LIBMPV),
                    gpuRenderingAllowed = true,
                ),
                listOf(malformed),
            ),
        )

        assertTrue(selection is PlaybackPolicy.Selection.Rejected)
    }

    @Test
    fun `secure requirement rejects non-secure graphs`() {
        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(
                requirements(secureOutputRequired = true),
                listOf(media3, mpvRender),
            ),
        )

        assertTrue(selection is PlaybackPolicy.Selection.Rejected)
    }

    @Test
    fun `graph selection enforces effective allowed surface modes`() {
        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(
                requirements(allowedSurfaceModes = setOf(SurfaceMode.SURFACE_VIEW)),
                listOf(mpvDirect, media3),
            ),
        )

        assertEquals(media3, selection.selectedGraph())
    }

    @Test
    fun `software graph is not silently selected when software fallback is disabled`() {
        val software = media3.copy(id = "media3-software", decoderMode = DecoderMode.SOFTWARE)

        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(requirements(), listOf(software)),
        )

        assertTrue(selection is PlaybackPolicy.Selection.Rejected)
    }

    @Test
    fun `explicit PCM output rejects passthrough-only graph`() {
        val passthrough = media3.copy(id = "passthrough", audioMode = AudioMode.PASSTHROUGH)

        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(
                requirements(audioOutput = AudioOutputPreference.PCM),
                listOf(passthrough),
            ),
        )

        assertTrue(selection is PlaybackPolicy.Selection.Rejected)
    }

    @Test
    fun `same policy input always yields the same graph`() {
        val input = PlaybackPolicy.SelectionInput(requirements(), listOf(mpvRender, media3, mpvDirect))
        val first = policy.selectPrimary(input)

        repeat(20) { assertEquals(first, policy.selectPrimary(input)) }
    }

    @Test
    fun `live reconnect backoff is immediate then capped indefinitely`() {
        assertEquals(0L, policy.liveReconnectDelayMs(0))
        assertEquals(1_000L, policy.liveReconnectDelayMs(1))
        assertEquals(20_000L, policy.liveReconnectDelayMs(5))
        assertEquals(20_000L, policy.liveReconnectDelayMs(500))
    }

    @Test
    fun `live guide promote keeps the playing graph when only GPU permission flips`() {
        // GUIDE forbids GPU render, FULLSCREEN allows it. For live the direct embed outranks the
        // GPU path in both profiles, so the flip can never change the winner: it must apply in
        // place instead of tearing the player down for an identical re-selection.
        val guide = requirements(profile = SessionProfile.GUIDE, gpuRenderingAllowed = false, liveContent = true)
        val fullscreen = requirements(
            profile = SessionProfile.FULLSCREEN,
            gpuRenderingAllowed = true,
            liveContent = true,
            displayModeSwitchAllowed = false,
        )

        val diff = PlaybackRequirementsDiffClassifier.classify(guide, fullscreen)

        assertEquals(ChangeImpact.APPLY_IN_PLACE, diff.impact)
        assertTrue(RequirementsField.GPU_RENDERING in diff.changedFields)
    }

    @Test
    fun `display output changes apply in place because the output controller owns them`() {
        val guide = requirements(profile = SessionProfile.GUIDE, gpuRenderingAllowed = false, liveContent = true)
        val fullscreen = guide.copy(
            profile = SessionProfile.FULLSCREEN,
            gpuRenderingAllowed = true,
            displayModeSwitchAllowed = true,
            frameRatePreference = FrameRatePreference.ON_START,
        )

        val diff = PlaybackRequirementsDiffClassifier.classify(guide, fullscreen)

        assertTrue(RequirementsField.DISPLAY_OUTPUT in diff.changedFields)
        assertEquals(ChangeImpact.APPLY_IN_PLACE, diff.impact)
    }

    @Test
    fun `non-live GPU permission flip still reselects because render may win fullscreen`() {
        val guide = requirements(profile = SessionProfile.GUIDE, gpuRenderingAllowed = false)
        val fullscreen = requirements(profile = SessionProfile.FULLSCREEN, gpuRenderingAllowed = true)

        assertEquals(
            ChangeImpact.RESELECT_GRAPH,
            PlaybackRequirementsDiffClassifier.classify(guide, fullscreen).impact,
        )
    }

    @Test
    fun `high resolution live streams get a realistic decoder budget while HD keeps the zap budget`() {
        val network = PlaybackNetworkRequest(readTimeoutMs = 60_000, callTimeoutMs = 12_000)
        val uhd = VideoDimensions(3840, 2160)
        val hd = VideoDimensions(1920, 1080)

        for (phase in listOf(
            PlaybackPolicy.WatchdogPhase.VIDEO_TRACKS_TO_READY,
            PlaybackPolicy.WatchdogPhase.READY_TO_FIRST_VIDEO_FRAME,
        )) {
            assertEquals("$phase 4K", 6_000L, policy.watchdogDelayMs(phase, ContentType.LIVE, network, uhd))
            assertEquals("$phase HD", if (phase == PlaybackPolicy.WatchdogPhase.VIDEO_TRACKS_TO_READY) 2_500L else 1_000L, policy.watchdogDelayMs(phase, ContentType.LIVE, network, hd))
            assertEquals("$phase unknown", if (phase == PlaybackPolicy.WatchdogPhase.VIDEO_TRACKS_TO_READY) 2_500L else 1_000L, policy.watchdogDelayMs(phase, ContentType.LIVE, network, null))
        }
        // Resolution never touches the transport phases: those budgets are about bytes, not decoders.
        assertEquals(
            1_500L,
            policy.watchdogDelayMs(PlaybackPolicy.WatchdogPhase.BYTES_TO_TRACKS, ContentType.LIVE, network, uhd),
        )
    }


    @Test
    fun `live rebuffer bound allows custom refill and does not blame decoder compatibility`() {
        val phase = PlaybackPolicy.WatchdogPhase.LIVE_REBUFFER
        assertEquals(30_000L, policy.watchdogDelayMs(phase, ContentType.LIVE, PlaybackNetworkRequest()))
        assertEquals(75_000L, policy.watchdogDelayMs(phase, ContentType.LIVE, PlaybackNetworkRequest(),
            customBuffer = CustomBufferPreference(60_000, 120_000, 1_500, 60_000)))
        val failure = policy.watchdogFailure(phase, StreamEvidence(), live = true)
        assertEquals(FailureDomain.NETWORK, failure.domain)
        assertEquals(Retryability.RETRYABLE_WITH_FRESH_REQUEST, failure.retryability)
        assertEquals(false, failure.deterministic)
    }

    private fun requirements(
        profile: SessionProfile = SessionProfile.FULLSCREEN,
        preferredEngineOrder: List<EngineType> = emptyList(),
        eligibleEngines: Set<EngineType> = setOf(EngineType.MEDIA3, EngineType.LIBMPV),
        subtitlesEnabled: Boolean = false,
        subtitleFidelity: SubtitleFidelity = SubtitleFidelity.COMPATIBLE,
        gpuRenderingAllowed: Boolean = true,
        secureOutputRequired: Boolean = false,
        audioOutput: AudioOutputPreference = AudioOutputPreference.AUTO,
        allowedSurfaceModes: Set<SurfaceMode> = SurfaceMode.entries.toSet(),
        liveContent: Boolean = false,
        displayModeSwitchAllowed: Boolean = profile == SessionProfile.FULLSCREEN,
        softwareDecodeFallbackAllowed: Boolean = false,
        resourceBudget: ResourceBudget = ResourceBudget(),
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
        displayModeSwitchAllowed = displayModeSwitchAllowed,
        frameRatePreference = FrameRatePreference.OFF,
        hdrPreference = HdrPreference.AUTO,
        decoderPreference = DecoderPreference.AUTO,
        softwareDecodeFallbackAllowed = softwareDecodeFallbackAllowed,
        subtitleFidelity = subtitleFidelity,
        subtitlesEnabled = subtitlesEnabled,
        audioOutput = audioOutput,
        pcmProcessingAllowed = true,
        buffering = BufferingPreference.RECOMMENDED,
        gpuRenderingAllowed = gpuRenderingAllowed,
        eligibleEngines = eligibleEngines,
        preferredEngineOrder = preferredEngineOrder,
        allowedSurfaceModes = allowedSurfaceModes,
        secureOutputRequired = secureOutputRequired,
        resourceBudget = resourceBudget,
        liveContent = liveContent,
    )

    private fun PlaybackPolicy.Selection.selectedGraph(): PlaybackGraph =
        (this as PlaybackPolicy.Selection.Selected).graph

    private fun PlaybackPolicy.Selection.selectedReason(): PlaybackPolicy.SelectionReason =
        (this as PlaybackPolicy.Selection.Selected).reason

    private companion object {
        val media3 = PlaybackGraph(
            id = "media3",
            engine = EngineType.MEDIA3,
            outputProfile = GraphOutputProfile.MEDIA3_STANDARD,
            decoderMode = DecoderMode.HARDWARE,
            audioMode = AudioMode.DECODE,
            surfaceMode = SurfaceMode.SURFACE_VIEW,
        )
        val mpvDirect = PlaybackGraph(
            id = "mpv-direct",
            engine = EngineType.LIBMPV,
            outputProfile = GraphOutputProfile.MPV_DIRECT,
            decoderMode = DecoderMode.HARDWARE,
            audioMode = AudioMode.DECODE,
            surfaceMode = SurfaceMode.NATIVE_EMBED,
        )
        val mpvRender = PlaybackGraph(
            id = "mpv-render",
            engine = EngineType.LIBMPV,
            outputProfile = GraphOutputProfile.MPV_RENDER,
            decoderMode = DecoderMode.HARDWARE,
            audioMode = AudioMode.DECODE,
            surfaceMode = SurfaceMode.GPU_RENDER,
        )
    }
}
