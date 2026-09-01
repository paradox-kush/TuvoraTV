package com.nuvio.tv.playback.core

/**
 * Default stream User-Agent when a request carries none — byte-identical to the legacy player's
 * stream UA (PlayerMediaSourceFactory). IPTV panels are UA-gated: an unrecognized agent draws
 * WAF refusals (401/403/407) on tiers that accept the fleet's long-standing browser UA, so the
 * cutover must not change the wire identity of provider streams. Both engine plans consume this.
 */
const val DEFAULT_STREAM_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** A secret-bearing request. Its string form is deliberately limited to [RequestSummary]. */
class PlaybackRequest(
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
    val applicationDnsKey: ApplicationDnsKey? = null,
    val network: PlaybackNetworkRequest = PlaybackNetworkRequest(),
    val drm: DrmRequest? = null,
    val contentType: ContentType,
    val contentKey: SecretValue? = null,
    val providerConnectionLimit: Int? = null,
) {
    init {
        require(url.isNotBlank()) { "Playback URL must not be blank" }
        require(providerConnectionLimit == null || providerConnectionLimit > 0) {
            "Provider connection limit must be positive"
        }
        require(applicationDnsKey == null || dnsPolicy == DnsPolicy.SHARED_APPLICATION_RESOLVER) {
            "An application DNS key requires the shared application resolver policy"
        }
    }

    fun summary(): RequestSummary = RequestSummary(
        scheme = url.substringBefore(':', missingDelimiterValue = "unknown")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z][a-z0-9+.-]*")) }
            ?: "unknown",
        contentType = contentType,
        hasAuthorization = headers.keys.any { it.equals("authorization", ignoreCase = true) } ||
            url.substringAfter("://", missingDelimiterValue = "")
                .substringBefore('/')
                .substringBefore('?')
                .contains('@'),
        hasCustomHeaders = headers.isNotEmpty(),
        hasCookies = cookies.isNotEmpty(),
        hasUserAgent = !userAgent.isNullOrBlank(),
        hasReferer = !referer.isNullOrBlank(),
        hasOrigin = !origin.isNullOrBlank(),
        hasDrm = drm != null,
        redirectPolicy = redirectPolicy,
        crossHostAuthorization = crossHostAuthorization,
        tlsPolicy = tlsPolicy,
        dnsPolicy = dnsPolicy,
        proxyMode = network.proxyMode,
        hasCustomNetworkPolicy = network != PlaybackNetworkRequest(),
        transientLoadRetryPolicy = network.transientLoadRetryPolicy,
        providerConnectionConstrained = providerConnectionLimit != null,
        secureOutputRequired = drm?.secureOutputRequired == true,
    )

    override fun toString(): String = "PlaybackRequest(summary=${summary()})"
}

/** Opaque secret value with value equality but permanently redacted string output. */
class SecretValue(val value: String) {
    override fun equals(other: Any?): Boolean = other is SecretValue && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "[REDACTED]"
}

/** Opaque application-resolver selector; provider configuration never appears in string output. */
class ApplicationDnsKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Application DNS key must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is ApplicationDnsKey && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "[REDACTED]"
}

/** Stable provider identity that is usable by a resolver but never printable. */
class ProviderSelectionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Provider selection id must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is ProviderSelectionId && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "[REDACTED]"
}

/** Exact profile scope captured when a playback owner is created; never printable in diagnostics. */
class PlaybackProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "Playback profile id must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is PlaybackProfileId && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "[REDACTED]"
}

enum class ProviderSourceType { XTREAM, M3U, STALKER }

/** Programme bounds needed to resolve a finite provider catch-up stream after the release barrier. */
class ProviderCatchUpWindow(
    val startEpochMs: Long,
    val endEpochMs: Long,
) {
    init {
        require(startEpochMs >= 0) { "Catch-up start must not be negative" }
        require(endEpochMs > startEpochMs) { "Catch-up end must be after start" }
    }

    override fun equals(other: Any?): Boolean = other is ProviderCatchUpWindow &&
        startEpochMs == other.startEpochMs && endEpochMs == other.endEpochMs

    override fun hashCode(): Int = 31 * startEpochMs.hashCode() + endEpochMs.hashCode()
    override fun toString(): String = "ProviderCatchUpWindow(hasBounds=true)"
}

/**
 * URL-free provider selection. It may cross the UI/session boundary before the current engine is
 * released because it contains stable opaque ids and non-secret playback metadata only. A provider
 * resolver turns it into a concrete [PlaybackRequest] inside the session's serialized lane.
 */
class ProviderPlaybackSelection(
    val sourceType: ProviderSourceType,
    val accountId: ProviderSelectionId,
    val itemId: ProviderSelectionId,
    val contentKey: ProviderSelectionId,
    val contentType: ContentType,
    val catchUpWindow: ProviderCatchUpWindow? = null,
    val providerConnectionLimit: Int? = 1,
    val declaredEvidence: StreamEvidence = StreamEvidence(),
) {
    init {
        require(providerConnectionLimit == null || providerConnectionLimit > 0) {
            "Provider connection limit must be positive"
        }
        require((contentType == ContentType.CATCH_UP) == (catchUpWindow != null)) {
            "Catch-up selections require bounds and non-catch-up selections must not carry them"
        }
    }

    override fun toString(): String =
        "ProviderPlaybackSelection(sourceType=$sourceType, contentType=$contentType, " +
            "hasCatchUpWindow=${catchUpWindow != null}, " +
            "providerConnectionConstrained=${providerConnectionLimit != null}, " +
            "hasDeclaredEvidence=${declaredEvidence != StreamEvidence()})"
}

