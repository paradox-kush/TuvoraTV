package com.nuvio.tv.playback.mpv

import com.nuvio.tv.playback.core.assembledHttpHeaders
import com.nuvio.tv.playback.core.AudioMode
import com.nuvio.tv.playback.core.ContentType
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
import com.nuvio.tv.playback.core.SubtitleFidelity
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.TransientLoadRetryPolicy
import com.nuvio.tv.playback.core.VodRestorationCheckpoint

/** Immutable, policy-free spelling of a resolved core graph for libmpv. */
internal data class MpvAdapterPlan(
    val url: String,
    val headers: Map<String, String>,
    val preInitOptions: Map<String, String>,
    val runtimeProperties: Map<String, String>,
    val surfaceMode: SurfaceMode,
    val startPaused: Boolean,
    val dnsMode: MpvDnsMode,
    val startPositionMs: Long = 0,
    val playbackRate: Float = 1f,
    val restorationCheckpoint: VodRestorationCheckpoint? = null,
) {
    override fun toString(): String =
        "MpvAdapterPlan(scheme=${url.substringBefore(':', "unknown")}, " +
            "hasHeaders=${headers.isNotEmpty()}, optionNames=${preInitOptions.keys.sorted()}, " +
            "propertyNames=${runtimeProperties.keys.sorted()}, surfaceMode=$surfaceMode, " +
            "startPaused=$startPaused, dnsMode=$dnsMode)"
}

/**
 * libmpv/FFmpeg cannot consume Android's request-scoped OkHttp [DnsPolicy] implementation.
 * V1 deliberately keeps libmpv on its system resolver instead of pretending that it applied the
 * playlist's application resolver. Network outcomes from this fallback remain network facts and
 * are never eligible to teach decoder or renderer compatibility history.
 */
internal enum class MpvDnsMode {
    SYSTEM,
    SYSTEM_FALLBACK_FOR_APPLICATION_DNS,
}

internal object MpvAdapterPlanFactory {
    private const val DEFAULT_USER_AGENT = com.nuvio.tv.playback.core.DEFAULT_STREAM_USER_AGENT

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
        // Product decision (2026-08-28): video is priority, subtitles are best-effort. Direct
        // render (mediacodec_embed) cannot draw subtitles; COMPATIBLE fidelity accepts that and
        // keeps the 1.5.8 direct live path. Only an explicit FULL fidelity demand excludes the
        // direct graph — mirroring PlaybackPolicy's selection filter exactly, so a graph the
        // policy selects is never vetoed here.
        if (graph.outputProfile == GraphOutputProfile.MPV_DIRECT &&
            (graph.decoderMode == DecoderMode.SOFTWARE ||
                (requirements.subtitlesEnabled &&
                    requirements.subtitleFidelity == SubtitleFidelity.FULL))
        ) {
            return unsupported(FailureCode.NO_ELIGIBLE_GRAPH)
        }

        val headers = request.assembledHttpHeaders()

        val options = linkedMapOf(
            "config" to "no",
            // 1.5.8-proven (NuvioMpvSurfaceView): a core that is not idle=yes is a zap-session
            // time bomb — at mpv's default (idle=no) the first `stop` empties the playlist and
            // the core initiates self-termination, wedging every later native command. The
            // source-replacement reuse lane depends on the core surviving `stop`.
            "idle" to "yes",
            "terminal" to "no",
            // Raw mpv/FFmpeg messages may contain provider URLs; normalized facts are the only
            // clean-adapter diagnostic channel.
            "msg-level" to "all=no",
            // Live: surface a genuinely-dead channel fast so PlaybackSession's fresh-link reconnect
            // can start, instead of hanging up to mpv's 60s default before end-file. Transient
            // mid-stream drops are already absorbed by the socket-level stream-lavf-o reconnect
            // (reconnect_delay_max=5) below, so this bound only governs "how long before we admit the
            // channel is dead". VOD/catch-up keep the long default (a slow seekable read is not dead).
            "network-timeout" to if (request.contentType == ContentType.LIVE) "15" else "60",
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
        // FFmpeg demuxer-level reconnect (the VLC/mpv approach to a transient live drop): re-open
        // the HTTP read underneath the running graph so the decoder + video output stay alive — the
        // last frame stays frozen under the buffering spinner and playback resumes without a black
        // screen. Scoped to re-openable live: a single-use / SESSION_ONLY link (e.g. Stalker
        // create_link) is dead after first use, so it must NOT be re-opened at the socket layer —
        // only PlaybackSession may mint a fresh link. reconnect_delay_max bounds the socket-level
        // retry; if FFmpeg still can't restore the read it emits end-file and PlaybackSession's
        // fresh-link reconnect loop takes over. VOD/catch-up keep FFmpeg's default (a legitimate
        // stream end must remain a clean EOF, never a reconnect).
        if (!request.network.retryConnectionFailures ||
            request.network.transientLoadRetryPolicy == TransientLoadRetryPolicy.SESSION_ONLY
        ) {
            options["stream-lavf-o"] = "reconnect=0,reconnect_streamed=0"
        } else if (request.contentType == ContentType.LIVE) {
            options["stream-lavf-o"] = "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5"
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
        // Direct render cannot draw subtitles — do not decode them there (best-effort decision).
        options["sid"] = if (
            requirements.subtitlesEnabled && graph.outputProfile != GraphOutputProfile.MPV_DIRECT
        ) {
            "auto"
        } else {
            "no"
        }

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
                dnsMode = when (request.dnsPolicy) {
                    DnsPolicy.SYSTEM -> MpvDnsMode.SYSTEM
                    DnsPolicy.SHARED_APPLICATION_RESOLVER ->
                        MpvDnsMode.SYSTEM_FALLBACK_FOR_APPLICATION_DNS
                },
                startPositionMs = input.startPositionMs,
                playbackRate = input.playbackRate,
                restorationCheckpoint = input.restorationCheckpoint,
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
