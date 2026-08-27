package com.nuvio.tv.playback.mpv

import com.nuvio.tv.playback.core.AudioMode
import com.nuvio.tv.playback.core.CrossHostAuthorization
import com.nuvio.tv.playback.core.DecoderMode
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.GraphOutputProfile
import com.nuvio.tv.playback.core.PlaybackEngineStart
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackNetworkRequest
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.ProxyMode
import com.nuvio.tv.playback.core.RedirectPolicy
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.TransientLoadRetryPolicy

/** Immutable, policy-free spelling of a resolved core graph for libmpv. */
internal data class MpvAdapterPlan(
    val url: String,
    val headers: Map<String, String>,
    val preInitOptions: Map<String, String>,
    val runtimeProperties: Map<String, String>,
    val surfaceMode: SurfaceMode,
    val startPaused: Boolean,
) {
    override fun toString(): String =
        "MpvAdapterPlan(scheme=${url.substringBefore(':', "unknown")}, " +
            "hasHeaders=${headers.isNotEmpty()}, optionNames=${preInitOptions.keys.sorted()}, " +
            "propertyNames=${runtimeProperties.keys.sorted()}, surfaceMode=$surfaceMode, " +
            "startPaused=$startPaused)"
}

internal object MpvAdapterPlanFactory {
    private const val DEFAULT_USER_AGENT = "TuvoraTV/1 libmpv"

