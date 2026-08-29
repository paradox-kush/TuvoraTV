package com.nuvio.tv.playback.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A failure is already normalized before it crosses a core port; raw engine exceptions stay local. */
sealed interface PlaybackResult<out T> {
    data class Success<T>(val value: T) : PlaybackResult<T>
    data class Failure(val failure: PlaybackFailure) : PlaybackResult<Nothing>
}

data class ResolvedPlaybackRequest(
    val request: PlaybackRequest,
    val summary: RequestSummary,
    val evidence: StreamEvidence,
    /** Hashed by application/provider wiring; core never derives it from request secrets. */
    val compatibilityScopeKey: CompatibilityScopeKey? = null,
)

fun interface PlaybackRequestResolver {
    suspend fun resolve(request: PlaybackRequest): PlaybackResult<ResolvedPlaybackRequest>
}

/**
 * Resolves a URL-free provider selection after the session's release barrier. Implementations may
 * mint single-use links and must cooperate with coroutine cancellation. Raw exceptions, provider
 * messages, URLs, and credentials must not cross this port; failures use normalized enum facts.
 */
fun interface ProviderPlaybackResolver {
    suspend fun resolve(
        selection: ProviderPlaybackSelection,
        context: ProviderResolutionContext,
    ): PlaybackResult<ResolvedPlaybackRequest>
}

/** Creates a resolver permanently bound to the playback owner's captured profile scope. */
fun interface ProviderPlaybackResolverFactory {
    fun create(profileId: PlaybackProfileId): ProviderPlaybackResolver
}

data class PlaybackEnvironmentSnapshot(
    val runtimeCapabilities: RuntimeCapabilities,
    val resourceBudget: ResourceBudget = ResourceBudget(),
    val previewViewport: VideoDimensions? = null,
    val eligibleEngines: Set<EngineType> = EngineType.entries.toSet(),
    val preferredEngineOrder: List<EngineType> = emptyList(),
    val allowedSurfaceModes: Set<SurfaceMode> = SurfaceMode.entries.toSet(),
    val secureOutputRequired: Boolean,
) {
    init {
        require(preferredEngineOrder.distinct().size == preferredEngineOrder.size)
        require(preferredEngineOrder.all(eligibleEngines::contains))
    }
}

data class PlaybackEnvironmentInput(
    val requestSummary: RequestSummary,
    val evidence: StreamEvidence,
    val profile: SessionProfile,
    val effectivePreferences: PlaybackPreferences,
    /** Exact provider/application hash; null only for concrete migration requests without a scope. */
    val compatibilityScopeKey: CompatibilityScopeKey?,
)

data class PlaybackEnvironmentResolution(
    val snapshot: PlaybackEnvironmentSnapshot,
    /** The complete effective set resolved from this same request/runtime/history snapshot. */
    val effectivePreferences: PlaybackPreferences,
)

fun interface PlaybackEnvironmentProvider {
    suspend fun snapshot(input: PlaybackEnvironmentInput): PlaybackResult<PlaybackEnvironmentResolution>
}

data class PlaybackRequirementsInput(
    val requestSummary: RequestSummary,
    val evidence: StreamEvidence,
    val profile: SessionProfile,
    val effectivePreferences: PlaybackPreferences,
    val environment: PlaybackEnvironmentSnapshot,
)

fun interface PlaybackRequirementsResolver {
    suspend fun resolve(input: PlaybackRequirementsInput): PlaybackResult<PlaybackRequirements>
}

data class PlaybackGraphInput(
    val requirements: PlaybackRequirements,
    val evidence: StreamEvidence,
)

fun interface PlaybackGraphProvider {
    suspend fun candidates(input: PlaybackGraphInput): PlaybackResult<List<PlaybackGraph>>
}

