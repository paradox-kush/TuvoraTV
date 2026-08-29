package com.nuvio.tv.playback.media3

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.ApplicationDnsKey
import com.nuvio.tv.playback.core.AudioMode
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.CrossHostAuthorization
import com.nuvio.tv.playback.core.ContainerType
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.DecoderMode
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.DrmScheme
import com.nuvio.tv.playback.core.DeliveryType
import com.nuvio.tv.playback.core.EvidenceProvenance
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackGraph
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.PlaybackRequirements
import com.nuvio.tv.playback.core.ProxyMode
import com.nuvio.tv.playback.core.RedirectPolicy
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.core.TlsPolicy
import com.nuvio.tv.playback.core.TransientLoadRetryPolicy
import java.util.UUID

/**
 * Engine-local, immutable translation of the already-resolved common contract. It contains no
 * policy: every value is a direct Media3 spelling of request/requirements supplied by the session.
 *
 * Raw-TS configuration is admitted only from non-URL stream evidence; DefaultMediaSourceFactory
 * sniffing remains the fallback for ambiguous sources and never becomes invented evidence.
 */
internal data class Media3AdapterPlan(
    val request: Media3NetworkPlan,
    val tracks: Media3TrackPlan,
    val buffer: Media3BufferPlan,
    val decoderMode: DecoderMode,
    val decoderFallback: Boolean,
    val audioMode: AudioMode,
    val audioOutput: AudioOutputPreference,
    val skipSilence: Boolean,
    val downmixToStereo: Boolean,
    val normalization: Boolean,
    val audioDelayMs: Long,
    val subtitleDelayMs: Long,
) {
    /** Never expose provider URLs, request headers, cookies, proxy material, or DRM secrets. */
    override fun toString(): String =
        "Media3AdapterPlan(request=$request, tracks=$tracks, buffer=$buffer, " +
            "decoderMode=$decoderMode, decoderFallback=$decoderFallback, audioMode=$audioMode, " +
            "audioOutput=$audioOutput, skipSilence=$skipSilence, downmixToStereo=$downmixToStereo, " +
            "normalization=$normalization, audioDelayMs=$audioDelayMs, subtitleDelayMs=$subtitleDelayMs)"
}

internal data class Media3NetworkPlan(
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String?,
    val confirmedRawTransportStream: Boolean,
    val followRedirects: Boolean,
    val preserveAuthorizationAcrossHosts: Boolean,
    val dnsPolicy: DnsPolicy,
    val applicationDnsKey: ApplicationDnsKey?,
    val tlsPolicy: TlsPolicy,
    val proxyMode: ProxyMode,
    val httpProxy: com.nuvio.tv.playback.core.HttpProxyRequest?,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val callTimeoutMs: Int?,
    val retryConnectionFailures: Boolean,
    val transientLoadRetryPolicy: TransientLoadRetryPolicy,
    val drm: Media3DrmPlan?,
) {
    override fun toString(): String =
        "Media3NetworkPlan(scheme=${url.substringBefore(':', "unknown")}, " +
            "hasHeaders=${headers.isNotEmpty()}, mimeType=$mimeType, " +
            "confirmedRawTransportStream=$confirmedRawTransportStream, " +
            "followRedirects=$followRedirects, dnsPolicy=$dnsPolicy, tlsPolicy=$tlsPolicy, " +
            "proxyMode=$proxyMode, hasDrm=${drm != null})"
}

internal data class Media3DrmPlan(
    val schemeUuid: UUID,
    val licenseUrl: String,
    val headers: Map<String, String>,
    val multiSession: Boolean,
) {
    override fun toString(): String =
        "Media3DrmPlan(schemeUuid=$schemeUuid, hasLicenseUrl=true, " +
            "hasHeaders=${headers.isNotEmpty()}, multiSession=$multiSession)"
}

