package com.nuvio.tv.playback.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequirementsResolverTest {
    private val resolver = DefaultPlaybackRequirementsResolver()

    @Test
    fun `AUTO product policy prefers libmpv for Live and Media3 for VOD`() = runTest {
        val live = resolve(input(summary = requestSummary(contentType = ContentType.LIVE)))
        val vod = resolve(input(summary = requestSummary(contentType = ContentType.VOD)))

        assertEquals(listOf(EngineType.LIBMPV, EngineType.MEDIA3), live.preferredEngineOrder)
        assertEquals(listOf(EngineType.MEDIA3, EngineType.LIBMPV), vod.preferredEngineOrder)
    }

    @Test
    fun `explicit override and compatibility history outrank Live product default`() = runTest {
        val history = resolve(
            input(
                summary = requestSummary(ContentType.LIVE),
                preferredEngineOrder = listOf(EngineType.MEDIA3),
            ),
        )
        val explicit = resolve(
            input(
                summary = requestSummary(ContentType.LIVE),
                preferences = PlaybackPreferences.recommended().copy(engine = EnginePreference.MEDIA3),
                preferredEngineOrder = listOf(EngineType.LIBMPV),
            ),
        )

        assertEquals(listOf(EngineType.MEDIA3, EngineType.LIBMPV), history.preferredEngineOrder)
        assertEquals(listOf(EngineType.MEDIA3, EngineType.LIBMPV), explicit.preferredEngineOrder)
    }

    @Test
    fun `guide uses viewport headroom without forcing HDR display switching or GPU`() = runTest {
        val resolved = resolve(
            input(
                profile = SessionProfile.GUIDE,
                evidence = adaptiveHls,
                preferences = PlaybackPreferences(
                    subtitles = SubtitlePreference(enabled = true, fidelity = SubtitleFidelity.FULL),
                    display = DisplayPreference(
                        frameRate = FrameRatePreference.ON_RATE_CHANGE,
                        resolutionMatching = true,
                    ),
                    video = VideoPreference(hdr = HdrPreference.DOLBY_VISION),
                ),
                previewViewport = VideoDimensions(640, 360),
            ),
        )

        assertEquals(SessionPriority.STARTUP_SPEED, resolved.priority)
        assertEquals(VideoQualityIntent.PREVIEW, resolved.qualityIntent)
        assertEquals(VideoDimensions(640, 360), resolved.preferredAdaptiveDimensions)
        assertEquals(VideoDimensions(960, 540), resolved.adaptiveDimensionCeiling)
        assertNull(resolved.bitrateCeiling)
        assertFalse(resolved.displayModeSwitchAllowed)
        assertFalse(resolved.resolutionMatchingEnabled)
        assertEquals(FrameRatePreference.OFF, resolved.frameRatePreference)
        assertEquals(HdrPreference.AUTO, resolved.hdrPreference)
        assertTrue(resolved.subtitlesEnabled)
        assertEquals(SubtitleFidelity.FULL, resolved.subtitleFidelity)
        assertEquals(BufferingPreference.LOW_LATENCY_LIVE, resolved.buffering)
        assertFalse(resolved.gpuRenderingAllowed)
    }

    @Test
    fun `guide headroom is capped by the current display`() = runTest {
        val resolved = resolve(
            input(
                profile = SessionProfile.GUIDE,
                evidence = adaptiveHls,
                capabilities = capabilities(displayDimensions = VideoDimensions(1280, 720)),
                previewViewport = VideoDimensions(1000, 600),
            ),
        )

        assertEquals(VideoDimensions(1280, 720), resolved.adaptiveDimensionCeiling)
    }

    @Test
    fun `raw transport stream retains original rendition without fake preview ceiling`() = runTest {
        val resolved = resolve(
            input(
                profile = SessionProfile.GUIDE,
                evidence = StreamEvidence(
                    delivery = EvidenceFact(
                        DeliveryType.RAW_TRANSPORT_STREAM,
                        EvidenceProvenance.PROVIDER_DECLARED,
                    ),
                    container = EvidenceFact(ContainerType.MPEG_TS, EvidenceProvenance.EXTRACTOR_CONFIRMED),
                    dimensions = EvidenceFact(VideoDimensions(1920, 1080), EvidenceProvenance.EXTRACTOR_CONFIRMED),
                    adaptive = EvidenceFact(false, EvidenceProvenance.EXTRACTOR_CONFIRMED),
                ),
                previewViewport = VideoDimensions(640, 360),
                budget = ResourceBudget(networkBitrateCeiling = 3_000_000),
            ),
        )

        assertNull(resolved.preferredAdaptiveDimensions)
        assertNull(resolved.adaptiveDimensionCeiling)
        assertNull(resolved.bitrateCeiling)
    }

    @Test
    fun `fullscreen admits supported output preferences and rejects unproven forced HDR`() = runTest {
        val preferences = PlaybackPreferences(
            audio = AudioPreference(output = AudioOutputPreference.PASSTHROUGH),
            display = DisplayPreference(
                frameRate = FrameRatePreference.ON_RATE_CHANGE,
                resolutionMatching = true,
            ),
            video = VideoPreference(hdr = HdrPreference.DOLBY_VISION),
        )
        val resolved = resolve(
            input(
                profile = SessionProfile.FULLSCREEN,
                evidence = adaptiveHls,
                preferences = preferences,
                capabilities = capabilities(
                    hdrTypes = setOf(HdrType.HDR10),
                    encodedAudioFormats = setOf(AudioCodec.AC3),
                ),
            ),
        )

        assertEquals(SessionPriority.QUALITY_AND_STABILITY, resolved.priority)
        assertEquals(VideoDimensions(3840, 2160), resolved.adaptiveDimensionCeiling)
        assertTrue(resolved.displayModeSwitchAllowed)
        assertTrue(resolved.resolutionMatchingEnabled)
        assertEquals(FrameRatePreference.ON_RATE_CHANGE, resolved.frameRatePreference)
        assertEquals(HdrPreference.AUTO, resolved.hdrPreference)
        assertEquals(AudioOutputPreference.PASSTHROUGH, resolved.audioOutput)
        assertFalse(resolved.pcmProcessingAllowed)
    }

    @Test
    fun `fullscreen AFR does not depend on resolution matching`() = runTest {
        val resolved = resolve(
            input(
                profile = SessionProfile.FULLSCREEN,
                evidence = adaptiveHls,
                preferences = PlaybackPreferences(
                    display = DisplayPreference(
                        frameRate = FrameRatePreference.ON_START,
                        resolutionMatching = false,
                    ),
                ),
            ),
        )

        assertTrue(resolved.displayModeSwitchAllowed)
        assertFalse(resolved.resolutionMatchingEnabled)
        assertEquals(FrameRatePreference.ON_START, resolved.frameRatePreference)
    }

    @Test
    fun `passthrough requires affirmative stream codec and active route format evidence`() = runTest {
        val preferences = PlaybackPreferences(
            audio = AudioPreference(output = AudioOutputPreference.PASSTHROUGH),
        )

        val missingRouteEvidence = resolve(
            input(evidence = adaptiveHls, preferences = preferences),
        )
        val missingStreamEvidence = resolve(
            input(
                evidence = adaptiveHls.copy(audioCodec = null),
                preferences = preferences,
                capabilities = capabilities(encodedAudioFormats = setOf(AudioCodec.AC3)),
            ),
        )

        assertEquals(AudioOutputPreference.AUTO, missingRouteEvidence.audioOutput)
        assertTrue(missingRouteEvidence.pcmProcessingAllowed)
        assertEquals(AudioOutputPreference.AUTO, missingStreamEvidence.audioOutput)
        assertTrue(missingStreamEvidence.pcmProcessingAllowed)
    }

    @Test
    fun `explicit secure output and DRM conservatively admit only secure Media3`() = runTest {
        val secureSummary = requestSummary(hasDrm = true)
        val resolved = resolve(
            input(
                summary = secureSummary,
                evidence = adaptiveHls,
                capabilities = capabilities(
                    surfaces = SurfaceCapabilities(
                        surfaceViewSupported = true,
                        textureViewSupported = true,
                        nativeEmbedSupported = true,
                        secureSurfaceSupported = true,
                        secureNativeEmbedSupported = false,
                        gpuRenderingSupported = true,
                        secureGpuRenderingSupported = false,
                    ),
                ),
                secureOutputRequired = true,
            ),
        )

        assertTrue(resolved.secureOutputRequired)
        assertEquals(setOf(EngineType.MEDIA3), resolved.eligibleEngines)
        assertEquals(setOf(SurfaceMode.SURFACE_VIEW), resolved.allowedSurfaceModes)
    }

    @Test
    fun `DRM restricts engine without inventing secure surface requirement`() = runTest {
        val resolved = resolve(
            input(
                summary = requestSummary(hasDrm = true),
                evidence = adaptiveHls,
            ),
        )

        assertFalse(resolved.secureOutputRequired)
        assertEquals(setOf(EngineType.MEDIA3), resolved.eligibleEngines)
        assertTrue(SurfaceMode.TEXTURE_VIEW in resolved.allowedSurfaceModes)
    }

    @Test
    fun `network ceiling remains absent unless supplied by budget evidence`() = runTest {
        val withoutEvidence = resolve(input(evidence = adaptiveHls))
        val withEvidence = resolve(
            input(
                evidence = adaptiveHls,
                budget = ResourceBudget(networkBitrateCeiling = 3_000_000),
            ),
        )

        assertNull(withoutEvidence.bitrateCeiling)
        assertEquals(3_000_000L, withEvidence.bitrateCeiling)
    }

    @Test
    fun `effective buffer audio and subtitle settings reach the adapter contract`() = runTest {
        val customBuffer = CustomBufferPreference(1_000, 20_000, 500, 1_000)
        val preferences = PlaybackPreferences(
            buffering = BufferingPreference.CUSTOM,
            customBuffer = customBuffer,
            audio = AudioPreference(
                output = AudioOutputPreference.PCM,
                downmixToStereo = true,
                normalization = true,
                skipSilence = true,
                preferredLanguage = "eng",
                delayMs = 125,
            ),
            subtitles = SubtitlePreference(
                enabled = true,
                preferredLanguage = "spa",
                delayMs = -250,
            ),
        )

        val resolved = resolve(input(preferences = preferences))

        assertEquals(customBuffer, resolved.customBuffer)
        assertTrue(resolved.audioDownmixToStereo)
        assertTrue(resolved.audioNormalization)
        assertTrue(resolved.audioSkipSilence)
        assertEquals("eng", resolved.preferredAudioLanguage)
        assertEquals(125L, resolved.audioDelayMs)
        assertEquals("spa", resolved.preferredSubtitleLanguage)
        assertEquals(-250L, resolved.subtitleDelayMs)
    }

    @Test
    fun `surface constraints can make every graph ineligible`() = runTest {
        val result = resolver.resolve(
            input(
                evidence = adaptiveHls,
                allowedSurfaceModes = setOf(SurfaceMode.GPU_RENDER),
                capabilities = capabilities(
                    surfaces = SurfaceCapabilities(
                        surfaceViewSupported = true,
                        textureViewSupported = false,
                        nativeEmbedSupported = false,
                        gpuRenderingSupported = false,
                    ),
                ),
            ),
        )

        val failure = result as PlaybackResult.Failure
        assertEquals(FailureCode.NO_ELIGIBLE_GRAPH, failure.failure.code)
        assertEquals(FailureDomain.DEVICE_RESOURCE, failure.failure.domain)
    }

    @Test
    fun `disallowed surface budget rejects the environment`() = runTest {
        val result = resolver.resolve(
            input(
                budget = ResourceBudget(surfaceCost = ResourceAllowance.DISALLOWED),
            ),
        )

        assertTrue(result is PlaybackResult.Failure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `network budget ceiling must be positive when present`() {
        ResourceBudget(networkBitrateCeiling = 0)
    }

    @Test
    fun `quality-only change applies in place`() {
        val previous = requirements(adaptiveCeiling = VideoDimensions(960, 540))
        val next = previous.copy(adaptiveDimensionCeiling = null)

        val diff = PlaybackRequirementsDiffClassifier.classify(previous, next)

        assertEquals(ChangeImpact.APPLY_IN_PLACE, diff.impact)
        assertEquals(setOf(RequirementsField.ADAPTIVE_QUALITY), diff.changedFields)
    }

    @Test
    fun `buffering change rebuilds current graph`() {
        val previous = requirements()
        val next = previous.copy(buffering = BufferingPreference.BALANCED)

        val diff = PlaybackRequirementsDiffClassifier.classify(previous, next)

        assertEquals(ChangeImpact.REBUILD_CURRENT_GRAPH, diff.impact)
        assertEquals(setOf(RequirementsField.BUFFERING), diff.changedFields)
    }

    @Test
    fun `resolution matching change is a display output rebuild`() {
        val previous = requirements().copy(resolutionMatchingEnabled = false)
        val next = previous.copy(resolutionMatchingEnabled = true)

        val diff = PlaybackRequirementsDiffClassifier.classify(previous, next)

        assertEquals(ChangeImpact.REBUILD_CURRENT_GRAPH, diff.impact)
        assertEquals(setOf(RequirementsField.DISPLAY_OUTPUT), diff.changedFields)
    }

    @Test
    fun `engine or surface eligibility change reselects graph`() {
        val previous = requirements()
        val next = previous.copy(
            eligibleEngines = setOf(EngineType.LIBMPV),
            preferredEngineOrder = listOf(EngineType.LIBMPV),
            allowedSurfaceModes = setOf(SurfaceMode.NATIVE_EMBED),
        )

        val diff = PlaybackRequirementsDiffClassifier.classify(previous, next)

        assertEquals(ChangeImpact.RESELECT_GRAPH, diff.impact)
        assertTrue(RequirementsField.ENGINE_ELIGIBILITY in diff.changedFields)
        assertTrue(RequirementsField.SURFACE_ELIGIBILITY in diff.changedFields)
    }

    @Test
    fun `subtitle fidelity or audio output change reselects graph`() {
        val previous = requirements()
        val subtitleChange = previous.copy(
            subtitlesEnabled = true,
            subtitleFidelity = SubtitleFidelity.FULL,
        )
        val audioChange = previous.copy(audioOutput = AudioOutputPreference.PASSTHROUGH)

        assertEquals(
            ChangeImpact.RESELECT_GRAPH,
            PlaybackRequirementsDiffClassifier.classify(previous, subtitleChange).impact,
        )
        assertEquals(
            ChangeImpact.RESELECT_GRAPH,
            PlaybackRequirementsDiffClassifier.classify(previous, audioChange).impact,
        )
    }

    @Test
    fun `track selection and skip silence apply in place while audio pipeline and buffer rebuild`() {
        val previous = requirements()
        val trackSelection = previous.copy(
            subtitlesEnabled = true,
            preferredSubtitleLanguage = "eng",
            subtitleDelayMs = 200,
            preferredAudioLanguage = "spa",
            audioDelayMs = -100,
        )
        val runtimeProcessing = previous.copy(audioSkipSilence = true)
        val audioPipeline = previous.copy(audioNormalization = true)
        val buffer = previous.copy(
            buffering = BufferingPreference.CUSTOM,
            customBuffer = CustomBufferPreference(1_000, 20_000, 500, 1_000),
        )

        assertEquals(
            ChangeImpact.APPLY_IN_PLACE,
            PlaybackRequirementsDiffClassifier.classify(previous, trackSelection).impact,
        )
        assertEquals(
            ChangeImpact.APPLY_IN_PLACE,
            PlaybackRequirementsDiffClassifier.classify(previous, runtimeProcessing).impact,
        )
        assertEquals(
            ChangeImpact.REBUILD_CURRENT_GRAPH,
            PlaybackRequirementsDiffClassifier.classify(previous, audioPipeline).impact,
        )
        assertEquals(
            ChangeImpact.REBUILD_CURRENT_GRAPH,
            PlaybackRequirementsDiffClassifier.classify(previous, buffer).impact,
        )
    }

    private suspend fun resolve(input: PlaybackRequirementsInput): PlaybackRequirements {
        val result = resolver.resolve(input)
        return (result as PlaybackResult.Success).value
    }

    private fun input(
        summary: RequestSummary = requestSummary(),
        evidence: StreamEvidence = StreamEvidence(),
        profile: SessionProfile = SessionProfile.FULLSCREEN,
        preferences: PlaybackPreferences = PlaybackPreferences.recommended(),
        capabilities: RuntimeCapabilities = capabilities(),
        budget: ResourceBudget = ResourceBudget(),
        previewViewport: VideoDimensions? = null,
        eligibleEngines: Set<EngineType> = EngineType.entries.toSet(),
        preferredEngineOrder: List<EngineType> = emptyList(),
        allowedSurfaceModes: Set<SurfaceMode> = SurfaceMode.entries.toSet(),
        secureOutputRequired: Boolean = false,
    ) = PlaybackRequirementsInput(
        requestSummary = summary,
        evidence = evidence,
        profile = profile,
        effectivePreferences = preferences,
        environment = PlaybackEnvironmentSnapshot(
            runtimeCapabilities = capabilities,
            resourceBudget = budget,
            previewViewport = previewViewport,
            eligibleEngines = eligibleEngines,
            preferredEngineOrder = preferredEngineOrder,
            allowedSurfaceModes = allowedSurfaceModes,
            secureOutputRequired = secureOutputRequired,
        ),
    )

    private fun requestSummary(
        contentType: ContentType = ContentType.LIVE,
        hasDrm: Boolean = false,
    ) = RequestSummary(
        scheme = "https",
        contentType = contentType,
        hasAuthorization = false,
        hasCustomHeaders = false,
        hasCookies = false,
        hasUserAgent = false,
        hasReferer = false,
        hasOrigin = false,
        hasDrm = hasDrm,
        redirectPolicy = RedirectPolicy.FOLLOW,
        crossHostAuthorization = CrossHostAuthorization.STRIP,
        tlsPolicy = TlsPolicy.PLATFORM_DEFAULT,
        dnsPolicy = DnsPolicy.SYSTEM,
        providerConnectionConstrained = true,
    )

    private fun capabilities(
        displayDimensions: VideoDimensions = VideoDimensions(3840, 2160),
        hdrTypes: Set<HdrType> = setOf(HdrType.HDR10),
        encodedAudioFormats: Set<AudioCodec> = emptySet(),
        surfaces: SurfaceCapabilities = SurfaceCapabilities(
            surfaceViewSupported = true,
            textureViewSupported = true,
            nativeEmbedSupported = true,
            secureSurfaceSupported = true,
            gpuRenderingSupported = true,
        ),
    ) = RuntimeCapabilities(
        snapshotVersion = 1,
        capturedAtEpochMs = 1,
        apiLevel = 36,
        display = DisplayCapabilities(
            currentDimensions = displayDimensions,
            hdrTypes = hdrTypes,
            modeSwitchSupported = true,
        ),
        audioRoute = AudioRouteCapabilities(
            route = AudioRoute.HDMI,
            encodedFormats = encodedAudioFormats,
        ),
        resources = ResourceCapabilities(
            availableMemoryBytes = 1_000_000_000,
            lowMemory = false,
        ),
        surfaces = surfaces,
    )

    private fun requirements(
        adaptiveCeiling: VideoDimensions? = null,
    ) = PlaybackRequirements(
        profile = SessionProfile.GUIDE,
        priority = SessionPriority.STARTUP_SPEED,
        qualityIntent = VideoQualityIntent.PREVIEW,
        adaptiveDimensionCeiling = adaptiveCeiling,
        displayModeSwitchAllowed = false,
        frameRatePreference = FrameRatePreference.OFF,
        hdrPreference = HdrPreference.AUTO,
        decoderPreference = DecoderPreference.AUTO,
        softwareDecodeFallbackAllowed = false,
        subtitleFidelity = SubtitleFidelity.COMPATIBLE,
        subtitlesEnabled = false,
        audioOutput = AudioOutputPreference.AUTO,
        pcmProcessingAllowed = true,
        buffering = BufferingPreference.LOW_LATENCY_LIVE,
        gpuRenderingAllowed = false,
        eligibleEngines = EngineType.entries.toSet(),
        preferredEngineOrder = listOf(EngineType.MEDIA3, EngineType.LIBMPV),
        secureOutputRequired = false,
        resourceBudget = ResourceBudget(),
    )

    private companion object {
        val adaptiveHls = StreamEvidence(
            delivery = EvidenceFact(DeliveryType.HLS, EvidenceProvenance.MANIFEST_CONFIRMED),
            audioCodec = EvidenceFact(AudioCodec.AC3, EvidenceProvenance.MANIFEST_CONFIRMED),
            adaptive = EvidenceFact(true, EvidenceProvenance.MANIFEST_CONFIRMED),
        )
    }
}
