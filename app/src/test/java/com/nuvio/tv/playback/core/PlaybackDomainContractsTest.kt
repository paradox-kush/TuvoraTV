package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDomainContractsTest {
    @Test
    fun `evidence preserves independent provenance without aggregate score`() {
        val evidence = StreamEvidence(
            delivery = EvidenceFact(DeliveryType.HLS, EvidenceProvenance.MANIFEST_CONFIRMED),
            container = EvidenceFact(ContainerType.MPEG_TS, EvidenceProvenance.SEGMENT_HINT),
            videoCodec = EvidenceFact(VideoCodec.HEVC, EvidenceProvenance.HLS_CODECS_ATTRIBUTE),
            subtitleFormat = EvidenceFact(SubtitleFormat.ASS, EvidenceProvenance.EXTRACTOR_CONFIRMED),
        )

        assertEquals(EvidenceProvenance.MANIFEST_CONFIRMED, evidence.delivery?.provenance)
        assertEquals(EvidenceProvenance.SEGMENT_HINT, evidence.container?.provenance)
        assertEquals(EvidenceProvenance.HLS_CODECS_ATTRIBUTE, evidence.videoCodec?.provenance)
        assertEquals(EvidenceProvenance.EXTRACTOR_CONFIRMED, evidence.subtitleFormat?.provenance)
        assertFalse(StreamEvidence::class.java.declaredFields.any { it.name.contains("score", ignoreCase = true) })
    }

    @Test
    fun `recommended preferences are versioned engine-neutral intent`() {
        val preferences = PlaybackPreferences.recommended()

        assertEquals(PlaybackPreferences.CURRENT_SCHEMA_VERSION, preferences.schemaVersion)
        assertEquals(EnginePreference.AUTO, preferences.engine)
        assertTrue(preferences.automaticFallback)
        assertEquals(DecoderPreference.AUTO, preferences.decoder)
        assertEquals(AudioOutputPreference.AUTO, preferences.audio.output)
        assertEquals(FrameRatePreference.ON_COMMITTED_PLAYBACK, preferences.display.frameRate)
    }

    @Test
    fun `preference resolution preserves requested value and explains effective value`() {
        val resolution = PreferenceResolution(
            requested = EnginePreference.LIBMPV,
            effective = EnginePreference.MEDIA3,
            authority = ResolutionAuthority.HARD_CONSTRAINT,
            availability = PreferenceAvailability.UNAVAILABLE,
            primaryReason = PreferenceReason.DRM_REQUIRES_MEDIADRM,
            contributingReasons = setOf(PreferenceReason.UNSUPPORTED_BY_STREAM),
            impact = ChangeImpact.RESELECT_GRAPH,
        )

        assertEquals(EnginePreference.LIBMPV, resolution.requested)
        assertEquals(EnginePreference.MEDIA3, resolution.effective)
        assertEquals(PreferenceReason.DRM_REQUIRES_MEDIADRM, resolution.primaryReason)
        assertEquals(ChangeImpact.RESELECT_GRAPH, resolution.impact)
    }

    @Test
    fun `guide preview failure does not imply stream unavailability`() {
        val snapshot = PlaybackSnapshot(
            profile = SessionProfile.GUIDE,
            previewAvailability = PreviewAvailability.Unavailable(
                PreviewUnavailableReason.GUIDE_RENDER_PATH_UNAVAILABLE,
            ),
        )

        assertTrue(snapshot.previewAvailability is PreviewAvailability.Unavailable)
        assertEquals(StreamAvailability.Unknown, snapshot.streamAvailability)
    }

    @Test
    fun `terminal stream unavailability requires typed terminal evidence`() {
        val unavailable = StreamAvailability.TerminallyUnavailable(
            reason = StreamUnavailableReason.NO_ELIGIBLE_GRAPH,
            evidence = TerminalAvailabilityEvidence.ALL_ELIGIBLE_GRAPHS_EXHAUSTED,
        )

        assertEquals(
            TerminalAvailabilityEvidence.ALL_ELIGIBLE_GRAPHS_EXHAUSTED,
            unavailable.evidence,
        )
        assertNotEquals(PreviewAvailability.Unavailable(PreviewUnavailableReason.ALL_PREVIEW_GRAPHS_FAILED), unavailable)
    }

    @Test
    fun `snapshot starts idle without inventing playback progress`() {
        val snapshot = PlaybackSnapshot()

        assertEquals(PlaybackState.IDLE, snapshot.state)
        assertEquals(0, snapshot.generation)
        assertFalse(snapshot.isPlaying)
        assertFalse(snapshot.progress.receivedBytes)
        assertFalse(snapshot.progress.discoveredTracks)
        assertFalse(snapshot.progress.renderedAudio)
        assertFalse(snapshot.progress.renderedVideoFrame)
        assertNull(snapshot.failure)
    }

    @Test
    fun `normalized events retain request generation`() {
        val failure = PlaybackFailure(
            code = FailureCode.NETWORK_TIMEOUT,
            domain = FailureDomain.NETWORK,
            phase = FailurePhase.PLAYBACK,
            retryability = Retryability.RETRYABLE_WITH_FRESH_REQUEST,
        )
        val event = PlaybackEvent.Failed(generation = 17, failure = failure)

        assertEquals(17, event.generation)
        assertEquals(FailureDomain.NETWORK, event.failure.domain)
    }

    @Test
    fun `deferred provider selection is URL-free and redacted through command and state strings`() {
        val accountSecret = "https://provider.invalid/get.php?username=alice&password=secret"
        val itemSecret = "single-use-stream-token"
        val contentSecret = "provider:live:private-channel"
        val selection = ProviderPlaybackSelection(
            sourceType = ProviderSourceType.STALKER,
            accountId = ProviderSelectionId(accountSecret),
            itemId = ProviderSelectionId(itemSecret),
            contentKey = ProviderSelectionId(contentSecret),
            contentType = ContentType.LIVE,
            declaredEvidence = StreamEvidence(
                container = EvidenceFact(ContainerType.MPEG_TS, EvidenceProvenance.PROVIDER_DECLARED),
            ),
        )
        val command = PlaybackCommand.Tune(selection, SessionProfile.FULLSCREEN)
        val state = PlaybackMachineState(launch = command.launch)
        val rendered = listOf(selection, command, state).joinToString("\n")

        assertFalse(rendered.contains(accountSecret))
        assertFalse(rendered.contains(itemSecret))
        assertFalse(rendered.contains(contentSecret))
        assertFalse(ProviderPlaybackSelection::class.java.declaredFields.any {
            it.name.equals("url", ignoreCase = true) ||
                it.name.contains("password", ignoreCase = true) ||
                it.name.contains("credential", ignoreCase = true)
        })
        assertTrue(rendered.contains("sourceType=STALKER"))
        assertTrue(rendered.contains("contentType=LIVE"))
    }

    @Test
    fun `catch-up provider selection requires finite bounds`() {
        val selection = ProviderPlaybackSelection(
            sourceType = ProviderSourceType.XTREAM,
            accountId = ProviderSelectionId("account"),
            itemId = ProviderSelectionId("channel"),
            contentKey = ProviderSelectionId("content"),
            contentType = ContentType.CATCH_UP,
            catchUpWindow = ProviderCatchUpWindow(1_000, 2_000),
        )

        assertEquals(ContentType.CATCH_UP, selection.contentType)
        assertEquals("ProviderCatchUpWindow(hasBounds=true)", selection.catchUpWindow.toString())
    }

    @Test
    fun `compatibility key is redacted and record expiration is deterministic`() {
        val key = CompatibilityScopeKey("provider-and-capability-scope")
        val record = CompatibilityRecord(
            scopeKey = key,
            graph = compatibilityGraph(EngineType.MEDIA3, GraphOutputProfile.MEDIA3_STANDARD),
            runtime = compatibilityRuntime(),
            outcome = CompatibilityOutcome.DETERMINISTIC_FATAL,
            failureDomain = FailureDomain.VIDEO_DECODER,
            failureCode = FailureCode.VIDEO_DECODER_FAILED,
            appVersion = "1.0",
            engineVersion = "1.11.0",
            recordedAtEpochMs = 1_000,
            expiresAtEpochMs = 2_000,
        )

        assertFalse(record.toString().contains("provider-and-capability-scope"))
        assertFalse(record.isExpired(1_999))
        assertTrue(record.isExpired(2_000))
    }

    @Test
    fun `compatibility failure classifier is closed and exhaustive`() {
        val accepted = setOf(
            FailureDomain.MANIFEST to FailureCode.MANIFEST_INVALID,
            FailureDomain.DEMUX to FailureCode.DEMUX_FAILED,
            FailureDomain.VIDEO_DECODER to FailureCode.VIDEO_DECODER_UNAVAILABLE,
            FailureDomain.VIDEO_DECODER to FailureCode.VIDEO_DECODER_FAILED,
            FailureDomain.VIDEO_RENDERER_SURFACE to FailureCode.VIDEO_RENDERER_FAILED,
            FailureDomain.VIDEO_RENDERER_SURFACE to FailureCode.SURFACE_LOST,
        )

        FailureDomain.entries.forEach { domain ->
            FailureCode.entries.forEach { code ->
                assertEquals(
                    "$domain/$code",
                    domain to code in accepted,
                    isLearnableCompatibilityFailure(domain, code),
                )
            }
        }
        assertFalse(isLearnableCompatibilityFailure(null, null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `deterministic fatal compatibility requires a failure domain`() {
        CompatibilityRecord(
            scopeKey = CompatibilityScopeKey("scope"),
            graph = compatibilityGraph(EngineType.LIBMPV, GraphOutputProfile.MPV_DIRECT),
            runtime = compatibilityRuntime(),
            outcome = CompatibilityOutcome.DETERMINISTIC_FATAL,
            appVersion = "1.0",
            engineVersion = "0.41",
            recordedAtEpochMs = 1,
            expiresAtEpochMs = 2,
        )
    }

    private fun compatibilityGraph(
        engine: EngineType,
        output: GraphOutputProfile,
    ) = CompatibilityGraphFingerprint(
        engine = engine,
        outputProfile = output,
        decoderMode = DecoderMode.HARDWARE,
        audioMode = AudioMode.DECODE,
        surfaceMode = if (engine == EngineType.MEDIA3) SurfaceMode.SURFACE_VIEW else SurfaceMode.NATIVE_EMBED,
        secureOutput = false,
    )

    private fun compatibilityRuntime() = CompatibilityRuntimeFingerprint(
        deviceVersion = "device-v1",
        firmwareVersion = "firmware-v1",
        capabilityFingerprint = "capabilities-v1",
    )
}