internal data class Media3TrackPlan(
    val viewportWidth: Int?,
    val viewportHeight: Int?,
    val maximumVideoWidth: Int?,
    val maximumVideoHeight: Int?,
    val maximumVideoBitrate: Int?,
    val preferredAudioLanguage: String?,
    val preferredSubtitleLanguage: String?,
    val subtitlesEnabled: Boolean,
)

internal data class Media3BufferPlan(
    val minimumMs: Int,
    val maximumMs: Int,
    val playbackMs: Int,
    val rebufferMs: Int,
)

internal object Media3AdapterPlanFactory {
    private const val DEFAULT_USER_AGENT = com.nuvio.tv.playback.core.DEFAULT_STREAM_USER_AGENT

    fun create(
        request: PlaybackRequest,
        evidence: StreamEvidence,
        graph: PlaybackGraph,
        requirements: PlaybackRequirements,
    ): Media3AdapterPlan {
        val headers = linkedMapOf<String, String>()
        request.headers.forEach { (name, value) -> headers.putReplacingCaseInsensitive(name, value) }
        request.userAgent?.let { headers.putReplacingCaseInsensitive("User-Agent", it) }
        if (headers.none { (name, _) -> name.equals("User-Agent", ignoreCase = true) }) {
            headers["User-Agent"] = DEFAULT_USER_AGENT
        }
        request.referer?.let { headers.putReplacingCaseInsensitive("Referer", it) }
        request.origin?.let { headers.putReplacingCaseInsensitive("Origin", it) }
        if (request.cookies.isNotEmpty()) {
            headers.putReplacingCaseInsensitive(
                "Cookie",
                request.cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" },
            )
        }

        val ceiling = requirements.adaptiveDimensionCeiling
        val custom = requirements.customBuffer
        val buffer = if (requirements.buffering == BufferingPreference.CUSTOM && custom != null) {
            Media3BufferPlan(
                minimumMs = custom.minimumBufferMs,
                maximumMs = custom.maximumBufferMs,
                playbackMs = custom.playbackStartBufferMs,
                rebufferMs = custom.rebufferStartBufferMs,
            )
        } else {
            when (requirements.buffering) {
                BufferingPreference.LOW_LATENCY_LIVE -> Media3BufferPlan(2_000, 8_000, 500, 1_000)
                BufferingPreference.BALANCED -> Media3BufferPlan(10_000, 30_000, 1_500, 2_500)
                BufferingPreference.RECOMMENDED,
                BufferingPreference.CUSTOM,
                -> Media3BufferPlan(15_000, 50_000, 2_500, 5_000)
            }
        }

        return Media3AdapterPlan(
            request = Media3NetworkPlan(
                url = request.url,
                headers = headers.toMap(),
                mimeType = sourceMimeType(evidence),
                confirmedRawTransportStream = evidence.delivery?.let { fact ->
                    fact.value == DeliveryType.RAW_TRANSPORT_STREAM &&
                        fact.provenance !in setOf(EvidenceProvenance.URL_INFERRED, EvidenceProvenance.UNKNOWN)
                } == true,
                followRedirects = request.redirectPolicy == RedirectPolicy.FOLLOW,
                preserveAuthorizationAcrossHosts =
                    request.crossHostAuthorization == CrossHostAuthorization.PRESERVE,
                dnsPolicy = request.dnsPolicy,
                applicationDnsKey = request.applicationDnsKey,
                tlsPolicy = request.tlsPolicy,
                proxyMode = request.network.proxyMode,
                httpProxy = request.network.httpProxy,
                connectTimeoutMs = request.network.connectTimeoutMs,
                readTimeoutMs = request.network.readTimeoutMs,
                callTimeoutMs = request.network.callTimeoutMs,
                retryConnectionFailures = request.network.retryConnectionFailures,
                transientLoadRetryPolicy = request.network.transientLoadRetryPolicy,
                drm = request.drm?.let { drm ->
                    Media3DrmPlan(
                        schemeUuid = when (drm.scheme) {
                            DrmScheme.WIDEVINE -> C.WIDEVINE_UUID
                            DrmScheme.PLAYREADY -> C.PLAYREADY_UUID
                            DrmScheme.CLEARKEY -> C.CLEARKEY_UUID
                            DrmScheme.UNKNOWN -> C.UUID_NIL
                        },
                        licenseUrl = drm.licenseUrl,
                        headers = drm.requestHeaders.toMap(),
                        multiSession = drm.multiSession,
                    )
                },
            ),
            tracks = Media3TrackPlan(
                viewportWidth = requirements.preferredAdaptiveDimensions?.width,
                viewportHeight = requirements.preferredAdaptiveDimensions?.height,
                maximumVideoWidth = ceiling?.width,
                maximumVideoHeight = ceiling?.height,
                maximumVideoBitrate = requirements.bitrateCeiling
                    ?.coerceAtMost(Int.MAX_VALUE.toLong())
                    ?.toInt(),
                preferredAudioLanguage = requirements.preferredAudioLanguage,
                preferredSubtitleLanguage = requirements.preferredSubtitleLanguage,
                subtitlesEnabled = requirements.subtitlesEnabled,
            ),
            buffer = buffer,
            decoderMode = graph.decoderMode,
            decoderFallback = requirements.decoderPreference == DecoderPreference.AUTO &&
                requirements.softwareDecodeFallbackAllowed,
            audioMode = graph.audioMode,
            audioOutput = requirements.audioOutput,
            skipSilence = requirements.audioSkipSilence && requirements.pcmProcessingAllowed,
            downmixToStereo = requirements.audioDownmixToStereo && requirements.pcmProcessingAllowed,
            normalization = requirements.audioNormalization && requirements.pcmProcessingAllowed,
            audioDelayMs = requirements.audioDelayMs,
            subtitleDelayMs = requirements.subtitleDelayMs,
        )
    }

