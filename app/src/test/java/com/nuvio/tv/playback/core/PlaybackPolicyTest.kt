package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {
    private val policy = PlaybackPolicy()

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

    private fun requirements(
        profile: SessionProfile = SessionProfile.FULLSCREEN,
        preferredEngineOrder: List<EngineType> = emptyList(),
        eligibleEngines: Set<EngineType> = setOf(EngineType.MEDIA3, EngineType.LIBMPV),
        subtitlesEnabled: Boolean = false,
        subtitleFidelity: SubtitleFidelity = SubtitleFidelity.COMPATIBLE,
        gpuRenderingAllowed: Boolean = true,
        secureOutputRequired: Boolean = false,
        audioOutput: AudioOutputPreference = AudioOutputPreference.AUTO,
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
        displayModeSwitchAllowed = profile == SessionProfile.FULLSCREEN,
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
        preferredEngineOrder = preferredEngineOrder,
        secureOutputRequired = secureOutputRequired,
        resourceBudget = ResourceBudget(),
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