/** Why a deferred provider link is being minted; it never carries provider text or URLs. */
enum class ProviderResolutionTrigger { INITIAL, RECOVERY, HANDOFF }

enum class ProviderDialectAdvanceEligibility {
    NONE,
    TRANSPORT_OR_DEMUX_FAILURE,
    INELIGIBLE_PLAYBACK_FAILURE,
}

/**
 * Typed feedback for resolver-owned catch-up dialect selection. A resolver may advance a TS/HLS
 * dialect only for [ProviderDialectAdvanceEligibility.TRANSPORT_OR_DEMUX_FAILURE]; decoder and
 * renderer failures are explicitly ineligible and must not walk provider URL dialects.
 */
data class ProviderResolutionFeedback(
    val code: FailureCode,
    val domain: FailureDomain,
    val phase: FailurePhase,
    val dialectAdvanceEligibility: ProviderDialectAdvanceEligibility,
)

data class ProviderResolutionContext(
    val trigger: ProviderResolutionTrigger,
    val previousFailure: ProviderResolutionFeedback? = null,
)

/** One startup target: either today's concrete migration input or a URL-free provider selection. */
sealed interface PlaybackLaunch {
    val contentType: ContentType

    class ConcreteRequest(val request: PlaybackRequest) : PlaybackLaunch {
        override val contentType: ContentType get() = request.contentType
        override fun toString(): String = "PlaybackLaunch.ConcreteRequest($request)"
    }

    class DeferredProvider(val selection: ProviderPlaybackSelection) : PlaybackLaunch {
        override val contentType: ContentType get() = selection.contentType
        override fun toString(): String = "PlaybackLaunch.DeferredProvider($selection)"
    }
}

class DrmRequest(
    val scheme: DrmScheme,
    val licenseUrl: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val multiSession: Boolean = false,
    /** True only when the content/license contract explicitly requires a protected video surface. */
    val secureOutputRequired: Boolean = false,
) {
    init {
        require(licenseUrl.isNotBlank()) { "DRM license URL must not be blank" }
    }

    override fun toString(): String =
        "DrmRequest(scheme=$scheme, hasLicenseUrl=true, hasRequestHeaders=${requestHeaders.isNotEmpty()}, " +
            "multiSession=$multiSession, secureOutputRequired=$secureOutputRequired)"
}

data class RequestSummary(
    val scheme: String,
    val contentType: ContentType,
    val hasAuthorization: Boolean,
    val hasCustomHeaders: Boolean,
    val hasCookies: Boolean,
    val hasUserAgent: Boolean,
    val hasReferer: Boolean,
    val hasOrigin: Boolean,
    val hasDrm: Boolean,
    val redirectPolicy: RedirectPolicy,
    val crossHostAuthorization: CrossHostAuthorization,
    val tlsPolicy: TlsPolicy,
    val dnsPolicy: DnsPolicy,
    val proxyMode: ProxyMode = ProxyMode.SYSTEM,
    val hasCustomNetworkPolicy: Boolean = false,
    val transientLoadRetryPolicy: TransientLoadRetryPolicy = TransientLoadRetryPolicy.ENGINE_DEFAULT,
    val providerConnectionConstrained: Boolean,
    val secureOutputRequired: Boolean = false,
)

enum class ContentType { LIVE, CATCH_UP, VOD }
enum class RedirectPolicy { FOLLOW, REJECT }
enum class CrossHostAuthorization { STRIP, PRESERVE }
enum class TlsPolicy { PLATFORM_DEFAULT, STRICT }
enum class DnsPolicy { SYSTEM, SHARED_APPLICATION_RESOLVER }
enum class DrmScheme { WIDEVINE, PLAYREADY, CLEARKEY, UNKNOWN }

enum class ProxyMode { SYSTEM, DIRECT, HTTP }

/** Secret-safe HTTP proxy material. Credentials never appear in string output. */
class HttpProxyRequest(
    val host: String,
    val port: Int,
    val username: SecretValue? = null,
    val password: SecretValue? = null,
) {
    init {
        require(host.isNotBlank()) { "Proxy host must not be blank" }
        require(port in 1..65_535) { "Proxy port must be valid" }
        require((username == null) == (password == null)) {
            "Proxy username and password must either both be present or both be absent"
        }
    }

    override fun equals(other: Any?): Boolean = other is HttpProxyRequest &&
        host == other.host && port == other.port && username == other.username && password == other.password

    override fun hashCode(): Int = arrayOf(host, port, username, password).contentHashCode()

    override fun toString(): String =
        "HttpProxyRequest(hasHost=true, port=$port, hasCredentials=${username != null})"
}

enum class TransientLoadRetryPolicy {
    /** The media stack may perform its bounded, in-request transient load recovery. */
    ENGINE_DEFAULT,

    /** The adapter reports the failure without reopening; only [PlaybackSession] may retry. */
    SESSION_ONLY,
}