    fun create(input: PlaybackEngineStart): PlaybackResult<MpvAdapterPlan> {
        val request = input.request
        val graph = input.graph
        val requirements = input.requirements

        if (!graph.isStructurallyValid() || graph.engine != EngineType.LIBMPV) {
            return unsupported(FailureCode.NO_ELIGIBLE_GRAPH)
        }
        if (request.drm != null) return unsupported(FailureCode.DRM_UNSUPPORTED)
        if (graph.secureOutput || requirements.secureOutputRequired) {
            return unsupported(FailureCode.NO_ELIGIBLE_GRAPH)
        }
        if (request.redirectPolicy == RedirectPolicy.REJECT ||
            request.dnsPolicy == DnsPolicy.SHARED_APPLICATION_RESOLVER ||
            request.network.callTimeoutMs != null
        ) {
            return unsupported(FailureCode.NO_ELIGIBLE_GRAPH)
        }
        if (request.network.connectTimeoutMs != PlaybackNetworkRequest.DEFAULT_CONNECT_TIMEOUT_MS ||
            request.network.readTimeoutMs != PlaybackNetworkRequest.DEFAULT_READ_TIMEOUT_MS
        ) {
            return unsupported(FailureCode.NO_ELIGIBLE_GRAPH)
        }
        val hasAuthorization = request.summary().hasAuthorization
        if (hasAuthorization && request.crossHostAuthorization == CrossHostAuthorization.STRIP) {
            return unsupported(FailureCode.NO_ELIGIBLE_GRAPH)
        }
        if (graph.audioMode == AudioMode.OFFLOAD || requirements.audioSkipSilence) {
            return unsupported(FailureCode.NO_ELIGIBLE_GRAPH)
        }
        if (graph.outputProfile == GraphOutputProfile.MPV_DIRECT &&
            (graph.decoderMode == DecoderMode.SOFTWARE || requirements.subtitlesEnabled)
        ) {
            return unsupported(FailureCode.NO_ELIGIBLE_GRAPH)
        }

        val headers = linkedMapOf<String, String>()
        request.headers.forEach { (name, value) -> headers.putReplacingCaseInsensitive(name, value) }
        request.referer?.let { headers.putReplacingCaseInsensitive("Referer", it) }
        request.origin?.let { headers.putReplacingCaseInsensitive("Origin", it) }
        if (request.cookies.isNotEmpty()) {
            headers.putReplacingCaseInsensitive(
                "Cookie",
                request.cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" },
            )
        }

        val options = linkedMapOf(
            "config" to "no",
            "terminal" to "no",
            // Raw mpv/FFmpeg messages may contain provider URLs; normalized facts are the only
            // clean-adapter diagnostic channel.
            "msg-level" to "all=no",
            "network-timeout" to "60",
            "user-agent" to (request.userAgent ?: DEFAULT_USER_AGENT),
            "tls-verify" to "yes",
            "cache" to "yes",
            "demuxer-readahead-secs" to bufferMaximumMs(input).div(1_000.0).toString(),
            "ao" to "audiotrack,aaudio",
        )
        when (request.network.proxyMode) {
            ProxyMode.SYSTEM -> Unit
            ProxyMode.DIRECT -> options["http-proxy"] = ""
            ProxyMode.HTTP -> {
                val proxy = requireNotNull(request.network.httpProxy)
                val credentials = proxy.username?.let {
                    "${it.value}:${requireNotNull(proxy.password).value}@"
                }.orEmpty()
                options["http-proxy"] = "http://$credentials${proxy.host}:${proxy.port}"
            }
        }
        if (!request.network.retryConnectionFailures ||
            request.network.transientLoadRetryPolicy == TransientLoadRetryPolicy.SESSION_ONLY
        ) {
            options["stream-lavf-o"] = "reconnect=0,reconnect_streamed=0"
        }
        if (headers.isNotEmpty()) {
            options["http-header-fields"] = headers.entries.joinToString(",") {
                "${it.key}: ${it.value}"
            }
        }

        when (graph.outputProfile) {
            GraphOutputProfile.MPV_DIRECT -> {
                options["vo"] = "mediacodec_embed"
                options["hwdec"] = "mediacodec"
            }
            GraphOutputProfile.MPV_RENDER -> {
                options["vo"] = "gpu"
                options["gpu-context"] = "android"
                options["hwdec"] = when (graph.decoderMode) {
                    DecoderMode.HARDWARE -> "mediacodec,mediacodec-copy"
                    DecoderMode.SOFTWARE -> "no"
                }
            }
            GraphOutputProfile.MEDIA3_STANDARD -> return unsupported(FailureCode.NO_ELIGIBLE_GRAPH)
        }

        when (graph.audioMode) {
            AudioMode.PASSTHROUGH -> options["audio-spdif"] = "ac3,eac3,dts-hd,truehd"
            AudioMode.DECODE -> options["audio-spdif"] = ""
            AudioMode.OFFLOAD -> return unsupported(FailureCode.NO_ELIGIBLE_GRAPH)
        }
        if (requirements.audioDownmixToStereo) options["audio-channels"] = "stereo"
        if (requirements.audioNormalization) options["af"] = "lavfi=[dynaudnorm]"
        requirements.preferredAudioLanguage?.let { options["alang"] = it }
        requirements.preferredSubtitleLanguage?.let { options["slang"] = it }
        options["sid"] = if (requirements.subtitlesEnabled) "auto" else "no"

        val properties = linkedMapOf(
            "audio-delay" to (requirements.audioDelayMs / 1_000.0).toString(),
            "sub-delay" to (requirements.subtitleDelayMs / 1_000.0).toString(),
        )
        return PlaybackResult.Success(
            MpvAdapterPlan(
                url = request.url,
                headers = headers.toMap(),
                preInitOptions = options.toMap(),
                runtimeProperties = properties.toMap(),
                surfaceMode = graph.surfaceMode,
                startPaused = input.startPaused,
            ),
        )
    }

    private fun bufferMaximumMs(input: PlaybackEngineStart): Int {
        val requirements = input.requirements
        return requirements.customBuffer?.maximumBufferMs ?: when (requirements.buffering) {
            com.nuvio.tv.playback.core.BufferingPreference.LOW_LATENCY_LIVE -> 8_000
            com.nuvio.tv.playback.core.BufferingPreference.BALANCED -> 30_000
            com.nuvio.tv.playback.core.BufferingPreference.RECOMMENDED,
            com.nuvio.tv.playback.core.BufferingPreference.CUSTOM,
            -> 50_000
        }
    }

    private fun MutableMap<String, String>.putReplacingCaseInsensitive(name: String, value: String) {
        keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(::remove)
        put(name, value)
    }

    private fun unsupported(code: FailureCode): PlaybackResult.Failure = PlaybackResult.Failure(
        PlaybackFailure(
            code = code,
            domain = if (code == FailureCode.DRM_UNSUPPORTED) {
                FailureDomain.DRM
            } else {
                FailureDomain.DEVICE_RESOURCE
            },
            phase = FailurePhase.ENGINE_START,
            retryability = Retryability.HANDOFF_ELIGIBLE,
            deterministic = true,
        ),
    )
}