data class PlaybackEngineStart(
    val generation: Long,
    val request: PlaybackRequest,
    val evidence: StreamEvidence,
    val graph: PlaybackGraph,
    val requirements: PlaybackRequirements,
    val startPaused: Boolean,
    /** Applied before playback starts so VOD rebuilds never flash from position zero. */
    val startPositionMs: Long = 0,
    val playbackRate: Float = 1f,
    val restorationCheckpoint: VodRestorationCheckpoint? = null,
)

/**
 * Generation-bound engine facts for the common playback watchdog. A null counter means the
 * engine cannot expose that fact; rendered video frames remain the shared progress authority.
 */
data class PlaybackEngineMetricsSnapshot(
    val generation: Long,
    val videoFramesRendered: Long?,
    val videoFramesSkipped: Long?,
    val videoFramesDropped: Long?,
    val audioBuffersRendered: Long?,
    val audioBuffersSkipped: Long?,
    val audioBuffersDropped: Long?,
)

/**
 * One adapter instance owns one engine, its surface attachment, listeners, and event translation.
 * It reports facts and executes commands; it never retries, refreshes links, or switches engines.
 */
interface PlaybackEngine {
    val type: EngineType
    val events: Flow<PlaybackEvent>

    suspend fun attachSurface(generation: Long, graph: PlaybackGraph): PlaybackResult<Unit>
    suspend fun detachSurface(generation: Long): PlaybackResult<Unit>
    suspend fun start(input: PlaybackEngineStart): PlaybackResult<Unit>
    suspend fun setPaused(generation: Long, paused: Boolean): PlaybackResult<Unit>
    suspend fun setVolume(generation: Long, volume: Float): PlaybackResult<Unit> =
        unsupportedPlaybackControl()
    suspend fun seekTo(generation: Long, positionMs: Long): PlaybackResult<Unit> =
        unsupportedPlaybackControl()
    suspend fun setPlaybackRate(generation: Long, rate: Float): PlaybackResult<Unit> =
        unsupportedPlaybackControl()
    suspend fun selectAudioTrack(generation: Long, trackId: PlaybackTrackId): PlaybackResult<Unit> =
        unsupportedPlaybackControl()
    suspend fun selectSubtitleTrack(generation: Long, trackId: PlaybackTrackId): PlaybackResult<Unit> =
        unsupportedPlaybackControl()
    suspend fun setSubtitlesEnabled(generation: Long, enabled: Boolean): PlaybackResult<Unit> =
        unsupportedPlaybackControl()
    suspend fun attachExternalSubtitle(
        generation: Long,
        subtitleId: ExternalSubtitleId,
    ): PlaybackResult<Unit> = unsupportedPlaybackControl()
    suspend fun applyRequirements(
        generation: Long,
        requirements: PlaybackRequirements,
    ): PlaybackResult<Unit>

    /** Reads continuing decoder progress without changing playback or owning watchdog policy. */
    suspend fun snapshotMetrics(generation: Long): PlaybackResult<PlaybackEngineMetricsSnapshot>

    /**
     * Stops only the generation-owned provider/media source while retaining a healthy compatible
     * engine core and surface. Success is affirmative proof that the old provider connection ended.
     */
    suspend fun releaseSource(generation: Long): PlaybackResult<Unit> = release(generation)

    /** Returns only after the adapter has stopped using its provider connection and surface. */
    suspend fun release(generation: Long): PlaybackResult<Unit>

    /**
     * Forcefully terminates adapter-owned network, renderer, listener, and surface resources.
     * Success is affirmative proof that provider and surface ownership have ended; a timeout is
     * never translated to success. Implementations must be idempotent.
     */
    suspend fun hardAbort(generation: Long): PlaybackResult<Unit>
}

enum class AudioFocusEvent { GAIN, LOSS_TRANSIENT, LOSS_DUCK, LOSS_PERMANENT }

interface AudioFocusPort {
    fun events(): Flow<AudioFocusEvent>
    suspend fun acquire(): PlaybackResult<Unit>
    suspend fun abandon(): PlaybackResult<Unit>
}