/** Engine-neutral network intent materialized equivalently by every adapter. */
data class PlaybackNetworkRequest(
    val proxyMode: ProxyMode = ProxyMode.SYSTEM,
    val httpProxy: HttpProxyRequest? = null,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    val callTimeoutMs: Int? = null,
    val retryConnectionFailures: Boolean = true,
    val transientLoadRetryPolicy: TransientLoadRetryPolicy = TransientLoadRetryPolicy.ENGINE_DEFAULT,
) {
    init {
        require(connectTimeoutMs > 0) { "Connect timeout must be positive" }
        require(readTimeoutMs > 0) { "Read timeout must be positive" }
        require(callTimeoutMs == null || callTimeoutMs > 0) {
            "Call timeout must be positive when present"
        }
        require((proxyMode == ProxyMode.HTTP) == (httpProxy != null)) {
            "An HTTP proxy is required only for HTTP proxy mode"
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 15_000
        const val DEFAULT_READ_TIMEOUT_MS: Int = 60_000
    }
}

data class EvidenceFact<T>(
    val value: T,
    val provenance: EvidenceProvenance,
)

enum class EvidenceProvenance {
    EXTRACTOR_CONFIRMED,
    MANIFEST_CONFIRMED,
    PROVIDER_DECLARED,
    HTTP_MIME_HINT,
    HLS_CODECS_ATTRIBUTE,
    SEGMENT_HINT,
    URL_INFERRED,
    UNKNOWN,
}

data class StreamEvidence(
    val delivery: EvidenceFact<DeliveryType>? = null,
    val container: EvidenceFact<ContainerType>? = null,
    val videoCodec: EvidenceFact<VideoCodec>? = null,
    val audioCodec: EvidenceFact<AudioCodec>? = null,
    val subtitleFormat: EvidenceFact<SubtitleFormat>? = null,
    val drmScheme: EvidenceFact<DrmScheme>? = null,
    val dimensions: EvidenceFact<VideoDimensions>? = null,
    val frameRate: EvidenceFact<Double>? = null,
    val adaptive: EvidenceFact<Boolean>? = null,
)

enum class DeliveryType { HLS, DASH, PROGRESSIVE, RAW_TRANSPORT_STREAM, RTSP, RTP, UDP, UNKNOWN }
enum class ContainerType { MPEG_TS, FMP4, MP4, MATROSKA, WEBM, MPEG_PS, OGG, UNKNOWN }
enum class VideoCodec { AVC, HEVC, AV1, VP9, MPEG2, MPEG4, VC1, DOLBY_VISION, UNKNOWN }
enum class AudioCodec { AAC, AC3, EAC3, TRUEHD, DTS, DTS_HD, OPUS, VORBIS, MP3, PCM, UNKNOWN }
enum class SubtitleFormat { WEBVTT, TTML, CEA_608, CEA_708, SRT, ASS, PGS, DVB, UNKNOWN }

data class VideoDimensions(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Video dimensions must be positive" }
    }

    /** More pixels than 1080p (1440p, 4K): hardware decoders need noticeably longer to open. */
    val isHighResolution: Boolean get() = width.toLong() * height > FULL_HD_PIXELS

    private companion object {
        const val FULL_HD_PIXELS = 1920L * 1080L
    }
}

data class RuntimeCapabilities(
    val snapshotVersion: Int,
    val capturedAtEpochMs: Long,
    val apiLevel: Int,
    val videoDecoders: List<VideoDecoderCapability> = emptyList(),
    val display: DisplayCapabilities,
    val audioRoute: AudioRouteCapabilities,
    val resources: ResourceCapabilities,
    val surfaces: SurfaceCapabilities,
    val verifiedQuirkIds: Set<String> = emptySet(),
)

data class VideoDecoderCapability(
    val stableId: String,
    val codec: VideoCodec,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val vendorProvided: Boolean,
    val securePlayback: Boolean,
    val maxDimensions: VideoDimensions? = null,
    val maxFrameRate: Double? = null,
    val profileLevels: Set<String> = emptySet(),
    val maxSupportedInstances: Int? = null,
)

data class DisplayCapabilities(
    val currentDimensions: VideoDimensions,
    val supportedRefreshRates: Set<Double> = emptySet(),
    val hdrTypes: Set<HdrType> = emptySet(),
    val modeSwitchSupported: Boolean = false,
)

enum class HdrType { HDR10, HDR10_PLUS, HLG, DOLBY_VISION }

data class AudioRouteCapabilities(
    val route: AudioRoute,
    val encodedFormats: Set<AudioCodec> = emptySet(),
    val maxChannelCount: Int = 2,
    val offloadSupported: Boolean = false,
)

enum class AudioRoute { TV_SPEAKERS, HDMI, HDMI_EARC, BLUETOOTH, USB, UNKNOWN }

data class ResourceCapabilities(
    val availableMemoryBytes: Long,
    val lowMemory: Boolean,
    val thermalState: ThermalState = ThermalState.UNKNOWN,
    val concurrentDecoderBudget: Int = 1,
)

enum class ThermalState { NOMINAL, FAIR, SERIOUS, CRITICAL, UNKNOWN }

data class SurfaceCapabilities(
    val surfaceViewSupported: Boolean = true,
    val textureViewSupported: Boolean = true,
    val nativeEmbedSupported: Boolean = false,
    val secureSurfaceSupported: Boolean = false,
    val secureNativeEmbedSupported: Boolean = false,
    val gpuRenderingSupported: Boolean = false,
    val secureGpuRenderingSupported: Boolean = false,
)

data class PlaybackPreferences(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val engine: EnginePreference = EnginePreference.AUTO,
    val automaticFallback: Boolean = true,
    val decoder: DecoderPreference = DecoderPreference.AUTO,
    val softwareDecodeFallback: Boolean = false,
    val buffering: BufferingPreference = BufferingPreference.RECOMMENDED,
    val customBuffer: CustomBufferPreference? = null,
    val audio: AudioPreference = AudioPreference(),
    val subtitles: SubtitlePreference = SubtitlePreference(),
    val display: DisplayPreference = DisplayPreference(),
    val video: VideoPreference = VideoPreference(),
    val behavior: PlaybackBehaviorPreference = PlaybackBehaviorPreference(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2
        fun recommended(): PlaybackPreferences = PlaybackPreferences()
    }
}

