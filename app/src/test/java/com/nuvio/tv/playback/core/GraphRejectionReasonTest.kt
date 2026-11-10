package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * NO_ELIGIBLE_GRAPH arrived in the field with nothing recorded before it (138 of 147 events), so we
 * could not tell which filter emptied the candidate list. Every rejection path must now say why.
 */
class GraphRejectionReasonTest {
    private val policy = PlaybackPolicy()

    @Test
    fun `no candidates at all is reported as NO_CANDIDATES`() {
        val rejection = reject(requirements(), emptyList())
        assertEquals(GraphRejectionReason.NO_CANDIDATES, rejection.rejectedBy)
        assertEquals(0, rejection.candidateGraphs)
        assertEquals(emptyMap<GraphRejectionReason, Int>(), rejection.removedBy)
    }

    @Test
    fun `a structurally invalid graph is reported as INVALID_GRAPH`() {
        val malformed = mpvDirect.copy(id = "bad", engine = EngineType.MEDIA3)
        val rejection = reject(requirements(), listOf(malformed))
        assertEquals(GraphRejectionReason.INVALID_GRAPH, rejection.rejectedBy)
        assertEquals(mapOf(GraphRejectionReason.INVALID_GRAPH to 1), rejection.removedBy)
    }

    @Test
    fun `an ineligible engine is reported as ENGINE_OR_SURFACE`() {
        val rejection = reject(requirements(eligibleEngines = setOf(EngineType.LIBMPV)), listOf(media3))
        assertEquals(GraphRejectionReason.ENGINE_OR_SURFACE, rejection.rejectedBy)
    }

    @Test
    fun `a disallowed surface mode is reported as ENGINE_OR_SURFACE`() {
        val rejection = reject(
            requirements(allowedSurfaceModes = setOf(SurfaceMode.TEXTURE_VIEW)),
            listOf(media3, mpvDirect),
        )
        assertEquals(GraphRejectionReason.ENGINE_OR_SURFACE, rejection.rejectedBy)
        assertEquals(2, rejection.candidateGraphs)
        assertEquals(mapOf(GraphRejectionReason.ENGINE_OR_SURFACE to 2), rejection.removedBy)
    }

    @Test
    fun `a handoff with only the failed engine left is reported as HANDOFF_EXCLUDED`() {
        val selection = policy.selectHandoff(requirements(), listOf(media3), failedGraph = media3)
        val rejection = (selection as PlaybackPolicy.Selection.Rejected).failure.graphRejection!!
        assertEquals(GraphRejectionReason.HANDOFF_EXCLUDED, rejection.rejectedBy)
    }

    @Test
    fun `secure output required on a non-secure graph is reported as SECURE_OUTPUT`() {
        val rejection = reject(requirements(secureOutputRequired = true), listOf(media3))
        assertEquals(GraphRejectionReason.SECURE_OUTPUT, rejection.rejectedBy)
    }

    @Test
    fun `hardware-only preference against software graphs is reported as DECODER_PREFERENCE`() {
        val rejection = reject(
            requirements().copy(decoderPreference = DecoderPreference.HARDWARE_ONLY),
            listOf(media3.copy(decoderMode = DecoderMode.SOFTWARE)),
        )
        assertEquals(GraphRejectionReason.DECODER_PREFERENCE, rejection.rejectedBy)
    }

    @Test
    fun `software-only preference against hardware graphs is reported as DECODER_PREFERENCE`() {
        val rejection = reject(
            requirements().copy(decoderPreference = DecoderPreference.SOFTWARE_ONLY),
            listOf(media3, mpvDirect),
        )
        assertEquals(GraphRejectionReason.DECODER_PREFERENCE, rejection.rejectedBy)
        assertEquals(mapOf(GraphRejectionReason.DECODER_PREFERENCE to 2), rejection.removedBy)
    }

    @Test
    fun `auto decoder without software fallback rejects software graphs as DECODER_PREFERENCE`() {
        val rejection = reject(
            requirements().copy(decoderPreference = DecoderPreference.AUTO, softwareDecodeFallbackAllowed = false),
            listOf(mpvDirect.copy(decoderMode = DecoderMode.SOFTWARE)),
        )
        assertEquals(GraphRejectionReason.DECODER_PREFERENCE, rejection.rejectedBy)
    }

    @Test
    fun `passthrough preference against decode graphs is reported as AUDIO_OUTPUT_PREFERENCE`() {
        val rejection = reject(
            requirements(audioOutput = AudioOutputPreference.PASSTHROUGH),
            listOf(media3, mpvDirect),
        )
        assertEquals(GraphRejectionReason.AUDIO_OUTPUT_PREFERENCE, rejection.rejectedBy)
    }

