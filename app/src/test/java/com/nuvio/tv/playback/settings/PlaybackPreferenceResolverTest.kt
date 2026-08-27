package com.nuvio.tv.playback.settings

import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.AudioMode
import com.nuvio.tv.playback.core.AudioRoute
import com.nuvio.tv.playback.core.AudioRouteCapabilities
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.CompatibilityOutcome
import com.nuvio.tv.playback.core.CompatibilityGraphFingerprint
import com.nuvio.tv.playback.core.CompatibilityRecord
import com.nuvio.tv.playback.core.CompatibilityRuntimeFingerprint
import com.nuvio.tv.playback.core.CompatibilityScopeKey
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.CustomBufferPreference
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.DecoderMode
import com.nuvio.tv.playback.core.DisplayCapabilities
import com.nuvio.tv.playback.core.DrmRequest
import com.nuvio.tv.playback.core.DrmScheme
import com.nuvio.tv.playback.core.EnginePreference
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.EvidenceFact
import com.nuvio.tv.playback.core.EvidenceProvenance
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.GraphOutputProfile
import com.nuvio.tv.playback.core.HdrPreference
import com.nuvio.tv.playback.core.HdrType
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.PreferenceAvailability
import com.nuvio.tv.playback.core.PreferenceReason
import com.nuvio.tv.playback.core.ResolutionAuthority
import com.nuvio.tv.playback.core.ResourceCapabilities
import com.nuvio.tv.playback.core.RuntimeCapabilities
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.core.SubtitleFidelity
import com.nuvio.tv.playback.core.SubtitleFormat
import com.nuvio.tv.playback.core.SurfaceCapabilities
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.core.VideoCodec
import com.nuvio.tv.playback.core.VideoDecoderCapability
import com.nuvio.tv.playback.core.ChangeImpact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPreferenceResolverTest {
    @Test
    fun `DRM hard constraint forces Media3 over explicit mpv`() {
        val requested = defaults(engine = EnginePreference.LIBMPV)
        val result = PlaybackPreferenceResolver.resolve(
            requested,
            context(
                request = PlaybackRequest(
                    url = "https://example.invalid/live.mpd",
                    drm = DrmRequest(DrmScheme.WIDEVINE, "https://license.invalid"),
                    contentType = ContentType.LIVE,
                ),
                capabilities = capabilities(secureSurface = false),
            ),
        )

        assertEquals(EnginePreference.LIBMPV, result.engine.requested)
        assertEquals(EnginePreference.MEDIA3, result.engine.effective)
        assertEquals(ResolutionAuthority.HARD_CONSTRAINT, result.engine.authority)
        assertEquals(PreferenceReason.DRM_REQUIRES_MEDIADRM, result.engine.primaryReason)
    }

    @Test
    fun `stream eligibility beats explicit override and selects alternate when fallback allowed`() {
        val result = PlaybackPreferenceResolver.resolve(
            defaults(engine = EnginePreference.MEDIA3),
            context(eligibleEngines = setOf(EngineType.LIBMPV)),
        )

        assertEquals(EnginePreference.LIBMPV, result.engine.effective)
        assertEquals(ResolutionAuthority.STREAM_ELIGIBILITY, result.engine.authority)
        assertEquals(PreferenceAvailability.UNAVAILABLE, result.engine.availability)
        assertEquals(PreferenceReason.UNSUPPORTED_BY_STREAM, result.engine.primaryReason)
    }

    @Test
    fun `explicit override beats ordinary compatibility success`() {
        val scope = CompatibilityScopeKey("provider|device|stream")
        val result = PlaybackPreferenceResolver.resolve(
            defaults(engine = EnginePreference.LIBMPV),
            context(
                scope = scope,
                records = listOf(record(scope, EngineType.MEDIA3, CompatibilityOutcome.SUCCESS)),
            ),
        )

        assertEquals(EnginePreference.LIBMPV, result.engine.effective)
        assertEquals(ResolutionAuthority.SAVED_USER_OVERRIDE, result.engine.authority)
    }

    @Test
    fun `exact current deterministic fatal record beats explicit override once`() {
        val scope = CompatibilityScopeKey("provider|device|stream")
        val fatal = record(
            scope,
            EngineType.LIBMPV,
            CompatibilityOutcome.DETERMINISTIC_FATAL,
            GraphOutputProfile.MPV_DIRECT,
        )
        val requested = defaults(engine = EnginePreference.LIBMPV).copy(
            expert = ExpertPlaybackPreferences(MpvOutputPreference.DIRECT),
        )

        val result = PlaybackPreferenceResolver.resolve(
            requested,
            context(scope = scope, records = listOf(fatal)),
        )

        assertEquals(EnginePreference.MEDIA3, result.engine.effective)
        assertEquals(ResolutionAuthority.HARD_CONSTRAINT, result.engine.authority)
        assertEquals(PreferenceReason.KNOWN_FATAL_INCOMPATIBILITY, result.engine.primaryReason)
    }

    @Test
    fun `output constraint is resolved before deterministic fatal history`() {
        val scope = CompatibilityScopeKey("provider|device|stream")
        val directFatal = record(
            scope,
            EngineType.LIBMPV,
            CompatibilityOutcome.DETERMINISTIC_FATAL,
            GraphOutputProfile.MPV_DIRECT,
        )
        val defaults = defaults(engine = EnginePreference.LIBMPV)
        val requested = defaults.copy(
            playback = defaults.playback.copy(
                subtitles = defaults.playback.subtitles.copy(fidelity = SubtitleFidelity.FULL),
            ),
            expert = ExpertPlaybackPreferences(MpvOutputPreference.DIRECT),
        )

        val result = PlaybackPreferenceResolver.resolve(
            requested,
            context(
                scope = scope,
                records = listOf(directFatal),
                evidence = StreamEvidence(
                    subtitleFormat = EvidenceFact(SubtitleFormat.ASS, EvidenceProvenance.MANIFEST_CONFIRMED),
                ),
            ),
        )

        assertEquals(MpvOutputPreference.RENDER, result.mpvOutput.effective)
        assertEquals(EnginePreference.LIBMPV, result.engine.effective)
        assertEquals(ResolutionAuthority.SAVED_USER_OVERRIDE, result.engine.authority)
    }

    @Test
    fun `stale deterministic fatal record cannot override user`() {
        val scope = CompatibilityScopeKey("provider|device|stream")
        val stale = record(
            scope,
            EngineType.LIBMPV,
            CompatibilityOutcome.DETERMINISTIC_FATAL,
            GraphOutputProfile.MPV_DIRECT,
        ).copy(expiresAtEpochMs = 10)
        val requested = defaults(engine = EnginePreference.LIBMPV).copy(
            expert = ExpertPlaybackPreferences(MpvOutputPreference.DIRECT),
        )

        val result = PlaybackPreferenceResolver.resolve(
            requested,
            context(scope = scope, records = listOf(stale), now = 20),
        )

        assertEquals(EnginePreference.LIBMPV, result.engine.effective)
        assertEquals(ResolutionAuthority.SAVED_USER_OVERRIDE, result.engine.authority)
    }

    @Test
    fun `network authorization provider TLS DRM and unscoped audio failures never become compatibility exclusions`() {
        val scope = CompatibilityScopeKey("provider|device|stream")
        val requested = defaults(engine = EnginePreference.LIBMPV).copy(
            expert = ExpertPlaybackPreferences(MpvOutputPreference.DIRECT),
        )

        listOf(
            FailureDomain.NETWORK to FailureCode.NETWORK_TIMEOUT,
            FailureDomain.AUTHORIZATION_PROVIDER_LIMIT to FailureCode.AUTHORIZATION_REJECTED,
            FailureDomain.TLS to FailureCode.TLS_HANDSHAKE_FAILED,
            FailureDomain.DRM to FailureCode.DRM_LICENSE_FAILED,
            FailureDomain.AUDIO to FailureCode.AUDIO_OUTPUT_FAILED,
        ).forEach { (domain, code) ->
            val result = PlaybackPreferenceResolver.resolve(
                requested,
                context(
                    scope = scope,
                    records = listOf(
                        record(
                            scope,
                            EngineType.LIBMPV,
                            CompatibilityOutcome.DETERMINISTIC_FATAL,
                            GraphOutputProfile.MPV_DIRECT,
                            domain,
                            code,
                        ),
                    ),
                ),
            )

            assertEquals(domain.name, EnginePreference.LIBMPV, result.engine.effective)
            assertEquals(domain.name, ResolutionAuthority.SAVED_USER_OVERRIDE, result.engine.authority)
        }
    }

    @Test
    fun `AUTO prefers libmpv only after exact Media3 fatal then eligible libmpv success`() {
        val scope = CompatibilityScopeKey("provider|device|stream")
        val media3Fatal = record(
            scope = scope,
            engine = EngineType.MEDIA3,
            outcome = CompatibilityOutcome.DETERMINISTIC_FATAL,
            recordedAt = 10,
        )
        val mpvSuccess = record(
            scope = scope,
            engine = EngineType.LIBMPV,
            outcome = CompatibilityOutcome.SUCCESS,
            recordedAt = 11,
        )

        val result = PlaybackPreferenceResolver.resolve(
            defaults(),
            context(
                scope = scope,
                records = listOf(media3Fatal, mpvSuccess),
            ),
        )

        assertEquals(EnginePreference.LIBMPV, result.engine.effective)
        assertEquals(ResolutionAuthority.LEARNED_COMPATIBILITY, result.engine.authority)
    }

    @Test
    fun `AUTO does not prefer unpaired libmpv success or unproven libmpv fallback`() {
        val scope = CompatibilityScopeKey("provider|device|stream")
        val mpvSuccess = record(scope, EngineType.LIBMPV, CompatibilityOutcome.SUCCESS, recordedAt = 11)
        val successOnly = PlaybackPreferenceResolver.resolve(
            defaults(),
            context(
                scope = scope,
                records = listOf(mpvSuccess),
            ),
        )
        assertEquals(EnginePreference.MEDIA3, successOnly.engine.effective)
        assertEquals(ResolutionAuthority.DEFAULT_POLICY, successOnly.engine.authority)

        val fatalOnly = PlaybackPreferenceResolver.resolve(
            defaults(),
            context(
                scope = scope,
                records = listOf(
                    record(
                        scope,
                        EngineType.MEDIA3,
                        CompatibilityOutcome.DETERMINISTIC_FATAL,
                        recordedAt = 12,
                    ),
                ),
            ),
        )
        assertNull(fatalOnly.engine.effective)
        assertEquals(PreferenceAvailability.UNAVAILABLE, fatalOnly.engine.availability)
    }

    @Test
    fun `AUTO requires fallback success at or after the Media3 failure`() {
        val scope = CompatibilityScopeKey("provider|device|stream")
        val result = PlaybackPreferenceResolver.resolve(
            defaults(),
            context(
                scope = scope,
                records = listOf(
                    record(scope, EngineType.LIBMPV, CompatibilityOutcome.SUCCESS, recordedAt = 9),
                    record(
                        scope,
                        EngineType.MEDIA3,
                        CompatibilityOutcome.DETERMINISTIC_FATAL,
                        recordedAt = 10,
                    ),
                ),
            ),
        )

        assertNull(result.engine.effective)
        assertEquals(PreferenceAvailability.UNAVAILABLE, result.engine.availability)
    }

    @Test
    fun `AUTO ignores libmpv success for a different eligible graph`() {
        val scope = CompatibilityScopeKey("provider|device|stream")
        val media3Graph = graph(EngineType.MEDIA3, GraphOutputProfile.MEDIA3_STANDARD)
        val renderGraph = graph(EngineType.LIBMPV, GraphOutputProfile.MPV_RENDER)
        val result = PlaybackPreferenceResolver.resolve(
            defaults(),
            context(
                scope = scope,
                records = listOf(
                    record(
                        scope,
                        EngineType.MEDIA3,
                        CompatibilityOutcome.DETERMINISTIC_FATAL,
                        recordedAt = 10,
                    ),
                    record(scope, EngineType.LIBMPV, CompatibilityOutcome.SUCCESS, recordedAt = 11),
                ),
                eligibleGraphs = setOf(media3Graph, renderGraph),
            ),
        )

        assertNull(result.engine.effective)
        assertEquals(PreferenceAvailability.UNAVAILABLE, result.engine.availability)
    }

    @Test
    fun `AUTO ignores otherwise valid failure success pair from another scope`() {
        val scope = CompatibilityScopeKey("provider|device|stream")
        val otherScope = CompatibilityScopeKey("other-provider|device|stream")
        val result = PlaybackPreferenceResolver.resolve(
            defaults(),
            context(
                scope = scope,
                records = listOf(
                    record(
                        scope,
                        EngineType.MEDIA3,
                        CompatibilityOutcome.DETERMINISTIC_FATAL,
                        recordedAt = 10,
                    ),
                    record(otherScope, EngineType.LIBMPV, CompatibilityOutcome.SUCCESS, recordedAt = 11),
                ),
            ),
        )

        assertNull(result.engine.effective)
        assertEquals(PreferenceAvailability.UNAVAILABLE, result.engine.availability)
    }

    @Test
    fun `history from another stable runtime fingerprint is ignored`() {
        val scope = CompatibilityScopeKey("provider|device|stream")
        val otherRuntime = CompatibilityRuntimeFingerprint(
            deviceVersion = "device-v1",
            firmwareVersion = "firmware-v2",
            capabilityFingerprint = "capabilities-v2",
        )
        val fatal = record(
            scope,
            EngineType.LIBMPV,
            CompatibilityOutcome.DETERMINISTIC_FATAL,
            GraphOutputProfile.MPV_DIRECT,
        ).copy(runtime = otherRuntime)
        val requested = defaults(engine = EnginePreference.LIBMPV).copy(
            expert = ExpertPlaybackPreferences(MpvOutputPreference.DIRECT),
        )

        val result = PlaybackPreferenceResolver.resolve(
            requested,
            context(scope = scope, records = listOf(fatal)),
        )

        assertEquals(EnginePreference.LIBMPV, result.engine.effective)
        assertEquals(ResolutionAuthority.SAVED_USER_OVERRIDE, result.engine.authority)
    }

    @Test
    fun `passthrough wins over incompatible PCM processing without changing request`() {
        val defaults = CleanPlaybackPreferences.recommended()
        val requested = defaults.copy(
            playback = defaults.playback.copy(
                audio = defaults.playback.audio.copy(
                    output = AudioOutputPreference.PASSTHROUGH,
                    downmixToStereo = true,
                    normalization = true,
                    skipSilence = true,
                ),
            ),
        )

        val result = PlaybackPreferenceResolver.resolve(requested, context())

        assertTrue(result.requested.playback.audio.downmixToStereo)
        assertFalse(result.effective.playback.audio.downmixToStereo)
        assertFalse(result.effective.playback.audio.normalization)
        assertFalse(result.effective.playback.audio.skipSilence)
        assertEquals(PreferenceReason.PCM_PROCESSING_REQUIRES_DECODED_AUDIO, result.downmix.primaryReason)
        assertTrue(result.downmix.conflicts.isNotEmpty())
    }

    @Test
    fun `unsupported Dolby Vision remains conservative without HDR10 stream evidence`() {
        val defaults = CleanPlaybackPreferences.recommended()
        val requested = defaults.copy(
            playback = defaults.playback.copy(
                video = defaults.playback.video.copy(hdr = HdrPreference.DOLBY_VISION),
            ),
        )

        val result = PlaybackPreferenceResolver.resolve(
            requested,
            context(capabilities = capabilities(hdrTypes = setOf(HdrType.HDR10))),
        )

        assertEquals(HdrPreference.AUTO, result.hdr.effective)
        assertEquals(PreferenceAvailability.UNAVAILABLE, result.hdr.availability)
        assertEquals(PreferenceReason.DOLBY_VISION_OUTPUT_UNAVAILABLE, result.hdr.primaryReason)
    }

    @Test
    fun `full ASS fidelity forces mpv render and rapid live zap defers AFR`() {
        val defaults = CleanPlaybackPreferences.recommended()
        val requested = defaults.copy(
            playback = defaults.playback.copy(
                subtitles = defaults.playback.subtitles.copy(fidelity = SubtitleFidelity.FULL),
                display = defaults.playback.display.copy(frameRate = FrameRatePreference.ON_RATE_CHANGE),
            ),
            expert = ExpertPlaybackPreferences(MpvOutputPreference.DIRECT),
        )
        val result = PlaybackPreferenceResolver.resolve(
            requested,
            context(
                evidence = StreamEvidence(
                    subtitleFormat = EvidenceFact(SubtitleFormat.ASS, EvidenceProvenance.MANIFEST_CONFIRMED),
                ),
                rapidLiveZapping = true,
            ),
        )

        assertEquals(MpvOutputPreference.RENDER, result.mpvOutput.effective)
        assertEquals(PreferenceReason.SUBTITLE_FIDELITY_REQUIRES_RENDER, result.mpvOutput.primaryReason)
        assertEquals(FrameRatePreference.ON_START, result.frameRate.effective)
        assertEquals(PreferenceReason.AFR_DEFERRED_DURING_ZAP, result.frameRate.primaryReason)
    }

    @Test
    fun `no eligible engine is typed unavailable`() {
        val result = PlaybackPreferenceResolver.resolve(defaults(), context(eligibleEngines = emptySet()))

        assertNull(result.engine.effective)
        assertEquals(PreferenceAvailability.UNAVAILABLE, result.engine.availability)
    }

    @Test
    fun `software 4k AV1 is unavailable when memory is unsafe and no hardware graph exists`() {
        val defaults = CleanPlaybackPreferences.recommended()
        val requested = defaults.copy(
            playback = defaults.playback.copy(decoder = DecoderPreference.SOFTWARE_ONLY),
        )
        val evidence = StreamEvidence(
            videoCodec = EvidenceFact(VideoCodec.AV1, EvidenceProvenance.EXTRACTOR_CONFIRMED),
            dimensions = EvidenceFact(VideoDimensions(3_840, 2_160), EvidenceProvenance.EXTRACTOR_CONFIRMED),
            frameRate = EvidenceFact(60.0, EvidenceProvenance.EXTRACTOR_CONFIRMED),
        )

        val result = PlaybackPreferenceResolver.resolve(
            requested,
            context(
                evidence = evidence,
                capabilities = capabilities(availableMemoryBytes = 400_000_000, lowMemory = true),
            ),
        )

        assertNull(result.decoder.effective)
        assertEquals(PreferenceAvailability.UNAVAILABLE, result.decoder.availability)
        assertEquals(PreferenceReason.SOFTWARE_DECODE_EXCEEDS_RESOURCE_BUDGET, result.decoder.primaryReason)
    }

    @Test
    fun `software 4k AV1 resolves to hardware when an eligible decoder exists`() {
        val defaults = CleanPlaybackPreferences.recommended()
        val requested = defaults.copy(
            playback = defaults.playback.copy(decoder = DecoderPreference.SOFTWARE_ONLY),
        )
        val evidence = StreamEvidence(
            videoCodec = EvidenceFact(VideoCodec.AV1, EvidenceProvenance.EXTRACTOR_CONFIRMED),
            dimensions = EvidenceFact(VideoDimensions(3_840, 2_160), EvidenceProvenance.EXTRACTOR_CONFIRMED),
            frameRate = EvidenceFact(60.0, EvidenceProvenance.EXTRACTOR_CONFIRMED),
        )
        val hardware = VideoDecoderCapability(
            stableId = "decoder.av1.hardware",
            codec = VideoCodec.AV1,
            hardwareAccelerated = true,
            softwareOnly = false,
            vendorProvided = true,
            securePlayback = false,
            maxDimensions = VideoDimensions(3_840, 2_160),
            maxFrameRate = 60.0,
        )

        val result = PlaybackPreferenceResolver.resolve(
            requested,
            context(
                evidence = evidence,
                capabilities = capabilities(
                    availableMemoryBytes = 400_000_000,
                    lowMemory = true,
                    videoDecoders = listOf(hardware),
                ),
            ),
        )

        assertEquals(DecoderPreference.HARDWARE_ONLY, result.decoder.effective)
        assertEquals(ResolutionAuthority.HARD_CONSTRAINT, result.decoder.authority)
    }

    @Test
    fun `unknown decoder dimensions frame rate or stream frame rate never establish hardware fallback`() {
        val defaults = CleanPlaybackPreferences.recommended()
        val requested = defaults.copy(
            playback = defaults.playback.copy(decoder = DecoderPreference.SOFTWARE_ONLY),
        )
        val baseEvidence = StreamEvidence(
            videoCodec = EvidenceFact(VideoCodec.AV1, EvidenceProvenance.EXTRACTOR_CONFIRMED),
            dimensions = EvidenceFact(VideoDimensions(3_840, 2_160), EvidenceProvenance.EXTRACTOR_CONFIRMED),
            frameRate = EvidenceFact(60.0, EvidenceProvenance.EXTRACTOR_CONFIRMED),
        )
        val decoder = VideoDecoderCapability(
            stableId = "decoder.av1.hardware",
            codec = VideoCodec.AV1,
            hardwareAccelerated = true,
            softwareOnly = false,
            vendorProvided = true,
            securePlayback = false,
            maxDimensions = VideoDimensions(3_840, 2_160),
            maxFrameRate = 60.0,
        )
        val unknownCases = listOf(
            baseEvidence to decoder.copy(maxDimensions = null),
            baseEvidence to decoder.copy(maxFrameRate = null),
            baseEvidence.copy(frameRate = null) to decoder,
        )

        unknownCases.forEach { (evidence, candidate) ->
            val result = PlaybackPreferenceResolver.resolve(
                requested,
                context(
                    evidence = evidence,
                    capabilities = capabilities(
                        availableMemoryBytes = 400_000_000,
                        lowMemory = true,
                        videoDecoders = listOf(candidate),
                    ),
                ),
            )

            assertNull(result.decoder.effective)
            assertEquals(PreferenceAvailability.UNAVAILABLE, result.decoder.availability)
        }
    }

    @Test
    fun `known decoder below required frame rate never establishes hardware fallback`() {
        val defaults = CleanPlaybackPreferences.recommended()
        val requested = defaults.copy(
            playback = defaults.playback.copy(decoder = DecoderPreference.SOFTWARE_ONLY),
        )
        val evidence = StreamEvidence(
            videoCodec = EvidenceFact(VideoCodec.AV1, EvidenceProvenance.EXTRACTOR_CONFIRMED),
            dimensions = EvidenceFact(VideoDimensions(3_840, 2_160), EvidenceProvenance.EXTRACTOR_CONFIRMED),
            frameRate = EvidenceFact(60.0, EvidenceProvenance.EXTRACTOR_CONFIRMED),
        )
        val insufficient = VideoDecoderCapability(
            stableId = "decoder.av1.hardware",
            codec = VideoCodec.AV1,
            hardwareAccelerated = true,
            softwareOnly = false,
            vendorProvided = true,
            securePlayback = false,
            maxDimensions = VideoDimensions(3_840, 2_160),
            maxFrameRate = 30.0,
        )

        val result = PlaybackPreferenceResolver.resolve(
            requested,
            context(
                evidence = evidence,
                capabilities = capabilities(
                    availableMemoryBytes = 400_000_000,
                    lowMemory = true,
                    videoDecoders = listOf(insufficient),
                ),
            ),
        )

        assertNull(result.decoder.effective)
        assertEquals(PreferenceAvailability.UNAVAILABLE, result.decoder.availability)
    }

    @Test
    fun `custom buffer is clamped to device budget without changing requested value`() {
        val defaults = CleanPlaybackPreferences.recommended()
        val custom = CustomBufferPreference(10_000, 60_000, 5_000, 5_000)
        val requested = defaults.copy(
            playback = defaults.playback.copy(
                buffering = BufferingPreference.CUSTOM,
                customBuffer = custom,
            ),
        )

        val result = PlaybackPreferenceResolver.resolve(
            requested,
            context(capabilities = capabilities(availableMemoryBytes = 400_000_000, lowMemory = true)),
        )

        assertEquals(custom, result.customBuffer.requested)
        assertEquals(15_000, result.customBuffer.effective?.maximumBufferMs)
        assertEquals(PreferenceReason.BUFFER_CLAMPED_TO_MEMORY_BUDGET, result.customBuffer.primaryReason)
    }

    @Test
    fun `every persisted field exposes its own runtime impact`() {
        val defaults = CleanPlaybackPreferences.recommended()
        val requested = defaults.copy(
            playback = defaults.playback.copy(
                automaticFallback = false,
                audio = defaults.playback.audio.copy(preferredLanguage = "fr"),
            ),
        )

        val result = PlaybackPreferenceResolver.resolve(requested, context())

        assertEquals(ChangeImpact.NEXT_SESSION_ONLY, result.automaticFallback.impact)
        assertEquals(ChangeImpact.RESELECT_GRAPH, result.decoder.impact)
        assertEquals(ChangeImpact.REBUILD_CURRENT_GRAPH, result.buffering.impact)
        assertEquals(ChangeImpact.APPLY_IN_PLACE, result.audioLanguage.impact)
        assertEquals(ChangeImpact.RESELECT_GRAPH, result.subtitleFidelity.impact)
        assertEquals(ChangeImpact.APPLY_IN_PLACE, result.showStatusIndicators.impact)
    }

    private fun defaults(engine: EnginePreference = EnginePreference.AUTO): CleanPlaybackPreferences {
        val defaults = CleanPlaybackPreferences.recommended()
        return defaults.copy(playback = defaults.playback.copy(engine = engine))
    }

    private fun context(
        request: PlaybackRequest = PlaybackRequest("https://example.invalid/live.ts", contentType = ContentType.LIVE),
        evidence: StreamEvidence = StreamEvidence(),
        capabilities: RuntimeCapabilities = capabilities(),
        eligibleEngines: Set<EngineType> = EngineType.entries.toSet(),
        scope: CompatibilityScopeKey? = null,
        records: List<CompatibilityRecord> = emptyList(),
        eligibleGraphs: Set<CompatibilityGraphFingerprint> = defaultGraphs(),
        runtimeFingerprint: CompatibilityRuntimeFingerprint = runtime,
        now: Long = 100,
        rapidLiveZapping: Boolean = false,
    ) = PlaybackPreferenceResolutionContext(
        request = request.summary(),
        evidence = evidence,
        capabilities = capabilities,
        eligibleEngines = eligibleEngines,
        compatibilityScopeKey = scope,
        compatibilityRecords = records,
        eligibleGraphFingerprints = eligibleGraphs,
        compatibilityRuntime = runtimeFingerprint,
        nowEpochMs = now,
        appVersion = "app-1",
        engineVersions = mapOf(EngineType.MEDIA3 to "media3-1", EngineType.LIBMPV to "mpv-1"),
        rapidLiveZapping = rapidLiveZapping,
    )

    private fun capabilities(
        secureSurface: Boolean = false,
        hdrTypes: Set<HdrType> = emptySet(),
        availableMemoryBytes: Long = 2_000_000_000,
        lowMemory: Boolean = false,
        videoDecoders: List<VideoDecoderCapability> = emptyList(),
    ) = RuntimeCapabilities(
        snapshotVersion = 7,
        capturedAtEpochMs = 1,
        apiLevel = 35,
        videoDecoders = videoDecoders,
        display = DisplayCapabilities(VideoDimensions(3_840, 2_160), hdrTypes = hdrTypes),
        audioRoute = AudioRouteCapabilities(AudioRoute.HDMI),
        resources = ResourceCapabilities(availableMemoryBytes = availableMemoryBytes, lowMemory = lowMemory),
        surfaces = SurfaceCapabilities(secureSurfaceSupported = secureSurface),
    )

    private fun record(
        scope: CompatibilityScopeKey,
        engine: EngineType,
        outcome: CompatibilityOutcome,
        output: GraphOutputProfile = if (engine == EngineType.MEDIA3) {
            GraphOutputProfile.MEDIA3_STANDARD
        } else {
            GraphOutputProfile.MPV_DIRECT
        },
        deterministicFailureDomain: FailureDomain = FailureDomain.VIDEO_DECODER,
        deterministicFailureCode: FailureCode = FailureCode.VIDEO_DECODER_FAILED,
        recordedAt: Long = 1,
    ) = CompatibilityRecord(
        scopeKey = scope,
        graph = graph(engine, output),
        runtime = runtime,
        outcome = outcome,
        failureDomain = if (outcome == CompatibilityOutcome.DETERMINISTIC_FATAL) {
            deterministicFailureDomain
        } else {
            null
        },
        failureCode = if (outcome == CompatibilityOutcome.DETERMINISTIC_FATAL) {
            deterministicFailureCode
        } else {
            null
        },
        appVersion = "app-1",
        engineVersion = if (engine == EngineType.MEDIA3) "media3-1" else "mpv-1",
        recordedAtEpochMs = recordedAt,
        expiresAtEpochMs = 1_000,
    )

    companion object {
        private val runtime = CompatibilityRuntimeFingerprint(
            deviceVersion = "device-v1",
            firmwareVersion = "firmware-v1",
            capabilityFingerprint = "capabilities-v1",
        )

        private fun graph(
            engine: EngineType,
            output: GraphOutputProfile,
        ) = CompatibilityGraphFingerprint(
            engine = engine,
            outputProfile = output,
            decoderMode = DecoderMode.HARDWARE,
            audioMode = AudioMode.DECODE,
            surfaceMode = when (output) {
                GraphOutputProfile.MEDIA3_STANDARD -> SurfaceMode.SURFACE_VIEW
                GraphOutputProfile.MPV_DIRECT -> SurfaceMode.NATIVE_EMBED
                GraphOutputProfile.MPV_RENDER -> SurfaceMode.GPU_RENDER
            },
            secureOutput = false,
            decoderStableId = "decoder.default",
        )

        private fun defaultGraphs(): Set<CompatibilityGraphFingerprint> = setOf(
            graph(EngineType.MEDIA3, GraphOutputProfile.MEDIA3_STANDARD),
            graph(EngineType.LIBMPV, GraphOutputProfile.MPV_DIRECT),
            graph(EngineType.LIBMPV, GraphOutputProfile.MPV_RENDER),
        )
    }
}