enum class EnginePreference { AUTO, MEDIA3, LIBMPV }
enum class DecoderPreference { AUTO, HARDWARE_ONLY, SOFTWARE_ONLY }
enum class BufferingPreference { RECOMMENDED, BALANCED, LOW_LATENCY_LIVE, CUSTOM }
enum class AudioOutputPreference { AUTO, PASSTHROUGH, PCM }
enum class SubtitleFidelity { COMPATIBLE, FULL }
enum class FrameRatePreference {
    OFF,
    ON_START,
    ON_RATE_CHANGE;

    companion object {
        /** Source-compatibility aliases only; persisted aliases are migrated by the clean schema. */
        @Deprecated("Use ON_START", ReplaceWith("ON_START"))
        val ON_COMMITTED_PLAYBACK: FrameRatePreference = ON_START

        @Deprecated("Use ON_RATE_CHANGE", ReplaceWith("ON_RATE_CHANGE"))
        val ALWAYS: FrameRatePreference = ON_RATE_CHANGE
    }
}
enum class HdrPreference { AUTO, SDR, HDR10, DOLBY_VISION }

data class CustomBufferPreference(
    val minimumBufferMs: Int,
    val maximumBufferMs: Int,
    val playbackStartBufferMs: Int,
    val rebufferStartBufferMs: Int,
) {
    init {
        require(minimumBufferMs >= 0 && maximumBufferMs >= minimumBufferMs)
        require(playbackStartBufferMs in 0..maximumBufferMs)
        require(rebufferStartBufferMs in 0..maximumBufferMs)
    }
}

data class AudioPreference(
    val output: AudioOutputPreference = AudioOutputPreference.AUTO,
    val downmixToStereo: Boolean = false,
    val normalization: Boolean = false,
    val skipSilence: Boolean = false,
    val preferredLanguage: String? = null,
    val delayMs: Long = 0,
)

data class SubtitlePreference(
    val enabled: Boolean = true,
    val fidelity: SubtitleFidelity = SubtitleFidelity.COMPATIBLE,
    val preferredLanguage: String? = null,
    val delayMs: Long = 0,
)

data class DisplayPreference(
    val frameRate: FrameRatePreference = FrameRatePreference.ON_START,
    val resolutionMatching: Boolean = false,
)

data class VideoPreference(
    val hdr: HdrPreference = HdrPreference.AUTO,
    val maximumDimensions: VideoDimensions? = null,
)

data class PlaybackBehaviorPreference(
    val autoplayNext: Boolean = true,
    val stillWatchingEnabled: Boolean = true,
    val showStatusIndicators: Boolean = true,
)

data class PreferenceResolution<T>(
    val requested: T,
    val effective: T?,
    val authority: ResolutionAuthority,
    val availability: PreferenceAvailability,
    val primaryReason: PreferenceReason,
    val contributingReasons: Set<PreferenceReason> = emptySet(),
    val conflicts: Set<PreferenceConflict> = emptySet(),
    val impact: ChangeImpact,
)

enum class ResolutionAuthority {
    HARD_CONSTRAINT,
    STREAM_ELIGIBILITY,
    TEMPORARY_USER_OVERRIDE,
    SAVED_USER_OVERRIDE,
    LEARNED_COMPATIBILITY,
    DEFAULT_POLICY,
}

enum class PreferenceAvailability { SUPPORTED, UNAVAILABLE, EXPERIMENTAL }

enum class PreferenceReason {
    REQUEST_EFFECTIVE,
    DRM_REQUIRES_MEDIADRM,
    EXPLICIT_USER_OVERRIDE,
    KNOWN_FATAL_INCOMPATIBILITY,
    PCM_PROCESSING_REQUIRES_DECODED_AUDIO,
    DOLBY_VISION_OUTPUT_UNAVAILABLE,
    SUBTITLE_FIDELITY_REQUIRES_RENDER,
    AFR_DEFERRED_DURING_ZAP,
    SOFTWARE_DECODE_EXCEEDS_RESOURCE_BUDGET,
    BUFFER_CLAMPED_TO_MEMORY_BUDGET,
    UNSUPPORTED_BY_STREAM,
    UNSUPPORTED_BY_DEVICE,
}

data class PreferenceConflict(
    val code: String,
    val affectedPreference: String,
)

enum class ChangeImpact { APPLY_IN_PLACE, REBUILD_CURRENT_GRAPH, RESELECT_GRAPH, NEXT_SESSION_ONLY }

enum class SessionProfile { GUIDE, FULLSCREEN }
enum class VideoQualityIntent { PREVIEW, FULL, CUSTOM }
enum class SessionPriority { STARTUP_SPEED, QUALITY_AND_STABILITY }

data class ResourceBudget(
    val networkBitrateCeiling: Long? = null,
    val adaptiveDimensionCeiling: VideoDimensions? = null,
    val decoderCost: ResourceAllowance = ResourceAllowance.NORMAL,
    val memoryCost: ResourceAllowance = ResourceAllowance.NORMAL,
    val gpuCost: ResourceAllowance = ResourceAllowance.NORMAL,
    val surfaceCost: ResourceAllowance = ResourceAllowance.NORMAL,
) {
    init {
        require(networkBitrateCeiling == null || networkBitrateCeiling > 0) {
            "Network bitrate ceiling must be positive when present"
        }
    }
}

enum class ResourceAllowance { MINIMAL, NORMAL, HIGH, DISALLOWED }

