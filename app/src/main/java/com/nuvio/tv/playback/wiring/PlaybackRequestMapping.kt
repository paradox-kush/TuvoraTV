package com.nuvio.tv.playback.wiring

import com.nuvio.tv.playback.core.AudioCodec
import com.nuvio.tv.playback.core.ContainerType
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.CrossHostAuthorization
import com.nuvio.tv.playback.core.DeliveryType
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.DrmRequest
import com.nuvio.tv.playback.core.EvidenceFact
import com.nuvio.tv.playback.core.EvidenceProvenance
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.RedirectPolicy
import com.nuvio.tv.playback.core.SecretValue
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.core.SubtitleFormat
import com.nuvio.tv.playback.core.TlsPolicy
import com.nuvio.tv.playback.core.VideoCodec
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Engine-neutral input assembled by the clean navigation/provider boundary. It deliberately does not
 * import the frozen legacy player route; cutover wiring translates route values into this contract.
 */
class NavigationPlaybackInput(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val cookies: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val referer: String? = null,
    val origin: String? = null,
    val redirectPolicy: RedirectPolicy = RedirectPolicy.FOLLOW,
    val crossHostAuthorization: CrossHostAuthorization = CrossHostAuthorization.STRIP,
    val tlsPolicy: TlsPolicy = TlsPolicy.PLATFORM_DEFAULT,
    val dnsPolicy: DnsPolicy = DnsPolicy.SYSTEM,
    val drm: DrmRequest? = null,
    val contentType: ContentType,
    val contentKey: SecretValue? = null,
    val providerConnectionLimit: Int? = null,
    val providerDeclaredDelivery: DeliveryType? = null,
    val providerDeclaredContainer: ContainerType? = null,
    val providerDeclaredVideoCodec: VideoCodec? = null,
    val providerDeclaredAudioCodec: AudioCodec? = null,
    val providerDeclaredSubtitleFormat: SubtitleFormat? = null,
    val httpMimeType: String? = null,
    val existingEvidence: StreamEvidence = StreamEvidence(),
) {
    override fun toString(): String =
        "NavigationPlaybackInput(contentType=$contentType, hasHeaders=${headers.isNotEmpty()}, " +
            "hasCookies=${cookies.isNotEmpty()}, hasDrm=${drm != null})"
}

data class MappedPlaybackRequest(
    val request: PlaybackRequest,
    val evidence: StreamEvidence,
)

/** Pure, passive mapping. It never performs DNS, HTTP, manifest, or media probing. */
class PlaybackRequestMapper {
    fun map(input: NavigationPlaybackInput): MappedPlaybackRequest {
        val headers = sanitizeHeaders(input.headers)
        val urlAuth = extractUrlAuthentication(input.url)
        val explicitAuthorization = headers.valueCaseInsensitive("Authorization")
        val headerUserAgent = headers.removeCaseInsensitive("User-Agent")
        val headerReferer = headers.removeCaseInsensitive("Referer")
            ?: headers.removeCaseInsensitive("Referrer")
        val headerOrigin = headers.removeCaseInsensitive("Origin")
        val userAgent = sanitizeScalar(input.userAgent) ?: sanitizeScalar(headerUserAgent)
        val referer = sanitizeScalar(input.referer) ?: sanitizeScalar(headerReferer)
        val origin = sanitizeScalar(input.origin) ?: sanitizeScalar(headerOrigin)
        val cookieHeader = headers.removeCaseInsensitive("Cookie")
        val cookies = parseCookieHeader(cookieHeader) + sanitizeCookies(input.cookies)
        if (explicitAuthorization == null && urlAuth.authorization != null) {
            headers["Authorization"] = urlAuth.authorization
        }

        val request = PlaybackRequest(
            url = urlAuth.url,
            headers = headers.toMap(),
            cookies = cookies,
            userAgent = userAgent,
            referer = referer,
            origin = origin,
            redirectPolicy = input.redirectPolicy,
            crossHostAuthorization = input.crossHostAuthorization,
            tlsPolicy = input.tlsPolicy,
            dnsPolicy = input.dnsPolicy,
            drm = input.drm?.sanitized(),
            contentType = input.contentType,
            contentKey = input.contentKey,
            providerConnectionLimit = input.providerConnectionLimit,
        )
        return MappedPlaybackRequest(request, passiveEvidence(input, urlAuth.url))
    }

