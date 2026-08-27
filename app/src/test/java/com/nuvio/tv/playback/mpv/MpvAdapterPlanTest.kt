package com.nuvio.tv.playback.mpv

import com.nuvio.tv.playback.core.AudioMode
import com.nuvio.tv.playback.core.ApplicationDnsKey
import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.CrossHostAuthorization
import com.nuvio.tv.playback.core.DecoderMode
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.GraphOutputProfile
import com.nuvio.tv.playback.core.HdrPreference
import com.nuvio.tv.playback.core.PlaybackEngineStart
import com.nuvio.tv.playback.core.PlaybackGraph
import com.nuvio.tv.playback.core.PlaybackNetworkRequest
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.PlaybackRequirements
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.RedirectPolicy
import com.nuvio.tv.playback.core.ResourceBudget
import com.nuvio.tv.playback.core.SessionPriority
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.core.SubtitleFidelity
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.VideoQualityIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvAdapterPlanTest {
    @Test
    fun `direct graph spells native embed and hardware decode exactly`() {
        val plan = plan(
            start(
                graph = graph(GraphOutputProfile.MPV_DIRECT, DecoderMode.HARDWARE),
                requirements = requirements(subtitles = false),
            ),
        )
        assertEquals("mediacodec_embed", plan.preInitOptions["vo"])
        assertEquals("mediacodec", plan.preInitOptions["hwdec"])
        assertEquals(SurfaceMode.NATIVE_EMBED, plan.surfaceMode)
    }

    @Test
    fun `render software graph spells gpu android and disables hardware decode`() {
        val plan = plan(start(graph = graph(GraphOutputProfile.MPV_RENDER, DecoderMode.SOFTWARE)))
        assertEquals("gpu", plan.preInitOptions["vo"])
        assertEquals("android", plan.preInitOptions["gpu-context"])
        assertEquals("no", plan.preInitOptions["hwdec"])
    }

    @Test
    fun `request material is mapped but never exposed by plan string`() {
        val secretUrl = "https://secret.test/live/token"
        val secret = "Bearer private"
        val plan = plan(
            start(
                request = PlaybackRequest(
                    secretUrl,
                    headers = mapOf("Authorization" to secret),
                    cookies = mapOf("session" to "cookie-secret"),
                    crossHostAuthorization = CrossHostAuthorization.PRESERVE,
                    contentType = ContentType.LIVE,
                ),
            ),
        )
        assertEquals(secret, plan.headers["Authorization"])
        assertFalse(plan.toString().contains(secretUrl))
        assertFalse(plan.toString().contains(secret))
        assertFalse(plan.toString().contains("cookie-secret"))
    }

    @Test
    fun `unsupported network guarantees fail closed instead of being approximated`() {
        val requests = listOf(
            PlaybackRequest("https://example.test/live", redirectPolicy = RedirectPolicy.REJECT, contentType = ContentType.LIVE),
            PlaybackRequest(
                "https://example.test/live",
                network = PlaybackNetworkRequest(callTimeoutMs = 20_000),
                contentType = ContentType.LIVE,
            ),
            PlaybackRequest(
                "https://example.test/live",
                network = PlaybackNetworkRequest(connectTimeoutMs = 16_000),
                contentType = ContentType.LIVE,
            ),
            PlaybackRequest(
                "https://example.test/live",
                headers = mapOf("Authorization" to "secret"),
                crossHostAuthorization = CrossHostAuthorization.STRIP,
                contentType = ContentType.LIVE,
            ),
        )
        requests.forEach { assertTrue(MpvAdapterPlanFactory.create(start(request = it)) is PlaybackResult.Failure) }
    }

    @Test
    fun `application DNS request is admitted with an explicit system fallback`() {
        val plan = plan(
            start(
                request = PlaybackRequest(
                    "https://example.test/live",
                    dnsPolicy = DnsPolicy.SHARED_APPLICATION_RESOLVER,
                    applicationDnsKey = ApplicationDnsKey("provider-dns-a"),
                    contentType = ContentType.LIVE,
                ),
            ),
        )

        assertEquals(MpvDnsMode.SYSTEM_FALLBACK_FOR_APPLICATION_DNS, plan.dnsMode)
        assertTrue(plan.toString().contains("dnsMode=SYSTEM_FALLBACK_FOR_APPLICATION_DNS"))
        assertFalse(plan.preInitOptions.containsKey("http-proxy"))
    }

    @Test
    fun `system DNS request remains system DNS`() {
        val plan = plan(start())

        assertEquals(MpvDnsMode.SYSTEM, plan.dnsMode)
    }

    @Test
    fun `direct rejects subtitles and software decode`() {
        assertTrue(
            MpvAdapterPlanFactory.create(
                start(graph = graph(GraphOutputProfile.MPV_DIRECT, DecoderMode.HARDWARE)),
            ) is PlaybackResult.Failure,
        )
        assertTrue(
            MpvAdapterPlanFactory.create(
                start(
                    graph = graph(GraphOutputProfile.MPV_DIRECT, DecoderMode.SOFTWARE),
                    requirements = requirements(subtitles = false),
                ),
            ) is PlaybackResult.Failure,
        )
    }

    private fun plan(input: PlaybackEngineStart) =
        (MpvAdapterPlanFactory.create(input) as PlaybackResult.Success).value

    private fun start(
        request: PlaybackRequest = PlaybackRequest("https://example.test/live", contentType = ContentType.LIVE),
        graph: PlaybackGraph = graph(GraphOutputProfile.MPV_RENDER, DecoderMode.HARDWARE),
        requirements: PlaybackRequirements = requirements(),
    ) = PlaybackEngineStart(1, request, StreamEvidence(), graph, requirements, startPaused = false)

    private fun graph(profile: GraphOutputProfile, decoder: DecoderMode) = PlaybackGraph(
        id = "mpv",
        engine = EngineType.LIBMPV,
        outputProfile = profile,
        decoderMode = decoder,
        audioMode = AudioMode.DECODE,
        surfaceMode = if (profile == GraphOutputProfile.MPV_DIRECT) SurfaceMode.NATIVE_EMBED else SurfaceMode.GPU_RENDER,
    )

    private fun requirements(subtitles: Boolean = true) = PlaybackRequirements(
        profile = SessionProfile.FULLSCREEN,
        priority = SessionPriority.QUALITY_AND_STABILITY,
        qualityIntent = VideoQualityIntent.FULL,
        displayModeSwitchAllowed = true,
        frameRatePreference = FrameRatePreference.ON_COMMITTED_PLAYBACK,
        hdrPreference = HdrPreference.AUTO,
        decoderPreference = DecoderPreference.AUTO,
        softwareDecodeFallbackAllowed = true,
        subtitleFidelity = SubtitleFidelity.FULL,
        subtitlesEnabled = subtitles,
        audioOutput = AudioOutputPreference.PCM,
        pcmProcessingAllowed = true,
        buffering = BufferingPreference.RECOMMENDED,
        gpuRenderingAllowed = true,
        eligibleEngines = setOf(EngineType.LIBMPV),
        allowedSurfaceModes = setOf(SurfaceMode.NATIVE_EMBED, SurfaceMode.GPU_RENDER),
        secureOutputRequired = false,
        resourceBudget = ResourceBudget(),
    )
}