data class PlaybackRequirements(
    val profile: SessionProfile,
    val priority: SessionPriority,
    val qualityIntent: VideoQualityIntent,
    val preferredAdaptiveDimensions: VideoDimensions? = null,
    val adaptiveDimensionCeiling: VideoDimensions? = null,
    val bitrateCeiling: Long? = null,
    val displayModeSwitchAllowed: Boolean,
    val resolutionMatchingEnabled: Boolean = false,
    val frameRatePreference: FrameRatePreference,
    val hdrPreference: HdrPreference,
    val decoderPreference: DecoderPreference,
    val softwareDecodeFallbackAllowed: Boolean,
    val subtitleFidelity: SubtitleFidelity,
    val subtitlesEnabled: Boolean,
    val audioOutput: AudioOutputPreference,
    val pcmProcessingAllowed: Boolean,
    val buffering: BufferingPreference,
    val customBuffer: CustomBufferPreference? = null,
    val audioDownmixToStereo: Boolean = false,
    val audioNormalization: Boolean = false,
    val audioSkipSilence: Boolean = false,
    val preferredAudioLanguage: String? = null,
    val audioDelayMs: Long = 0,
    val preferredSubtitleLanguage: String? = null,
    val subtitleDelayMs: Long = 0,
    val gpuRenderingAllowed: Boolean,
    val eligibleEngines: Set<EngineType>,
    val preferredEngineOrder: List<EngineType> = emptyList(),
    val allowedSurfaceModes: Set<SurfaceMode> = SurfaceMode.entries.toSet(),
    val secureOutputRequired: Boolean,
    val resourceBudget: ResourceBudget,
    /**
     * Live content is zapped and judged on motion smoothness, so its mpv output ranking favours
     * the zero-copy direct embed over the GPU render path even in fullscreen. Fixed for the life
     * of a request (content type never changes mid-session), so it takes no part in diffs.
     */
    val liveContent: Boolean = false,
) {
    init {
        require(eligibleEngines.isNotEmpty()) { "At least one playback engine must be eligible" }
        require(allowedSurfaceModes.isNotEmpty()) { "At least one output surface must be eligible" }
        require(
            preferredAdaptiveDimensions == null ||
                adaptiveDimensionCeiling == null ||
                preferredAdaptiveDimensions.width <= adaptiveDimensionCeiling.width &&
                preferredAdaptiveDimensions.height <= adaptiveDimensionCeiling.height
        ) { "Preferred adaptive dimensions cannot exceed the adaptive ceiling" }
        require(preferredEngineOrder.distinct().size == preferredEngineOrder.size) {
            "Preferred engine order must not contain duplicates"
        }
        require(preferredEngineOrder.all(eligibleEngines::contains)) {
            "Preferred engine order may contain only eligible engines"
        }
    }
}

enum class RequirementsField {
    PROFILE,
    ADAPTIVE_QUALITY,
    NETWORK_BITRATE,
    DISPLAY_OUTPUT,
    HDR,
    DECODER,
    SUBTITLE_SELECTION,
    SUBTITLE_RENDERER,
    AUDIO_OUTPUT,
    AUDIO_PIPELINE,
    AUDIO_RUNTIME_PROCESSING,
    AUDIO_SELECTION,
    BUFFERING,
    GPU_RENDERING,
    ENGINE_ELIGIBILITY,
    ENGINE_ORDER,
    SURFACE_ELIGIBILITY,
    SECURE_OUTPUT,
    RESOURCE_BUDGET,
}

data class PlaybackRequirementsDiff(
    val impact: ChangeImpact,
    val changedFields: Set<RequirementsField>,
)

enum class EngineType { MEDIA3, LIBMPV }
enum class GraphOutputProfile { MEDIA3_STANDARD, MPV_DIRECT, MPV_RENDER }
enum class DecoderMode { HARDWARE, SOFTWARE }
enum class AudioMode { PASSTHROUGH, OFFLOAD, DECODE }
enum class SurfaceMode { SURFACE_VIEW, TEXTURE_VIEW, NATIVE_EMBED, GPU_RENDER }

data class PlaybackGraph(
    val id: String,
    val engine: EngineType,
    val outputProfile: GraphOutputProfile,
    val decoderMode: DecoderMode,
    val audioMode: AudioMode,
    val surfaceMode: SurfaceMode,
    val secureOutput: Boolean = false,
) {
    /** Reject adapter bugs as graph-selection input instead of letting impossible graphs run. */
    fun isStructurallyValid(): Boolean {
        val engineOwnsOutput = when (engine) {
            EngineType.MEDIA3 -> outputProfile == GraphOutputProfile.MEDIA3_STANDARD
            EngineType.LIBMPV -> outputProfile != GraphOutputProfile.MEDIA3_STANDARD
        }
        val outputOwnsSurface = when (outputProfile) {
            GraphOutputProfile.MEDIA3_STANDARD ->
                surfaceMode == SurfaceMode.SURFACE_VIEW || surfaceMode == SurfaceMode.TEXTURE_VIEW
            GraphOutputProfile.MPV_DIRECT -> surfaceMode == SurfaceMode.NATIVE_EMBED
            GraphOutputProfile.MPV_RENDER -> surfaceMode == SurfaceMode.GPU_RENDER
        }
        return engineOwnsOutput && outputOwnsSurface
    }
}

data class PlaybackFailure(
    val code: FailureCode,
    val domain: FailureDomain,
    val phase: FailurePhase,
    val retryability: Retryability,
    val deterministic: Boolean = false,
    val httpStatus: Int? = null,
    val statusProvenance: HttpStatusProvenance? = null,
)

enum class HttpStatusProvenance { CONFIRMED, INFERRED_FROM_NETWORK_ERROR }