    private fun passiveEvidence(input: NavigationPlaybackInput, sanitizedUrl: String): StreamEvidence {
        val mime = input.httpMimeType?.trim()?.lowercase()
        val inferred = inferFromUrl(sanitizedUrl)
        return input.existingEvidence.copy(
            delivery = strongest(
                input.existingEvidence.delivery,
                input.providerDeclaredDelivery?.providerFact(),
                deliveryFromMime(mime)?.httpFact(),
                inferred.delivery?.urlFact(),
            ),
            container = strongest(
                input.existingEvidence.container,
                input.providerDeclaredContainer?.providerFact(),
                containerFromMime(mime)?.httpFact(),
                inferred.container?.urlFact(),
            ),
            videoCodec = strongest(
                input.existingEvidence.videoCodec,
                input.providerDeclaredVideoCodec?.providerFact(),
            ),
            audioCodec = strongest(
                input.existingEvidence.audioCodec,
                input.providerDeclaredAudioCodec?.providerFact(),
            ),
            subtitleFormat = strongest(
                input.existingEvidence.subtitleFormat,
                input.providerDeclaredSubtitleFormat?.providerFact(),
            ),
            drmScheme = strongest(
                input.existingEvidence.drmScheme,
                input.drm?.scheme?.providerFact(),
            ),
        )
    }