    @Test
    fun `PCM preference against passthrough graphs is reported as AUDIO_OUTPUT_PREFERENCE`() {
        val rejection = reject(
            requirements(audioOutput = AudioOutputPreference.PCM),
            listOf(media3.copy(audioMode = AudioMode.PASSTHROUGH)),
        )
        assertEquals(GraphRejectionReason.AUDIO_OUTPUT_PREFERENCE, rejection.rejectedBy)
    }

    @Test
    fun `GPU render without the GPU budget is reported as GPU_RENDER`() {
        val rejection = reject(
            requirements(eligibleEngines = setOf(EngineType.LIBMPV), gpuRenderingAllowed = false),
            listOf(mpvRender),
        )
        assertEquals(GraphRejectionReason.GPU_RENDER, rejection.rejectedBy)
    }

    // Was "reported as SUBTITLE_FIDELITY": that rejection WAS the field bug (Onn 8YSK95Q6) — every
    // live channel failed. Subtitle fidelity is a soft filter now: it may rank, never reject.
    @Test
    fun `full subtitle fidelity with only direct output still plays direct`() {
        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(
                requirements(subtitlesEnabled = true, subtitleFidelity = SubtitleFidelity.FULL),
                listOf(mpvDirect),
            ),
        )
        assertEquals(mpvDirect, (selection as PlaybackPolicy.Selection.Selected).graph)
    }

    @Test
    fun `each filter counts what it removed and the last one to empty the list is decisive`() {
        val rejection = reject(
            requirements(
                eligibleEngines = setOf(EngineType.LIBMPV),
                audioOutput = AudioOutputPreference.PASSTHROUGH,
                gpuRenderingAllowed = false,
            ),
            listOf(
                media3,
                mpvDirect,
                mpvDirect.copy(id = "mpv-direct-sw", decoderMode = DecoderMode.SOFTWARE),
                mpvRender.copy(audioMode = AudioMode.PASSTHROUGH),
            ),
        )
        assertEquals(4, rejection.candidateGraphs)
        assertEquals(GraphRejectionReason.GPU_RENDER, rejection.rejectedBy)
        assertEquals(
            mapOf(
                GraphRejectionReason.ENGINE_OR_SURFACE to 1,
                GraphRejectionReason.DECODER_PREFERENCE to 1,
                GraphRejectionReason.AUDIO_OUTPUT_PREFERENCE to 1,
                GraphRejectionReason.GPU_RENDER to 1,
            ),
            rejection.removedBy,
        )
    }

    @Test
    fun `a selected graph carries no rejection and selection is unchanged`() {
        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(requirements(), listOf(media3, mpvDirect)),
        )
        assertEquals(media3, (selection as PlaybackPolicy.Selection.Selected).graph)
    }

    @Test
    fun `rejection carries the stream fact codes from the evidence`() {
        val evidence = StreamEvidence(
            delivery = EvidenceFact(DeliveryType.HLS, EvidenceProvenance.MANIFEST_CONFIRMED),
            container = EvidenceFact(ContainerType.MPEG_TS, EvidenceProvenance.MANIFEST_CONFIRMED),
            videoCodec = EvidenceFact(VideoCodec.HEVC, EvidenceProvenance.MANIFEST_CONFIRMED),
            dimensions = EvidenceFact(VideoDimensions(3840, 2160), EvidenceProvenance.MANIFEST_CONFIRMED),
        )
        val selection = policy.selectPrimary(
            PlaybackPolicy.SelectionInput(requirements(), emptyList(), evidence = evidence),
        )
        val stream = (selection as PlaybackPolicy.Selection.Rejected).failure.graphRejection!!.stream
        assertEquals(StreamFactCodes(StreamCodecCode.HEVC, StreamContainerCode.HLS, StreamHeightCode.UHD, null), stream)
    }

    @Test
    fun `stream fact codes map every known enum and omit unknowns`() {
        fun codec(c: VideoCodec) = StreamFactCodes.from(StreamEvidence(videoCodec = EvidenceFact(c, EvidenceProvenance.MANIFEST_CONFIRMED)))
        assertEquals(StreamCodecCode.H264, codec(VideoCodec.AVC).codec)
        assertEquals(StreamCodecCode.HEVC, codec(VideoCodec.HEVC).codec)
        assertEquals(StreamCodecCode.AV1, codec(VideoCodec.AV1).codec)
        assertEquals(StreamCodecCode.VP9, codec(VideoCodec.VP9).codec)
        assertEquals(StreamCodecCode.MPEG2, codec(VideoCodec.MPEG2).codec)
        assertEquals(StreamCodecCode.OTHER, codec(VideoCodec.MPEG4).codec)
        assertEquals(StreamCodecCode.OTHER, codec(VideoCodec.VC1).codec)
        assertNull(codec(VideoCodec.UNKNOWN).codec)
        // Dolby Vision is a profile on top of a base codec we are not told about.
        assertEquals(StreamCodecCode.OTHER, codec(VideoCodec.DOLBY_VISION).codec)
        assertEquals(StreamHdrCode.DV, codec(VideoCodec.DOLBY_VISION).hdr)
        // No HDR fact exists for other codecs: unknown, never guessed as SDR.
        assertNull(codec(VideoCodec.HEVC).hdr)

        fun container(d: DeliveryType?, c: ContainerType?) = StreamFactCodes.from(
            StreamEvidence(
                delivery = d?.let { EvidenceFact(it, EvidenceProvenance.MANIFEST_CONFIRMED) },
                container = c?.let { EvidenceFact(it, EvidenceProvenance.MANIFEST_CONFIRMED) },
            ),
        ).container
        assertEquals(StreamContainerCode.HLS, container(DeliveryType.HLS, ContainerType.FMP4))
        assertEquals(StreamContainerCode.DASH, container(DeliveryType.DASH, null))
        assertEquals(StreamContainerCode.TS, container(DeliveryType.RAW_TRANSPORT_STREAM, null))
        assertEquals(StreamContainerCode.TS, container(DeliveryType.PROGRESSIVE, ContainerType.MPEG_TS))
        assertEquals(StreamContainerCode.MP4, container(null, ContainerType.MP4))
        assertEquals(StreamContainerCode.MP4, container(null, ContainerType.FMP4))
        assertEquals(StreamContainerCode.MKV, container(null, ContainerType.MATROSKA))
        assertEquals(StreamContainerCode.OTHER, container(null, ContainerType.WEBM))
        assertEquals(StreamContainerCode.OTHER, container(DeliveryType.RTSP, null))
        assertNull(container(DeliveryType.UNKNOWN, ContainerType.UNKNOWN))
        assertNull(container(null, null))

        fun height(h: Int) = StreamFactCodes.from(
            StreamEvidence(dimensions = EvidenceFact(VideoDimensions(h * 16 / 9, h), EvidenceProvenance.MANIFEST_CONFIRMED)),
        ).height
        assertEquals(StreamHeightCode.SD, height(576))
        assertEquals(StreamHeightCode.HD, height(720))
        assertEquals(StreamHeightCode.FHD, height(1080))
        assertEquals(StreamHeightCode.FHD, height(1440))
        assertEquals(StreamHeightCode.UHD, height(2160))
        assertEquals(StreamFactCodes(), StreamFactCodes.from(null))
    }

    private fun reject(requirements: PlaybackRequirements, candidates: List<PlaybackGraph>): GraphRejection {
        val selection = policy.selectPrimary(PlaybackPolicy.SelectionInput(requirements, candidates))
        val failure = (selection as PlaybackPolicy.Selection.Rejected).failure
        assertEquals(FailureCode.NO_ELIGIBLE_GRAPH, failure.code)
        return requireNotNull(failure.graphRejection) { "NO_ELIGIBLE_GRAPH must say why" }
    }

    private fun requirements(
        eligibleEngines: Set<EngineType> = setOf(EngineType.MEDIA3, EngineType.LIBMPV),
        subtitlesEnabled: Boolean = false,
        subtitleFidelity: SubtitleFidelity = SubtitleFidelity.COMPATIBLE,
        gpuRenderingAllowed: Boolean = true,
        secureOutputRequired: Boolean = false,
        audioOutput: AudioOutputPreference = AudioOutputPreference.AUTO,
        allowedSurfaceModes: Set<SurfaceMode> = SurfaceMode.entries.toSet(),
    ) = PlaybackRequirements(
        profile = SessionProfile.FULLSCREEN,
        priority = SessionPriority.QUALITY_AND_STABILITY,
        qualityIntent = VideoQualityIntent.FULL,
        displayModeSwitchAllowed = true,
        frameRatePreference = FrameRatePreference.OFF,
        hdrPreference = HdrPreference.AUTO,
        decoderPreference = DecoderPreference.AUTO,
        softwareDecodeFallbackAllowed = false,
        subtitleFidelity = subtitleFidelity,
        subtitlesEnabled = subtitlesEnabled,
        audioOutput = audioOutput,
        pcmProcessingAllowed = true,
        buffering = BufferingPreference.RECOMMENDED,
        gpuRenderingAllowed = gpuRenderingAllowed,
        eligibleEngines = eligibleEngines,
        preferredEngineOrder = emptyList(),
        allowedSurfaceModes = allowedSurfaceModes,
        secureOutputRequired = secureOutputRequired,
        resourceBudget = ResourceBudget(),
    )

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