enum class FailureCode {
    NETWORK_UNREACHABLE,
    NETWORK_TIMEOUT,
    AUTHORIZATION_REJECTED,
    PROVIDER_CONNECTION_LIMIT,
    TLS_HANDSHAKE_FAILED,
    MANIFEST_INVALID,
    DEMUX_FAILED,
    VIDEO_DECODER_UNAVAILABLE,
    VIDEO_DECODER_FAILED,
    VIDEO_RENDERER_FAILED,
    SURFACE_LOST,
    AUDIO_OUTPUT_FAILED,
    AUDIO_DECODER_FAILED,
    AUDIO_SINK_FAILED,
    SUBTITLE_OUTPUT_UNSUPPORTED,
    DRM_UNSUPPORTED,
    DRM_LICENSE_FAILED,
    RESOURCE_BUDGET_EXCEEDED,
    RESOURCE_RELEASE_FAILED,
    NO_ELIGIBLE_GRAPH,
    NO_PROGRESS,
    LIVE_RECONNECT_EXHAUSTED,
    UNKNOWN,
}

enum class FailureDomain {
    NETWORK,
    AUTHORIZATION_PROVIDER_LIMIT,
    TLS,
    MANIFEST,
    DEMUX,
    VIDEO_DECODER,
    VIDEO_RENDERER_SURFACE,
    AUDIO,
    AUDIO_DECODER,
    AUDIO_SINK,
    SUBTITLE,
    DRM,
    DEVICE_RESOURCE,
    UNKNOWN,
}

enum class FailurePhase {
    REQUEST_RESOLUTION,
    GRAPH_SELECTION,
    SURFACE_ATTACHMENT,
    ENGINE_START,
    PLAYBACK,
    RECOVERY,
    RELEASE,
}

enum class Retryability { RETRYABLE_IN_PLACE, RETRYABLE_WITH_FRESH_REQUEST, HANDOFF_ELIGIBLE, FATAL }

sealed interface PreviewAvailability {
    data object Unknown : PreviewAvailability
    data object Available : PreviewAvailability
    data class Unavailable(val reason: PreviewUnavailableReason) : PreviewAvailability
}

enum class PreviewUnavailableReason {
    GUIDE_RESOURCE_RESTRICTION,
    GUIDE_SURFACE_RESTRICTION,
    GUIDE_RENDER_PATH_UNAVAILABLE,
    PREFERRED_ENGINE_FAILED,
    ALL_PREVIEW_GRAPHS_FAILED,
}

sealed interface StreamAvailability {
    data object Unknown : StreamAvailability
    data object Available : StreamAvailability
    data class TerminallyUnavailable(
        val reason: StreamUnavailableReason,
        val evidence: TerminalAvailabilityEvidence,
    ) : StreamAvailability
}

enum class StreamUnavailableReason { AUTHORIZATION, REMOVED_OR_EXPIRED, PROVIDER_DECLARED, NO_ELIGIBLE_GRAPH }
enum class TerminalAvailabilityEvidence { SOURCE_CONFIRMED, PROVIDER_DECLARED, ALL_ELIGIBLE_GRAPHS_EXHAUSTED }

enum class PlaybackState {
    IDLE,
    RESOLVING,
    SELECTING_GRAPH,
    ATTACHING_SURFACE,
    STARTING_PRIMARY,
    PLAYING,
    DEGRADED,
    RECOVERING_IN_PLACE,
    HANDING_OFF_ONCE,
    LIVE_RECONNECTING,
    RELEASING,
    STOPPED,
    FAILED,
}

enum class PlaybackEndReason { EOF, ERROR, STOPPED, SHUTDOWN }
enum class PlaybackEngineState { IDLE, BUFFERING, READY, ENDED }

sealed interface PlaybackEvent {
    val generation: Long

    data class RequestResolved(
        override val generation: Long,
        val summary: RequestSummary,
        val evidence: StreamEvidence,
        /** Concrete request produced by deferred resolution; null preserves migration-test calls. */
        val request: PlaybackRequest? = null,
    ) : PlaybackEvent

    /** Fresh concrete facts committed during recovery without changing the current recovery state. */
    data class RequestRefreshed(
        override val generation: Long,
        val summary: RequestSummary,
        val evidence: StreamEvidence,
        val request: PlaybackRequest,
    ) : PlaybackEvent

    /** Fresh deferred facts that must precede selection of the one allowed handoff graph. */
    data class HandoffRequestResolved(
        override val generation: Long,
        val summary: RequestSummary,
        val evidence: StreamEvidence,
        val request: PlaybackRequest,
        val failedGraph: PlaybackGraph,
        val failure: PlaybackFailure,
    ) : PlaybackEvent