    private fun sourceMimeType(evidence: StreamEvidence): String? {
        val delivery = evidence.delivery
        when (delivery?.value) {
            DeliveryType.HLS -> return MimeTypes.APPLICATION_M3U8
            DeliveryType.DASH -> return MimeTypes.APPLICATION_MPD
            DeliveryType.RAW_TRANSPORT_STREAM -> if (
                delivery.provenance !in setOf(EvidenceProvenance.URL_INFERRED, EvidenceProvenance.UNKNOWN)
            ) return MimeTypes.VIDEO_MP2T
            else -> Unit
        }
        return when (evidence.container?.value) {
            ContainerType.MP4, ContainerType.FMP4 -> MimeTypes.VIDEO_MP4
            ContainerType.MATROSKA -> MimeTypes.VIDEO_MATROSKA
            ContainerType.WEBM -> MimeTypes.VIDEO_WEBM
            // MPEG-TS is set only by confirmed raw delivery above; HLS segments must not turn the
            // parent source into a progressive raw-TS source.
            else -> null
        }
    }

    private fun MutableMap<String, String>.putReplacingCaseInsensitive(name: String, value: String) {
        keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(::remove)
        put(name, value)
    }
}

/** Stable normalization of Media3's public error codes. Raw exceptions never leave this package. */
internal object Media3FailureMapper {
    fun map(error: PlaybackException, phase: FailurePhase = FailurePhase.PLAYBACK): PlaybackFailure {
        error.causeChain().filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
            ?.let { response ->
                return when (response.responseCode) {
                    401, 403 -> failure(
                        FailureCode.AUTHORIZATION_REJECTED,
                        FailureDomain.AUTHORIZATION_PROVIDER_LIMIT,
                        phase,
                        Retryability.FATAL,
                        httpStatus = response.responseCode,
                        statusProvenance = com.nuvio.tv.playback.core.HttpStatusProvenance.CONFIRMED,
                    )
                    429 -> failure(
                        FailureCode.PROVIDER_CONNECTION_LIMIT,
                        FailureDomain.AUTHORIZATION_PROVIDER_LIMIT,
                        phase,
                        Retryability.FATAL,
                    )
                    in 500..599 -> failure(
                        FailureCode.NETWORK_UNREACHABLE,
                        FailureDomain.NETWORK,
                        phase,
                        Retryability.RETRYABLE_IN_PLACE,
                    )
                    else -> failure(
                        FailureCode.NETWORK_UNREACHABLE,
                        FailureDomain.NETWORK,
                        phase,
                        Retryability.RETRYABLE_WITH_FRESH_REQUEST,
                    )
                }
            }
        if (error.causeChain().any { it is javax.net.ssl.SSLException }) {
            return failure(
                FailureCode.TLS_HANDSHAKE_FAILED,
                FailureDomain.TLS,
                phase,
                Retryability.FATAL,
                deterministic = true,
            )
        }
        val exo = error as? ExoPlaybackException
        val rendererMime = exo?.rendererFormat?.sampleMimeType
        if (rendererMime != null && MimeTypes.isAudio(rendererMime)) {
            return when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
                -> failure(
                    FailureCode.AUDIO_DECODER_FAILED,
                    FailureDomain.AUDIO_DECODER,
                    phase,
                    Retryability.HANDOFF_ELIGIBLE,
                )
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
                -> failure(
                    FailureCode.AUDIO_SINK_FAILED,
                    FailureDomain.AUDIO_SINK,
                    phase,
                    Retryability.HANDOFF_ELIGIBLE,
                )
                else -> failure(FailureCode.UNKNOWN, FailureDomain.UNKNOWN, phase, Retryability.HANDOFF_ELIGIBLE)
            }
        }
        return map(error.errorCode, phase)
    }

    fun map(errorCode: Int, phase: FailurePhase = FailurePhase.PLAYBACK): PlaybackFailure = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
        -> failure(FailureCode.NETWORK_TIMEOUT, FailureDomain.NETWORK, phase, Retryability.RETRYABLE_IN_PLACE)

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        -> failure(FailureCode.NETWORK_UNREACHABLE, FailureDomain.NETWORK, phase, Retryability.RETRYABLE_IN_PLACE)

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> failure(
            FailureCode.NETWORK_UNREACHABLE,
            FailureDomain.NETWORK,
            phase,
            Retryability.RETRYABLE_WITH_FRESH_REQUEST,
        )

        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> failure(FailureCode.MANIFEST_INVALID, FailureDomain.MANIFEST, phase, Retryability.HANDOFF_ELIGIBLE, true)

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        -> failure(FailureCode.DEMUX_FAILED, FailureDomain.DEMUX, phase, Retryability.HANDOFF_ELIGIBLE, true)

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> failure(FailureCode.VIDEO_DECODER_UNAVAILABLE, FailureDomain.VIDEO_DECODER, phase, Retryability.HANDOFF_ELIGIBLE, true)

        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        -> failure(FailureCode.VIDEO_DECODER_FAILED, FailureDomain.VIDEO_DECODER, phase, Retryability.HANDOFF_ELIGIBLE)

        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        -> failure(FailureCode.AUDIO_SINK_FAILED, FailureDomain.AUDIO_SINK, phase, Retryability.HANDOFF_ELIGIBLE)

        in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> failure(
            if (errorCode == PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED) {
                FailureCode.DRM_LICENSE_FAILED
            } else {
                FailureCode.DRM_UNSUPPORTED
            },
            FailureDomain.DRM,
            phase,
            Retryability.FATAL,
            deterministic = errorCode == PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        )

        else -> failure(FailureCode.UNKNOWN, FailureDomain.UNKNOWN, phase, Retryability.HANDOFF_ELIGIBLE)
    }

    private fun failure(
        code: FailureCode,
        domain: FailureDomain,
        phase: FailurePhase,
        retryability: Retryability,
        deterministic: Boolean = false,
        httpStatus: Int? = null,
        statusProvenance: com.nuvio.tv.playback.core.HttpStatusProvenance? = null,
    ) = PlaybackFailure(code, domain, phase, retryability, deterministic, httpStatus, statusProvenance)

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }
}
