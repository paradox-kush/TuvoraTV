package com.nuvio.tv.playback.core

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
        providerConnectionConstrained = providerConnectionLimit != null,
    )

    override fun toString(): String = "PlaybackRequest(summary=${summary()})"
}

/** Opaque secret value with value equality but permanently redacted string output. */
class SecretValue(val value: String) {
    override fun equals(other: Any?): Boolean = other is SecretValue && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "[REDACTED]"
}

class DrmRequest(
    val scheme: DrmScheme,
    val licenseUrl: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val multiSession: Boolean = false,
) {
    init {
        require(licenseUrl.isNotBlank()) { "DRM license URL must not be blank" }
    }

    override fun toString(): String =
        "DrmRequest(scheme=$scheme, hasLicenseUrl=true, hasRequestHeaders=${requestHeaders.isNotEmpty()}, multiSession=$multiSession)"
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
    val providerConnectionConstrained: Boolean,
)

enum class ContentType { LIVE, CATCH_UP, VOD }
enum class RedirectPolicy { FOLLOW, REJECT }
enum class CrossHostAuthorization { STRIP, PRESERVE }
enum class TlsPolicy { PLATFORM_DEFAULT, STRICT }
enum class DnsPolicy { SYSTEM, SHARED_APPLICATION_RESOLVER }
enum class DrmScheme { WIDEVINE, PLAYREADY, CLEARKEY, UNKNOWN }

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
    val secureSurfaceSupported: Boolean = false,
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
        const val CURRENT_SCHEMA_VERSION: Int = 1
        fun recommended(): PlaybackPreferences = PlaybackPreferences()
    }
}

enum class EnginePreference { AUTO, MEDIA3, LIBMPV }
enum class DecoderPreference { AUTO, HARDWARE_ONLY, SOFTWARE_ONLY }
enum class BufferingPreference { RECOMMENDED, BALANCED, LOW_LATENCY_LIVE, CUSTOM }
enum class AudioOutputPreference { AUTO, PASSTHROUGH, PCM }
enum class SubtitleFidelity { COMPATIBLE, FULL }
enum class FrameRatePreference { OFF, ON_COMMITTED_PLAYBACK, ALWAYS }
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
    val frameRate: FrameRatePreference = FrameRatePreference.ON_COMMITTED_PLAYBACK,
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
)

enum class ResourceAllowance { MINIMAL, NORMAL, HIGH, DISALLOWED }

data class PlaybackRequirements(
    val profile: SessionProfile,
    val priority: SessionPriority,
    val qualityIntent: VideoQualityIntent,
    val preferredAdaptiveDimensions: VideoDimensions? = null,
    val adaptiveDimensionCeiling: VideoDimensions? = null,
    val bitrateCeiling: Long? = null,
    val displayModeSwitchAllowed: Boolean,
    val frameRatePreference: FrameRatePreference,
    val hdrPreference: HdrPreference,
    val decoderPreference: DecoderPreference,
    val softwareDecodeFallbackAllowed: Boolean,
    val subtitleFidelity: SubtitleFidelity,
    val subtitlesEnabled: Boolean,
    val audioOutput: AudioOutputPreference,
    val pcmProcessingAllowed: Boolean,
    val buffering: BufferingPreference,
    val gpuRenderingAllowed: Boolean,
    val eligibleEngines: Set<EngineType>,
    val preferredEngineOrder: List<EngineType> = emptyList(),
    val secureOutputRequired: Boolean,
    val resourceBudget: ResourceBudget,
) {
    init {
        require(eligibleEngines.isNotEmpty()) { "At least one playback engine must be eligible" }
        require(preferredEngineOrder.distinct().size == preferredEngineOrder.size) {
            "Preferred engine order must not contain duplicates"
        }
        require(preferredEngineOrder.all(eligibleEngines::contains)) {
            "Preferred engine order may contain only eligible engines"
        }
    }
}

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
)

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
    DRM_UNSUPPORTED,
    DRM_LICENSE_FAILED,
    RESOURCE_BUDGET_EXCEEDED,
    NO_ELIGIBLE_GRAPH,
    NO_PROGRESS,
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

sealed interface PlaybackEvent {
    val generation: Long

    data class RequestResolved(
        override val generation: Long,
        val summary: RequestSummary,
        val evidence: StreamEvidence,
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
    ) : PlaybackEvent
    data class FirstAudio(override val generation: Long) : PlaybackEvent
    data class FirstVideoFrame(override val generation: Long) : PlaybackEvent
    data class BufferingStarted(override val generation: Long) : PlaybackEvent
    data class BufferingEnded(override val generation: Long) : PlaybackEvent
    data class PlaybackEnded(
        override val generation: Long,
        val reason: PlaybackEndReason,
    ) : PlaybackEvent
    data class Failed(override val generation: Long, val failure: PlaybackFailure) : PlaybackEvent
    data class EngineReleased(override val generation: Long) : PlaybackEvent
}

sealed interface PlaybackCommand {
    data class Tune(val request: PlaybackRequest, val profile: SessionProfile) : PlaybackCommand
    data class Zap(val request: PlaybackRequest, val profile: SessionProfile) : PlaybackCommand
    data object Pause : PlaybackCommand
    data object Resume : PlaybackCommand
    data object Retry : PlaybackCommand
    data class PreferencesChanged(
        val preferences: PlaybackPreferences,
        val impact: ChangeImpact,
    ) : PlaybackCommand
    data class SessionProfileChanged(
        val profile: SessionProfile,
        val impact: ChangeImpact,
    ) : PlaybackCommand
    data object SurfaceAvailable : PlaybackCommand
    data object SurfaceUnavailable : PlaybackCommand
    data object Stop : PlaybackCommand
    data object Release : PlaybackCommand
}

data class PlaybackProgressEvidence(
    val receivedBytes: Boolean = false,
    val discoveredTracks: Boolean = false,
    val renderedAudio: Boolean = false,
    val renderedVideoFrame: Boolean = false,
)

data class TrackSummary(
    val audioTrackCount: Int = 0,
    val subtitleTrackCount: Int = 0,
    val hasVideoTrack: Boolean = false,
)

data class PlaybackSnapshot(
    val generation: Long = 0,
    val state: PlaybackState = PlaybackState.IDLE,
    val profile: SessionProfile = SessionProfile.FULLSCREEN,
    val requestSummary: RequestSummary? = null,
    val graph: PlaybackGraph? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isReconnecting: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val tracks: TrackSummary = TrackSummary(),
    val progress: PlaybackProgressEvidence = PlaybackProgressEvidence(),
    val failure: PlaybackFailure? = null,
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