    private fun sanitizeHeaders(source: Map<String, String>): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        source.forEach { (rawName, rawValue) ->
            val name = rawName.trim()
            val value = rawValue.trim()
            if (!name.isValidHeaderName() || !value.isSafeTransportValue()) return@forEach
            if (name.lowercase() in HOP_BY_HOP_HEADERS) return@forEach
            result.removeCaseInsensitive(name)
            result[name] = value
        }
        return result
    }

    private fun sanitizeCookies(source: Map<String, String>): Map<String, String> = buildMap {
        source.forEach { (rawName, rawValue) ->
            val name = rawName.trim()
            val value = rawValue.trim()
            if (COOKIE_NAME.matches(name) && value.isSafeTransportValue() && ';' !in value) {
                put(name, value)
            }
        }
    }

    private fun parseCookieHeader(value: String?): Map<String, String> = buildMap {
        value?.split(';')?.forEach { part ->
            val name = part.substringBefore('=', missingDelimiterValue = "").trim()
            val cookieValue = part.substringAfter('=', missingDelimiterValue = "").trim()
            if (COOKIE_NAME.matches(name) && cookieValue.isSafeTransportValue()) put(name, cookieValue)
        }
    }

    private fun DrmRequest.sanitized(): DrmRequest {
        val headers = sanitizeHeaders(requestHeaders)
        val urlAuth = extractUrlAuthentication(licenseUrl)
        if (headers.valueCaseInsensitive("Authorization") == null && urlAuth.authorization != null) {
            headers["Authorization"] = urlAuth.authorization
        }
        return DrmRequest(
            scheme = scheme,
            licenseUrl = urlAuth.url,
            requestHeaders = headers,
            multiSession = multiSession,
        )
    }

    private fun extractUrlAuthentication(rawUrl: String): UrlAuthentication {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return UrlAuthentication(rawUrl, null)
        val userInfo = uri.userInfo?.takeIf(String::isNotBlank) ?: return UrlAuthentication(rawUrl, null)
        val host = uri.rawAuthority?.substringAfterLast('@') ?: return UrlAuthentication(rawUrl, null)
        val clean = buildString {
            uri.scheme?.let { append(it).append("://") }
            append(host)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
        val token = Base64.getEncoder().encodeToString(userInfo.toByteArray(StandardCharsets.UTF_8))
        return UrlAuthentication(clean, "Basic $token")
    }

    private fun inferFromUrl(url: String): UrlEvidence {
        val uri = runCatching { URI(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val path = uri?.path.orEmpty().lowercase()
        return when {
            scheme == "rtsp" -> UrlEvidence(DeliveryType.RTSP, null)
            scheme == "rtp" -> UrlEvidence(DeliveryType.RTP, null)
            scheme == "udp" -> UrlEvidence(DeliveryType.UDP, null)
            path.endsWith(".m3u8") -> UrlEvidence(DeliveryType.HLS, null)
            path.endsWith(".mpd") -> UrlEvidence(DeliveryType.DASH, null)
            path.endsWith(".ts") || path.endsWith(".m2ts") ->
                UrlEvidence(DeliveryType.RAW_TRANSPORT_STREAM, ContainerType.MPEG_TS)
            path.endsWith(".mp4") -> UrlEvidence(DeliveryType.PROGRESSIVE, ContainerType.MP4)
            path.endsWith(".mkv") -> UrlEvidence(DeliveryType.PROGRESSIVE, ContainerType.MATROSKA)
            path.endsWith(".webm") -> UrlEvidence(DeliveryType.PROGRESSIVE, ContainerType.WEBM)
            else -> UrlEvidence(null, null)
        }
    }

    private fun deliveryFromMime(mime: String?): DeliveryType? = when (mime?.substringBefore(';')) {
        "application/vnd.apple.mpegurl", "application/x-mpegurl" -> DeliveryType.HLS
        "application/dash+xml" -> DeliveryType.DASH
        "video/mp2t" -> DeliveryType.RAW_TRANSPORT_STREAM
        "video/mp4", "video/x-matroska", "video/webm" -> DeliveryType.PROGRESSIVE
        else -> null
    }

    private fun containerFromMime(mime: String?): ContainerType? = when (mime?.substringBefore(';')) {
        "video/mp2t" -> ContainerType.MPEG_TS
        "video/mp4" -> ContainerType.MP4
        "video/x-matroska" -> ContainerType.MATROSKA
        "video/webm" -> ContainerType.WEBM
        else -> null
    }

    private fun sanitizeScalar(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && it.isSafeTransportValue() }

    private fun String.isSafeTransportValue(): Boolean = none { character ->
        val code = character.code
        (code in 0x00..0x1f && character != '\t') || code == 0x7f
    }
    private fun String.isValidHeaderName(): Boolean = HEADER_NAME.matches(this)

    private fun <T> T.providerFact() = EvidenceFact(this, EvidenceProvenance.PROVIDER_DECLARED)
    private fun <T> T.httpFact() = EvidenceFact(this, EvidenceProvenance.HTTP_MIME_HINT)
    private fun <T> T.urlFact() = EvidenceFact(this, EvidenceProvenance.URL_INFERRED)

    private fun <T> strongest(vararg facts: EvidenceFact<T>?): EvidenceFact<T>? = facts
        .filterNotNull()
        .maxByOrNull { it.provenance.strength }

    private val EvidenceProvenance.strength: Int
        get() = when (this) {
            EvidenceProvenance.EXTRACTOR_CONFIRMED -> 80
            EvidenceProvenance.MANIFEST_CONFIRMED -> 70
            EvidenceProvenance.HLS_CODECS_ATTRIBUTE -> 65
            EvidenceProvenance.SEGMENT_HINT -> 60
            EvidenceProvenance.PROVIDER_DECLARED -> 50
            EvidenceProvenance.HTTP_MIME_HINT -> 40
            EvidenceProvenance.URL_INFERRED -> 20
            EvidenceProvenance.UNKNOWN -> 0
        }

    private fun MutableMap<String, String>.removeCaseInsensitive(name: String): String? {
        val key = keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
        return remove(key)
    }

    private fun Map<String, String>.valueCaseInsensitive(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private data class UrlAuthentication(val url: String, val authorization: String?)
    private data class UrlEvidence(val delivery: DeliveryType?, val container: ContainerType?)

    private companion object {
        val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val COOKIE_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val HOP_BY_HOP_HEADERS = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te",
            "trailer", "transfer-encoding", "upgrade",
        )
    }
}