object NoopAudioFocusPort : AudioFocusPort {
    override fun events(): Flow<AudioFocusEvent> = emptyFlow()
    override suspend fun acquire(): PlaybackResult<Unit> = PlaybackResult.Success(Unit)
    override suspend fun abandon(): PlaybackResult<Unit> = PlaybackResult.Success(Unit)
}

private fun unsupportedPlaybackControl(): PlaybackResult.Failure = PlaybackResult.Failure(
    PlaybackFailure(
        code = FailureCode.NO_ELIGIBLE_GRAPH,
        domain = FailureDomain.DEVICE_RESOURCE,
        phase = FailurePhase.PLAYBACK,
        retryability = Retryability.FATAL,
        deterministic = true,
    ),
)

fun interface PlaybackEngineRegistry {
    fun engine(type: EngineType): PlaybackEngine?
}

interface PlaybackCompatibilityHistory {
    suspend fun records(scopeKey: CompatibilityScopeKey): List<CompatibilityRecord>
    suspend fun record(value: CompatibilityRecord)
}

/** Stable recording inputs owned by application composition, not an engine or playback screen. */
data class CompatibilityRecordingEnvironment(
    val history: PlaybackCompatibilityHistory,
    val runtime: CompatibilityRuntimeFingerprint,
    val appVersion: String,
    val engineVersions: Map<EngineType, String>,
    val successTtlMs: Long,
    val fatalTtlMs: Long,
) {
    init {
        require(appVersion.isNotBlank()) { "Compatibility app version must not be blank" }
        require(engineVersions.values.all(String::isNotBlank)) {
            "Compatibility engine versions must not be blank"
        }
        require(successTtlMs > 0) { "Compatibility success TTL must be positive" }
        require(fatalTtlMs > 0) { "Compatibility fatal TTL must be positive" }
    }
}

interface PlaybackClock {
    fun nowEpochMs(): Long
    suspend fun delayMs(durationMs: Long)
}

enum class PlaybackDiagnosticCode {
    REQUEST_RESOLUTION_STARTED,
    REQUEST_RESOLVED,
    GRAPH_SELECTED,
    ENGINE_OPERATION_FAILED,
    RELEASE_BARRIER_STARTED,
    RELEASE_BARRIER_COMPLETED,
    LIVE_RECONNECT_ATTEMPT,
    LIVE_RECONNECT_SUCCEEDED,
    REQUIREMENTS_CHANGE_REJECTED,
    WATCHDOG_EXPIRED,
    COMPATIBILITY_HISTORY_RECORD_FAILED,
    PLAYBACK_OUTPUT_NONFATAL,
}

data class PlaybackDiagnosticEvent(
    val generation: Long,
    val code: PlaybackDiagnosticCode,
    val engine: EngineType? = null,
    val failure: PlaybackFailure? = null,
    val attempt: Int? = null,
    val outputStatus: PlaybackOutputStatus? = null,
)

fun interface PlaybackDiagnostics {
    fun record(event: PlaybackDiagnosticEvent)
}

/**
 * Audio/display changes are applied by Android implementations after requirements are resolved.
 * Implementations must reject a lower [VideoOutputFacts.revision] for the same generation so a
 * late coroutine cannot restore stale display facts. Optional output limitations and operation
 * faults are successful typed applications; [PlaybackResult.Failure] is reserved for a hard
 * correctness or security constraint that makes playback unsafe.
 */
interface PlaybackOutputController {
    suspend fun apply(request: PlaybackOutputRequest): PlaybackResult<PlaybackOutputApplication>

    suspend fun reset(
        releasedGeneration: Long?,
        reason: ActiveWorkReleaseReason,
    ): PlaybackResult<Unit>
}

enum class PlaybackLifecycleEvent { ACTIVE, INACTIVE, DESTROYED }

fun interface PlaybackLifecyclePort {
    fun events(): Flow<PlaybackLifecycleEvent>
}