    data class GraphSelected(override val generation: Long, val graph: PlaybackGraph) : PlaybackEvent
    data class SurfaceAttached(override val generation: Long) : PlaybackEvent
    data class EngineStarting(override val generation: Long) : PlaybackEvent
    data class BytesReceived(override val generation: Long) : PlaybackEvent
    data class TracksAvailable(
        override val generation: Long,
        val hasVideo: Boolean,
        val audioTrackCount: Int,
        val subtitleTrackCount: Int,
        /** Container-header size of the video track, when the engine knows it at tracks time. */
        val videoDimensions: VideoDimensions? = null,
    ) : PlaybackEvent
    data class TimelineUpdated(
        override val generation: Long,
        val facts: PlaybackTimelineFacts,
    ) : PlaybackEvent
    data class TrackCatalogUpdated(
        override val generation: Long,
        val catalog: PlaybackTrackCatalog,
    ) : PlaybackEvent
    data class PlaybackRateChanged(
        override val generation: Long,
        val rate: Float,
    ) : PlaybackEvent
    data class FirstAudio(override val generation: Long) : PlaybackEvent
    data class FirstVideoFrame(override val generation: Long) : PlaybackEvent
    data class BufferingStarted(override val generation: Long) : PlaybackEvent
    data class BufferingEnded(override val generation: Long) : PlaybackEvent
    /** Passive adapter facts for diagnostics; they never drive recovery or policy. */
    data class EngineStateObserved(
        override val generation: Long,
        val state: PlaybackEngineState,
        val playWhenReady: Boolean,
        val isLoading: Boolean,
    ) : PlaybackEvent
    data class VideoDecoderInitialized(
        override val generation: Long,
        val decoderName: String,
    ) : PlaybackEvent
    data class VideoInputFormatChanged(
        override val generation: Long,
        val sampleMimeType: String?,
    ) : PlaybackEvent
    data class VideoFrameRateChanged(
        override val generation: Long,
        val frameRate: Float,
    ) : PlaybackEvent
    data class VideoSizeChanged(
        override val generation: Long,
        val width: Int,
        val height: Int,
    ) : PlaybackEvent
    data class PlaybackEnded(
        override val generation: Long,
        val reason: PlaybackEndReason,
    ) : PlaybackEvent
    data class Failed(override val generation: Long, val failure: PlaybackFailure) : PlaybackEvent
    data class EngineReleased(override val generation: Long) : PlaybackEvent
}

sealed interface PlaybackCommand {
    data class Tune(
        /** Nullable only so the existing concrete-request constructor remains source compatible. */
        val request: PlaybackRequest?,
        val profile: SessionProfile,
        val providerSelection: ProviderPlaybackSelection? = null,
        val startPositionMs: Long = 0,
    ) : PlaybackCommand {
        constructor(selection: ProviderPlaybackSelection, profile: SessionProfile) :
            this(request = null, profile = profile, providerSelection = selection)

        init {
            require((request == null) != (providerSelection == null)) {
                "Tune requires exactly one concrete request or deferred provider selection"
            }
            require(startPositionMs >= 0) { "Start position must not be negative" }
        }

        val launch: PlaybackLaunch
            get() = request?.let(PlaybackLaunch::ConcreteRequest)
                ?: PlaybackLaunch.DeferredProvider(requireNotNull(providerSelection))
    }

    data class Zap(
        /** Nullable only so the existing concrete-request constructor remains source compatible. */
        val request: PlaybackRequest?,
        val profile: SessionProfile,
        val providerSelection: ProviderPlaybackSelection? = null,
        val startPositionMs: Long = 0,
    ) : PlaybackCommand {
        constructor(selection: ProviderPlaybackSelection, profile: SessionProfile) :
            this(request = null, profile = profile, providerSelection = selection)

        init {
            require((request == null) != (providerSelection == null)) {
                "Zap requires exactly one concrete request or deferred provider selection"
            }
            require(startPositionMs >= 0) { "Start position must not be negative" }
        }

        val launch: PlaybackLaunch
            get() = request?.let(PlaybackLaunch::ConcreteRequest)
                ?: PlaybackLaunch.DeferredProvider(requireNotNull(providerSelection))
    }
    data object Pause : PlaybackCommand
    data object Resume : PlaybackCommand
    data object Retry : PlaybackCommand
    data class SeekTo(val positionMs: Long) : PlaybackCommand {
        init {
            require(positionMs >= 0) { "Seek position must not be negative" }
        }
    }
    data class SetPlaybackRate(val rate: Float) : PlaybackCommand {
        init {
            require(rate.isFinite() && rate in 0.25f..4f) { "Playback rate must be between 0.25 and 4" }
        }
    }
    data class SelectAudioTrack(val trackId: PlaybackTrackId) : PlaybackCommand
    data class SelectSubtitleTrack(val trackId: PlaybackTrackId) : PlaybackCommand
    data object DisableSubtitles : PlaybackCommand
    data class AttachExternalSubtitle(val subtitleId: ExternalSubtitleId) : PlaybackCommand
    data class PreferencesChanged(val preferences: PlaybackPreferences) : PlaybackCommand
    data class SessionProfileChanged(val profile: SessionProfile) : PlaybackCommand
    data object SurfaceAvailable : PlaybackCommand
    data object SurfaceUnavailable : PlaybackCommand
    data object Stop : PlaybackCommand
    data object Release : PlaybackCommand
}

data class PlaybackProgressEvidence(
    val receivedBytes: Boolean = false,
    val discoveredTracks: Boolean = false,
    val decoderReady: Boolean = false,
    val rendererReady: Boolean = false,
    val renderedAudio: Boolean = false,
    val renderedVideoFrame: Boolean = false,
)

data class TrackSummary(
    val audioTrackCount: Int = 0,
    val subtitleTrackCount: Int = 0,
    val hasVideoTrack: Boolean = false,
    val videoDimensions: VideoDimensions? = null,
)

/** Runtime video facts reported by an engine. Values are factual and never URL-derived guesses. */
data class VideoOutputFacts(
    /** Monotonic within one playback generation, including graph rebuilds and handoffs. */
    val revision: Long = 0,
    val frameRate: Float? = null,
    val dimensions: VideoDimensions? = null,
) {
    init {
        require(revision >= 0) { "Video output fact revision must not be negative" }
    }
}

