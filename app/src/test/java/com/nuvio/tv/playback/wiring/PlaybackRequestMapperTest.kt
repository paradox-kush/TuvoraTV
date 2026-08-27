package com.nuvio.tv.playback.wiring

import com.nuvio.tv.playback.core.ContainerType
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.CrossHostAuthorization
import com.nuvio.tv.playback.core.DeliveryType
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.DrmRequest
import com.nuvio.tv.playback.core.DrmScheme
import com.nuvio.tv.playback.core.EvidenceFact
import com.nuvio.tv.playback.core.EvidenceProvenance
import com.nuvio.tv.playback.core.RedirectPolicy
import com.nuvio.tv.playback.core.SecretValue
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.core.TlsPolicy
import com.nuvio.tv.playback.core.VideoCodec
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestMapperTest {
    private val mapper = PlaybackRequestMapper()

    @Test
    fun `maps navigation intent strips URL user info and preserves request policy`() {
        val drm = DrmRequest(
            scheme = DrmScheme.WIDEVINE,
            licenseUrl = "https://drm-user:drm-pass@license.invalid/wv?secret=license-token",
            requestHeaders = mapOf(
                "Authorization" to "drm-secret",
                "X-Injected" to "bad\r\nHeader: value",
            ),
        )
        val mapped = mapper.map(
            NavigationPlaybackInput(
                url = "https://alice:password@provider.invalid/live/channel.ts?token=url-secret",
                headers = mapOf(
                    "User-Agent" to "provider-agent",
                    "Referer" to "https://private-referrer.invalid/path",
                    "Origin" to "https://private-origin.invalid",
                    "Cookie" to "session=cookie-secret; theme=dark",
                    "X-Playback" to "allowed",
                    "Connection" to "keep-alive",
                    "X-Injected" to "bad\r\nHeader: value",
                ),
                cookies = mapOf("account" to "account-cookie", "theme" to "explicit-theme"),
                redirectPolicy = RedirectPolicy.REJECT,
                crossHostAuthorization = CrossHostAuthorization.PRESERVE,
                tlsPolicy = TlsPolicy.STRICT,
                dnsPolicy = DnsPolicy.SHARED_APPLICATION_RESOLVER,
                drm = drm,
                contentType = ContentType.LIVE,
                contentKey = SecretValue("playlist-account-key"),
                providerConnectionLimit = 1,
            ),
        )

        val request = mapped.request
        assertEquals("https://provider.invalid/live/channel.ts?token=url-secret", request.url)
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString(
                "alice:password".toByteArray(StandardCharsets.UTF_8),
            ),
            request.headers["Authorization"],
        )
        assertEquals("allowed", request.headers["X-Playback"])
        assertFalse(request.headers.keys.any { it.equals("Connection", ignoreCase = true) })
        assertFalse(request.headers.keys.any { it.equals("X-Injected", ignoreCase = true) })
        assertEquals("provider-agent", request.userAgent)
        assertEquals("https://private-referrer.invalid/path", request.referer)
        assertEquals("https://private-origin.invalid", request.origin)
        assertEquals("cookie-secret", request.cookies["session"])
        assertEquals("account-cookie", request.cookies["account"])
        assertEquals("explicit-theme", request.cookies["theme"])
        assertEquals(RedirectPolicy.REJECT, request.redirectPolicy)
        assertEquals(CrossHostAuthorization.PRESERVE, request.crossHostAuthorization)
        assertEquals(TlsPolicy.STRICT, request.tlsPolicy)
        assertEquals(DnsPolicy.SHARED_APPLICATION_RESOLVER, request.dnsPolicy)
        assertEquals(1, request.providerConnectionLimit)
        assertEquals("https://license.invalid/wv?secret=license-token", request.drm?.licenseUrl)
        assertEquals("drm-secret", request.drm?.requestHeaders?.get("Authorization"))
        assertFalse(request.drm?.requestHeaders?.keys.orEmpty().contains("X-Injected"))
        assertEquals(DrmScheme.WIDEVINE, mapped.evidence.drmScheme?.value)
    }

    @Test
    fun `explicit authorization wins while URL credentials are always removed`() {
        val mapped = mapper.map(
            NavigationPlaybackInput(
                url = "http://url-user:url-pass@provider.invalid/live",
                headers = mapOf("authorization" to "Bearer explicit-secret"),
                contentType = ContentType.LIVE,
            ),
        )

        assertEquals("http://provider.invalid/live", mapped.request.url)
        assertEquals("Bearer explicit-secret", mapped.request.headers["authorization"])
        assertEquals(1, mapped.request.headers.keys.count { it.equals("authorization", ignoreCase = true) })
    }

    @Test
    fun `passive evidence keeps stronger facts and independently records MIME and URL hints`() {
        val confirmedContainer = EvidenceFact(ContainerType.FMP4, EvidenceProvenance.MANIFEST_CONFIRMED)
        val mapped = mapper.map(
            NavigationPlaybackInput(
                url = "https://provider.invalid/live/channel.m3u8?token=secret",
                contentType = ContentType.LIVE,
                providerDeclaredDelivery = DeliveryType.DASH,
                httpMimeType = "video/mp2t; charset=binary",
                existingEvidence = StreamEvidence(container = confirmedContainer),
            ),
        )

        assertEquals(DeliveryType.DASH, mapped.evidence.delivery?.value)
        assertEquals(EvidenceProvenance.PROVIDER_DECLARED, mapped.evidence.delivery?.provenance)
        assertEquals(confirmedContainer, mapped.evidence.container)
        assertNull(mapped.evidence.videoCodec)
    }

    @Test
    fun `stronger provider facts replace stale URL evidence and live schemes are inferred passively`() {
        val providerWins = mapper.map(
            NavigationPlaybackInput(
                url = "https://provider.invalid/live/channel.m3u8",
                contentType = ContentType.LIVE,
                providerDeclaredDelivery = DeliveryType.DASH,
                providerDeclaredVideoCodec = VideoCodec.HEVC,
                existingEvidence = StreamEvidence(
                    delivery = EvidenceFact(DeliveryType.HLS, EvidenceProvenance.URL_INFERRED),
                    videoCodec = EvidenceFact(VideoCodec.AVC, EvidenceProvenance.UNKNOWN),
                ),
            ),
        )

        assertEquals(DeliveryType.DASH, providerWins.evidence.delivery?.value)
        assertEquals(VideoCodec.HEVC, providerWins.evidence.videoCodec?.value)
        listOf(
            "rtsp://provider.invalid/live" to DeliveryType.RTSP,
            "rtp://239.1.1.1:5000" to DeliveryType.RTP,
            "udp://@239.1.1.1:5000" to DeliveryType.UDP,
        ).forEach { (url, expected) ->
            val mapped = mapper.map(NavigationPlaybackInput(url = url, contentType = ContentType.LIVE))
            assertEquals(expected, mapped.evidence.delivery?.value)
            assertEquals(EvidenceProvenance.URL_INFERRED, mapped.evidence.delivery?.provenance)
        }
    }

    @Test
    fun `all forbidden transport control bytes and DEL are rejected`() {
        listOf('\u0000', '\u0001', '\u0008', '\u000b', '\u000c', '\u001f', '\u007f').forEach { control ->
            val mapped = mapper.map(
                NavigationPlaybackInput(
                    url = "https://provider.invalid/live",
                    headers = mapOf("X-Unsafe" to "before${control}after"),
                    userAgent = "agent${control}suffix",
                    contentType = ContentType.LIVE,
                ),
            )
            assertFalse(mapped.request.headers.containsKey("X-Unsafe"))
            assertNull(mapped.request.userAgent)
        }
    }

    @Test
    fun `all request-bearing string forms remain secret safe`() {
        val secrets = listOf(
            "provider.invalid", "url-secret", "header-secret", "cookie-secret", "playlist-key",
        )
        val input = NavigationPlaybackInput(
            url = "https://provider.invalid/live?token=url-secret",
            headers = mapOf("Authorization" to "header-secret"),
            cookies = mapOf("session" to "cookie-secret"),
            contentType = ContentType.CATCH_UP,
            contentKey = SecretValue("playlist-key"),
        )
        val mapped = mapper.map(input)
        val printable = listOf(input, mapped, mapped.request, mapped.request.summary()).joinToString(" ")

        secrets.forEach { assertFalse("Leaked $it", printable.contains(it)) }
        assertTrue(printable.contains("contentType=CATCH_UP"))
    }
}
