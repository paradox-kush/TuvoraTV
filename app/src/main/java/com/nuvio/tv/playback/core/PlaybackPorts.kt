package com.nuvio.tv.playback.core

import kotlinx.coroutines.flow.Flow

/** A failure is already normalized before it crosses a core port; raw engine exceptions stay local. */
sealed interface PlaybackResult<out T> {
    data class Success<T>(val value: T) : PlaybackResult<T>
    data class Failure(val failure: PlaybackFailure) : PlaybackResult<Nothing>
}

data class ResolvedPlaybackRequest(
    val request: PlaybackRequest,
    val summary: RequestSummary,
    val evidence: StreamEvidence,
)

fun interface PlaybackRequestResolver {
    suspend fun resolve(request: PlaybackRequest): PlaybackResult<ResolvedPlaybackRequest>
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

fun interface PlaybackEnvironmentProvider {
    suspend fun snapshot(
        requestSummary: RequestSummary,
        evidence: StreamEvidence,
        profile: SessionProfile,
        effectivePreferences: PlaybackPreferences,
    ): PlaybackResult<PlaybackEnvironmentSnapshot>
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
    suspend fun applyRequirements(
        generation: Long,
        requirements: PlaybackRequirements,
    ): PlaybackResult<Unit>

    /** Reads continuing decoder progress without changing playback or owning watchdog policy. */
    suspend fun snapshotMetrics(generation: Long): PlaybackResult<PlaybackEngineMetricsSnapshot>

    /** Returns only after the adapter has stopped using its provider connection and surface. */
    suspend fun release(generation: Long): PlaybackResult<Unit>

    /**
     * Forcefully terminates adapter-owned network, renderer, listener, and surface resources.
     * Success is affirmative proof that provider and surface ownership have ended; a timeout is
     * never translated to success. Implementations must be idempotent.
     */
    suspend fun hardAbort(generation: Long): PlaybackResult<Unit>
}

fun interface PlaybackEngineRegistry {
    fun engine(type: EngineType): PlaybackEngine?
}

interface PlaybackCompatibilityHistory {
    suspend fun records(scopeKey: CompatibilityScopeKey): List<CompatibilityRecord>
    suspend fun record(value: CompatibilityRecord)
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
}

data class PlaybackDiagnosticEvent(
    val generation: Long,
    val code: PlaybackDiagnosticCode,
    val engine: EngineType? = null,
    val failure: PlaybackFailure? = null,
    val attempt: Int? = null,
)

fun interface PlaybackDiagnostics {
    fun record(event: PlaybackDiagnosticEvent)
}

/** Audio/display changes are applied by Android implementations after requirements are resolved. */
interface PlaybackOutputController {
    suspend fun apply(
        generation: Long,
        requirements: PlaybackRequirements,
    ): PlaybackResult<Unit>

    suspend fun reset(generation: Long): PlaybackResult<Unit>
}

enum class PlaybackLifecycleEvent { ACTIVE, INACTIVE, DESTROYED }

fun interface PlaybackLifecyclePort {
    fun events(): Flow<PlaybackLifecycleEvent>
}