/** Secret-safe result of applying the effective display-output policy. */
enum class PlaybackOutputStatus {
    NOT_REQUESTED,
    WAITING_FOR_COMMIT,
    WAITING_FOR_FRAME_RATE,
    WAITING_FOR_VIDEO_SIZE,
    DISABLED,
    UNSUPPORTED,
    NO_COMPATIBLE_MODE,
    APPLY_NOT_CONFIRMED,
    APPLY_FAILED,
    APPLIED,
    ALREADY_EFFECTIVE,
}

data class PlaybackOutputApplication(
    val status: PlaybackOutputStatus,
)

data class PlaybackOutputRequest(
    val generation: Long,
    val requirements: PlaybackRequirements,
    val facts: VideoOutputFacts,
    val committed: Boolean,
)

data class PlaybackSnapshot(
    val generation: Long = 0,
    val state: PlaybackState = PlaybackState.IDLE,
    val profile: SessionProfile = SessionProfile.FULLSCREEN,
    val requestSummary: RequestSummary? = null,
    val graph: PlaybackGraph? = null,
    /** User/session play intent; remains true while intended playback buffers or recovers. */
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isReconnecting: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val bufferedPositionMs: Long = 0,
    val seekable: Boolean = false,
    val playbackRate: Float = 1f,
    val trackCatalog: PlaybackTrackCatalog = PlaybackTrackCatalog(),
    val completionReason: PlaybackCompletionReason? = null,
    val tracks: TrackSummary = TrackSummary(),
    val progress: PlaybackProgressEvidence = PlaybackProgressEvidence(),
    val videoOutputFacts: VideoOutputFacts = VideoOutputFacts(),
    val playbackOutputStatus: PlaybackOutputStatus = PlaybackOutputStatus.NOT_REQUESTED,
    val failure: PlaybackFailure? = null,
    /** Non-terminal rejection of a user control; playback remains on the current graph. */
    val controlFailure: PlaybackFailure? = null,
    val statusCode: PlaybackStatusCode? = null,
    val previewAvailability: PreviewAvailability = PreviewAvailability.Unknown,
    val streamAvailability: StreamAvailability = StreamAvailability.Unknown,
)

enum class PlaybackStatusCode { RESOLVING, STARTING, BUFFERING, RECONNECTING, HANDING_OFF, STOPPED }

/** Exact compatibility scope key. It may be persisted and compared, but is never printable. */
class CompatibilityScopeKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Compatibility scope key must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is CompatibilityScopeKey && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "CompatibilityScopeKey([REDACTED])"
}

data class CompatibilityGraphFingerprint(
    val engine: EngineType,
    val outputProfile: GraphOutputProfile,
    val decoderMode: DecoderMode,
    val audioMode: AudioMode,
    val surfaceMode: SurfaceMode,
    val secureOutput: Boolean,
    val decoderStableId: String? = null,
) {
    init {
        require(decoderStableId == null || decoderStableId.isNotBlank()) {
            "Decoder stable ID must be null or non-blank"
        }
    }
}

data class CompatibilityRuntimeFingerprint(
    val deviceVersion: String,
    val firmwareVersion: String,
    /** Stable capability/quirk schema fingerprint, never a refresh observation sequence. */
    val capabilityFingerprint: String,
) {
    init {
        require(deviceVersion.isNotBlank()) { "Device version must not be blank" }
        require(firmwareVersion.isNotBlank()) { "Firmware version must not be blank" }
        require(capabilityFingerprint.isNotBlank()) { "Capability fingerprint must not be blank" }
    }
}

data class CompatibilityRecord(
    val scopeKey: CompatibilityScopeKey,
    val graph: CompatibilityGraphFingerprint,
    val runtime: CompatibilityRuntimeFingerprint,
    val outcome: CompatibilityOutcome,
    val failureDomain: FailureDomain? = null,
    val failureCode: FailureCode? = null,
    val appVersion: String,
    val engineVersion: String,
    val recordedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    /** Temporary source-compatible accessors while policy call sites move to [graph]. */
    val engine: EngineType get() = graph.engine
    val outputProfile: GraphOutputProfile get() = graph.outputProfile

    init {
        require(expiresAtEpochMs > recordedAtEpochMs) { "Compatibility record must expire after it is recorded" }
        require(outcome != CompatibilityOutcome.DETERMINISTIC_FATAL || failureDomain != null) {
            "A deterministic fatal record requires a failure domain"
        }
    }

    fun isExpired(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs
}

enum class CompatibilityOutcome { SUCCESS, DETERMINISTIC_FATAL }

/** Closed compatibility-learning allowlist shared by the session hook and persistent store. */
fun isLearnableCompatibilityFailure(domain: FailureDomain?, code: FailureCode?): Boolean =
    when (domain) {
        FailureDomain.MANIFEST -> code == FailureCode.MANIFEST_INVALID
        FailureDomain.DEMUX -> code == FailureCode.DEMUX_FAILED
        FailureDomain.VIDEO_DECODER -> code in setOf(
            FailureCode.VIDEO_DECODER_UNAVAILABLE,
            FailureCode.VIDEO_DECODER_FAILED,
        )
        FailureDomain.VIDEO_RENDERER_SURFACE -> code in setOf(
            FailureCode.VIDEO_RENDERER_FAILED,
            FailureCode.SURFACE_LOST,
        )
        FailureDomain.AUDIO,
        FailureDomain.AUDIO_DECODER,
        FailureDomain.AUDIO_SINK,
        FailureDomain.SUBTITLE,
        FailureDomain.NETWORK,
        FailureDomain.AUTHORIZATION_PROVIDER_LIMIT,
        FailureDomain.TLS,
        FailureDomain.DRM,
        FailureDomain.DEVICE_RESOURCE,
        FailureDomain.UNKNOWN,
        null,
        -> false
    }
