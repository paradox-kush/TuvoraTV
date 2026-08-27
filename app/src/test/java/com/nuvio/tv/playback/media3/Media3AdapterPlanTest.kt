package com.nuvio.tv.playback.media3

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import com.nuvio.tv.playback.core.AudioMode
import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.CrossHostAuthorization
import com.nuvio.tv.playback.core.DecoderMode
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.DeliveryType
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.DrmRequest
import com.nuvio.tv.playback.core.DrmScheme
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.EvidenceFact
import com.nuvio.tv.playback.core.EvidenceProvenance
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.GraphOutputProfile
import com.nuvio.tv.playback.core.HdrPreference
import com.nuvio.tv.playback.core.HttpProxyRequest
import com.nuvio.tv.playback.core.PlaybackGraph
import com.nuvio.tv.playback.core.PlaybackNetworkRequest
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.PlaybackRequirements
import com.nuvio.tv.playback.core.ProxyMode
import com.nuvio.tv.playback.core.ResourceBudget
import com.nuvio.tv.playback.core.SecretValue
import com.nuvio.tv.playback.core.SessionPriority
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.core.SubtitleFidelity
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.TransientLoadRetryPolicy
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.core.VideoQualityIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3AdapterPlanTest {
    @Test
    fun `raw TS extractor mode requires non URL evidence`() {
        val urlOnly = plan(
            evidence = StreamEvidence(
                delivery = EvidenceFact(DeliveryType.RAW_TRANSPORT_STREAM, EvidenceProvenance.URL_INFERRED),
            ),
        )
        assertFalse(urlOnly.request.confirmedRawTransportStream)
        assertNull(urlOnly.request.mimeType)

        val providerConfirmed = plan(
            evidence = StreamEvidence(
                delivery = EvidenceFact(DeliveryType.RAW_TRANSPORT_STREAM, EvidenceProvenance.PROVIDER_DECLARED),
            ),
        )
        assertTrue(providerConfirmed.request.confirmedRawTransportStream)
        assertEquals(MimeTypes.VIDEO_MP2T, providerConfirmed.request.mimeType)
    }

    @Test
    fun `HLS MPEG TS remains HLS and never activates raw extractor mode`() {
        val result = plan(
            evidence = StreamEvidence(
                delivery = EvidenceFact(DeliveryType.HLS, EvidenceProvenance.MANIFEST_CONFIRMED),
                container = EvidenceFact(
                    com.nuvio.tv.playback.core.ContainerType.MPEG_TS,
                    EvidenceProvenance.SEGMENT_HINT,
                ),
            ),
        )
        assertEquals(MimeTypes.APPLICATION_M3U8, result.request.mimeType)
        assertFalse(result.request.confirmedRawTransportStream)
    }

    @Test
    fun `request parity preserves headers cookies redirect DNS proxy and timeout intent`() {
        val proxy = HttpProxyRequest("proxy.test", 8181, SecretValue("u"), SecretValue("p"))
        val request = PlaybackRequest(
            url = "https://origin.test/live",
            headers = mapOf("Authorization" to "Bearer token", "X-Test" to "yes"),
            cookies = linkedMapOf("sid" to "one", "pref" to "two"),
            userAgent = "provider-agent",
            referer = "https://ref.test/",
            origin = "https://origin.test",
            crossHostAuthorization = CrossHostAuthorization.PRESERVE,
            dnsPolicy = DnsPolicy.SHARED_APPLICATION_RESOLVER,
            network = PlaybackNetworkRequest(
                proxyMode = ProxyMode.HTTP,
                httpProxy = proxy,
                connectTimeoutMs = 4_000,
                readTimeoutMs = 9_000,
                callTimeoutMs = 12_000,
                retryConnectionFailures = false,
                transientLoadRetryPolicy = TransientLoadRetryPolicy.SESSION_ONLY,
            ),
            contentType = ContentType.LIVE,
        )
        val result = Media3AdapterPlanFactory.create(request, StreamEvidence(), graph(), requirements())
        assertEquals("Bearer token", result.request.headers["Authorization"])
        assertEquals("sid=one; pref=two", result.request.headers["Cookie"])
        assertEquals("provider-agent", result.request.headers["User-Agent"])
        assertEquals("https://ref.test/", result.request.headers["Referer"])
        assertEquals("https://origin.test", result.request.headers["Origin"])
        assertTrue(result.request.preserveAuthorizationAcrossHosts)
        assertEquals(DnsPolicy.SHARED_APPLICATION_RESOLVER, result.request.dnsPolicy)
        assertEquals(ProxyMode.HTTP, result.request.proxyMode)
        assertEquals(proxy, result.request.httpProxy)
        assertEquals(4_000, result.request.connectTimeoutMs)
        assertEquals(9_000, result.request.readTimeoutMs)
        assertEquals(12_000, result.request.callTimeoutMs)
        assertFalse(result.request.retryConnectionFailures)
        assertEquals(TransientLoadRetryPolicy.SESSION_ONLY, result.request.transientLoadRetryPolicy)
        val printable = result.toString()
        assertFalse(printable.contains("Bearer token"))
        assertFalse(printable.contains("sid=one"))
        assertFalse(printable.contains("origin.test"))
        assertFalse(printable.contains("SecretValue"))
    }

    @Test
    fun `DRM data source carries only license headers and never stream authorization`() {
        val result = Media3AdapterPlanFactory.create(
            PlaybackRequest(
                url = "https://stream.test/live",
                headers = mapOf("Authorization" to "Bearer stream-secret", "X-Stream" to "stream"),
                drm = DrmRequest(
                    scheme = DrmScheme.WIDEVINE,
                    licenseUrl = "https://license.test/widevine",
                    requestHeaders = mapOf(
                        "Authorization" to "Bearer license-secret",
                        "X-License" to "license",
                    ),
                ),
                contentType = ContentType.LIVE,
            ),
            StreamEvidence(),
            graph(),
            requirements(),
        )

        val drmHeaders = drmDefaultRequestHeaders(result.request)
        assertEquals("Bearer license-secret", drmHeaders["Authorization"])
        assertEquals("license", drmHeaders["X-License"])
        assertFalse(drmHeaders.values.any { it.contains("stream-secret") })
        assertFalse(drmHeaders.containsKey("X-Stream"))
    }

    @Test
    fun `guide constraints and selected graph modes translate without adapter policy`() {
        val requirements = requirements().copy(
            preferredAdaptiveDimensions = VideoDimensions(640, 360),
            adaptiveDimensionCeiling = VideoDimensions(960, 540),
            bitrateCeiling = 2_000_000,
            audioOutput = AudioOutputPreference.PCM,
            audioSkipSilence = true,
            pcmProcessingAllowed = true,
            preferredAudioLanguage = "de",
            preferredSubtitleLanguage = "en",
            subtitlesEnabled = false,
        )
        val graph = graph().copy(decoderMode = DecoderMode.SOFTWARE, audioMode = AudioMode.DECODE)
        val result = plan(requirements = requirements, graph = graph)
        assertEquals(640, result.tracks.viewportWidth)
        assertEquals(360, result.tracks.viewportHeight)
        assertEquals(960, result.tracks.maximumVideoWidth)
        assertEquals(540, result.tracks.maximumVideoHeight)
        assertEquals(2_000_000, result.tracks.maximumVideoBitrate)
        assertEquals("de", result.tracks.preferredAudioLanguage)
        assertEquals("en", result.tracks.preferredSubtitleLanguage)
        assertFalse(result.tracks.subtitlesEnabled)
        assertEquals(DecoderMode.SOFTWARE, result.decoderMode)
        assertEquals(AudioMode.DECODE, result.audioMode)
        assertTrue(result.skipSilence)

        val fullscreen = plan(
            requirements = requirements.copy(
                profile = SessionProfile.FULLSCREEN,
                preferredAdaptiveDimensions = null,
                adaptiveDimensionCeiling = null,
                bitrateCeiling = null,
            ),
            graph = graph,
        )
        assertNull(fullscreen.tracks.viewportWidth)
        assertNull(fullscreen.tracks.maximumVideoWidth)
        assertNull(fullscreen.tracks.maximumVideoBitrate)
    }

    @Test
    fun `extension renderer ordering follows resolved decoder graph and fallback permission`() {
        val strictHardware = plan(
            graph = graph().copy(decoderMode = DecoderMode.HARDWARE),
            requirements = requirements().copy(softwareDecodeFallbackAllowed = false),
        )
        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
            extensionRendererModeFor(strictHardware),
        )

        val fallbackHardware = plan(
            graph = graph().copy(decoderMode = DecoderMode.HARDWARE),
            requirements = requirements().copy(softwareDecodeFallbackAllowed = true),
        )
        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            extensionRendererModeFor(fallbackHardware),
        )

        val software = plan(graph = graph().copy(decoderMode = DecoderMode.SOFTWARE))
        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            extensionRendererModeFor(software),
        )
    }

    @Test
    fun `Media3 errors are normalized by domain without exceptions escaping`() {
        val timeout = Media3FailureMapper.map(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals(FailureCode.NETWORK_TIMEOUT, timeout.code)
        assertEquals(FailureDomain.NETWORK, timeout.domain)

        val decoder = Media3FailureMapper.map(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        assertEquals(FailureCode.VIDEO_DECODER_UNAVAILABLE, decoder.code)
        assertEquals(FailureDomain.VIDEO_DECODER, decoder.domain)

        val drm = Media3FailureMapper.map(PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED)
        assertEquals(FailureCode.DRM_UNSUPPORTED, drm.code)
        assertTrue(drm.deterministic)
    }

    private fun plan(
        evidence: StreamEvidence = StreamEvidence(),
        requirements: PlaybackRequirements = requirements(),
        graph: PlaybackGraph = graph(),
    ) = Media3AdapterPlanFactory.create(
        PlaybackRequest("https://example.test/live", contentType = ContentType.LIVE),
        evidence,
        graph,
        requirements,
    )

    private fun graph() = PlaybackGraph(
        id = "media3",
        engine = EngineType.MEDIA3,
        outputProfile = GraphOutputProfile.MEDIA3_STANDARD,
        decoderMode = DecoderMode.HARDWARE,
        audioMode = AudioMode.DECODE,
        surfaceMode = SurfaceMode.SURFACE_VIEW,
    )

    private fun requirements() = PlaybackRequirements(
        profile = SessionProfile.GUIDE,
        priority = SessionPriority.STARTUP_SPEED,
        qualityIntent = VideoQualityIntent.PREVIEW,
        displayModeSwitchAllowed = false,
        frameRatePreference = FrameRatePreference.OFF,
        hdrPreference = HdrPreference.AUTO,
        decoderPreference = DecoderPreference.AUTO,
        softwareDecodeFallbackAllowed = false,
        subtitleFidelity = SubtitleFidelity.COMPATIBLE,
        subtitlesEnabled = true,
        audioOutput = AudioOutputPreference.AUTO,
        pcmProcessingAllowed = true,
        buffering = BufferingPreference.RECOMMENDED,
        gpuRenderingAllowed = false,
        eligibleEngines = setOf(EngineType.MEDIA3),
        allowedSurfaceModes = setOf(SurfaceMode.SURFACE_VIEW),
        secureOutputRequired = false,
        resourceBudget = ResourceBudget(),
    )
}
