package com.nuvio.tv.playback.media3

import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.GraphOutputProfile
import com.nuvio.tv.playback.core.PlaybackEndReason
import com.nuvio.tv.playback.core.PlaybackEngine
import com.nuvio.tv.playback.core.PlaybackEngineState
import com.nuvio.tv.playback.core.PlaybackEngineMetricsSnapshot
import com.nuvio.tv.playback.core.PlaybackEngineStart
import com.nuvio.tv.playback.core.PlaybackEvent
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackRequirements
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.PlaybackTimelineFacts
import com.nuvio.tv.playback.core.PlaybackTrackCatalog
import com.nuvio.tv.playback.core.PlaybackTrackId
import com.nuvio.tv.playback.core.ExternalSubtitleId
import com.nuvio.tv.playback.core.VodRestorationCheckpoint
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.playback.core.SurfaceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface Media3BackendEvent {
    data object BytesReceived : Media3BackendEvent
    data class TracksAvailable(
        val hasVideo: Boolean,
        val audioTrackCount: Int,
        val subtitleTrackCount: Int,
    ) : Media3BackendEvent
    data class TimelineUpdated(val facts: PlaybackTimelineFacts) : Media3BackendEvent
    data class TrackCatalogUpdated(val catalog: PlaybackTrackCatalog) : Media3BackendEvent
    data class PlaybackRateChanged(val rate: Float) : Media3BackendEvent
    data object FirstAudio : Media3BackendEvent
    data object FirstVideoFrame : Media3BackendEvent
    data object BufferingStarted : Media3BackendEvent
    data object BufferingEnded : Media3BackendEvent
    data class StateObserved(
        val state: PlaybackEngineState,
        val playWhenReady: Boolean,
        val isLoading: Boolean,
    ) : Media3BackendEvent
    data class VideoDecoderInitialized(val decoderName: String) : Media3BackendEvent
    data class VideoInputFormatChanged(val sampleMimeType: String?) : Media3BackendEvent
    data class VideoFrameRateChanged(val frameRate: Float) : Media3BackendEvent
    data class VideoSizeChanged(val width: Int, val height: Int) : Media3BackendEvent
    data object Ended : Media3BackendEvent
    data class Failed(val failure: PlaybackFailure) : Media3BackendEvent
}

internal interface Media3Backend {
    val events: Flow<Media3BackendEvent>
    suspend fun attachSurface(lease: Media3SurfaceLease): PlaybackResult<Unit>
    suspend fun start(
        paused: Boolean,
        startPositionMs: Long,
        playbackRate: Float,
        restorationCheckpoint: VodRestorationCheckpoint?,
    ): PlaybackResult<Unit>
    suspend fun setPaused(paused: Boolean): PlaybackResult<Unit>
    suspend fun setVolume(volume: Float): PlaybackResult<Unit>
    suspend fun seekTo(positionMs: Long): PlaybackResult<Unit> = unsupportedMedia3Control()
    suspend fun setPlaybackRate(rate: Float): PlaybackResult<Unit> = unsupportedMedia3Control()
    suspend fun selectAudioTrack(trackId: PlaybackTrackId): PlaybackResult<Unit> = unsupportedMedia3Control()
    suspend fun selectSubtitleTrack(trackId: PlaybackTrackId): PlaybackResult<Unit> = unsupportedMedia3Control()
    suspend fun setSubtitlesEnabled(enabled: Boolean): PlaybackResult<Unit> = unsupportedMedia3Control()
    suspend fun attachExternalSubtitle(subtitleId: ExternalSubtitleId): PlaybackResult<Unit> =
        unsupportedMedia3Control()
    suspend fun apply(plan: Media3AdapterPlan): PlaybackResult<Unit>
    suspend fun detachSurface(): PlaybackResult<Unit>
    suspend fun stopSource(): PlaybackResult<Unit>
    suspend fun replaceSource(
        plan: Media3AdapterPlan,
        paused: Boolean,
        startPositionMs: Long,
        playbackRate: Float,
        restorationCheckpoint: VodRestorationCheckpoint?,
    ): PlaybackResult<Unit>

    /** Includes tracked HTTP-call cancellation and returns only on affirmative ownership release. */
    suspend fun release(): PlaybackResult<Unit>
    suspend fun hardAbort(): PlaybackResult<Unit>
    suspend fun metrics(): PlaybackResult<Media3DecoderMetrics>
}

private fun unsupportedMedia3Control(): PlaybackResult.Failure = PlaybackResult.Failure(
    PlaybackFailure(
        code = FailureCode.NO_ELIGIBLE_GRAPH,
        domain = FailureDomain.DEVICE_RESOURCE,
        phase = FailurePhase.PLAYBACK,
        retryability = Retryability.FATAL,
        deterministic = true,
    ),
)

internal data class Media3DecoderMetrics(
    val videoRendered: Int,
    val videoSkipped: Int,
    val videoDropped: Int,
    val audioRendered: Int,
    val audioSkipped: Int,
    val audioDropped: Int,
)

internal fun interface Media3BackendFactory {
    suspend fun create(plan: Media3AdapterPlan): PlaybackResult<Media3Backend>
}

/**
 * Facts-only Media3 adapter. It never retries, refreshes links, mutates settings, learns history,
 * or chooses another graph. ENDED stays a factual event; live/VOD interpretation belongs to the
 * serialized session.
 */
class Media3Engine internal constructor(
    private val scope: CoroutineScope,
    private val surfaceHost: Media3SurfaceHost,
    private val backendFactory: Media3BackendFactory,
) : PlaybackEngine {
    override val type: EngineType = EngineType.MEDIA3

    private val lock = Mutex()
    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
    override val events: Flow<PlaybackEvent> = _events.asSharedFlow()

    @Volatile private var generation: Long? = null
    private var lease: Media3SurfaceLease? = null
    private var backend: Media3Backend? = null
    private var eventJob: Job? = null
    private var activeGraph: com.nuvio.tv.playback.core.PlaybackGraph? = null
    private var activeEvidence: com.nuvio.tv.playback.core.StreamEvidence? = null
    private var sourceReleased = false

    override suspend fun attachSurface(
        generation: Long,
        graph: com.nuvio.tv.playback.core.PlaybackGraph,
    ): PlaybackResult<Unit> = lock.withLock {
        if (!graph.isStructurallyValid() || graph.engine != EngineType.MEDIA3 ||
            graph.outputProfile != GraphOutputProfile.MEDIA3_STANDARD ||
            graph.surfaceMode !in setOf(SurfaceMode.SURFACE_VIEW, SurfaceMode.TEXTURE_VIEW)
        ) {
            return@withLock failure(FailurePhase.SURFACE_ATTACHMENT, FailureCode.SURFACE_LOST)
        }
        if (this.generation != null && this.generation != generation) {
            return@withLock failure(FailurePhase.SURFACE_ATTACHMENT, FailureCode.RESOURCE_BUDGET_EXCEEDED)
        }
        val currentBackend = backend
        if (currentBackend != null && activeGraph != graph) {
            return@withLock failure(FailurePhase.SURFACE_ATTACHMENT, FailureCode.RESOURCE_BUDGET_EXCEEDED)
        }
        if (lease != null) {
            this.generation = generation
            return@withLock PlaybackResult.Success(Unit)
        }
        when (val acquired = surfaceHost.acquire(graph.surfaceMode, graph.secureOutput)) {
            is PlaybackResult.Success -> {
                if (acquired.value.mode != graph.surfaceMode || acquired.value.secure != graph.secureOutput) {
                    acquired.value.release()
                    failure(FailurePhase.SURFACE_ATTACHMENT, FailureCode.SURFACE_LOST)
                } else {
                    if (currentBackend != null) {
                        when (val attached = currentBackend.attachSurface(acquired.value)) {
                            is PlaybackResult.Failure -> {
                                // If a backend failed after taking partial ownership, retain the
                                // lease unless its release is affirmative. This keeps later release
                                // and retry decisions fail-closed.
                                if (!acquired.value.release()) lease = acquired.value
                                return@withLock attached
                            }
                            is PlaybackResult.Success -> Unit
                        }
                    }
                    this.generation = generation
                    lease = acquired.value
                    PlaybackResult.Success(Unit)
                }
            }
            is PlaybackResult.Failure -> acquired
        }
    }

    override suspend fun detachSurface(generation: Long): PlaybackResult<Unit> = lock.withLock {
        if (this.generation != generation) return@withLock stale(FailurePhase.SURFACE_ATTACHMENT)
        val currentBackend = backend
        if (currentBackend != null) {
            val detached = currentBackend.detachSurface()
            if (detached is PlaybackResult.Failure) return@withLock detached
        }
        val currentLease = lease ?: return@withLock PlaybackResult.Success(Unit)
        if (!currentLease.release()) return@withLock failure(FailurePhase.RELEASE, FailureCode.SURFACE_LOST)
        lease = null
        PlaybackResult.Success(Unit)
    }

    override suspend fun start(input: PlaybackEngineStart): PlaybackResult<Unit> = lock.withLock {
        if (input.graph.engine != EngineType.MEDIA3 || input.graph.outputProfile != GraphOutputProfile.MEDIA3_STANDARD) {
            return@withLock failure(FailurePhase.ENGINE_START, FailureCode.NO_ELIGIBLE_GRAPH)
        }
        if (generation != input.generation) return@withLock stale(FailurePhase.ENGINE_START)
        val currentLease = lease ?: return@withLock failure(FailurePhase.SURFACE_ATTACHMENT, FailureCode.SURFACE_LOST)
        val plan = Media3AdapterPlanFactory.create(input.request, input.evidence, input.graph, input.requirements)
        val retained = backend
        if (retained != null) {
            if (!sourceReleased || activeGraph != input.graph) {
                return@withLock failure(FailurePhase.ENGINE_START, FailureCode.RESOURCE_BUDGET_EXCEEDED)
            }
            eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                retained.events.collect { event -> publish(input.generation, event) }
            }
            val replaced = retained.replaceSource(
                plan,
                input.startPaused,
                input.startPositionMs,
                input.playbackRate,
                input.restorationCheckpoint,
            )
            if (replaced is PlaybackResult.Success) {
                sourceReleased = false
                activeRequest = input.request
                activeEvidence = input.evidence
            } else {
                eventJob?.cancelAndJoin()
                eventJob = null
            }
            return@withLock replaced
        }
        when (val created = backendFactory.create(plan)) {
            is PlaybackResult.Failure -> created
            is PlaybackResult.Success -> {
                val candidate = created.value
                val attached = candidate.attachSurface(currentLease)
                if (attached is PlaybackResult.Failure) {
                    return@withLock when (val released = candidate.release()) {
                        is PlaybackResult.Success -> attached
                        is PlaybackResult.Failure -> {
                            backend = candidate
                            released
                        }
                    }
                }
                backend = candidate
                activeRequest = input.request
                activeGraph = input.graph
                activeEvidence = input.evidence
                sourceReleased = false
                // Subscribe before prepare/play can synchronously publish a terminal or failure fact.
                eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    candidate.events.collect { event -> publish(input.generation, event) }
                }
                when (
                    val started = candidate.start(
                        input.startPaused,
                        input.startPositionMs,
                        input.playbackRate,
                        input.restorationCheckpoint,
                    )
                ) {
                    is PlaybackResult.Success -> started
                    is PlaybackResult.Failure -> {
                        eventJob?.cancelAndJoin()
                        eventJob = null
                        when (val released = candidate.release()) {
                            is PlaybackResult.Failure -> released
                            is PlaybackResult.Success -> {
                                backend = null
                                activeRequest = null
                                activeGraph = null
                                activeEvidence = null
                                started
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun setPaused(generation: Long, paused: Boolean): PlaybackResult<Unit> = lock.withLock {
        if (this.generation != generation) return@withLock stale(FailurePhase.PLAYBACK)
        backend?.setPaused(paused) ?: failure(FailurePhase.PLAYBACK, FailureCode.UNKNOWN)
    }

    override suspend fun setVolume(generation: Long, volume: Float): PlaybackResult<Unit> = lock.withLock {
        if (this.generation != generation) return@withLock stale(FailurePhase.PLAYBACK)
        backend?.setVolume(volume.coerceIn(0f, 1f))
            ?: failure(FailurePhase.PLAYBACK, FailureCode.UNKNOWN)
    }

    override suspend fun seekTo(generation: Long, positionMs: Long): PlaybackResult<Unit> = lock.withLock {
        if (this.generation != generation) return@withLock stale(FailurePhase.PLAYBACK)
        backend?.seekTo(positionMs) ?: failure(FailurePhase.PLAYBACK, FailureCode.UNKNOWN)
    }

    override suspend fun setPlaybackRate(generation: Long, rate: Float): PlaybackResult<Unit> = lock.withLock {
        if (this.generation != generation) return@withLock stale(FailurePhase.PLAYBACK)
        backend?.setPlaybackRate(rate) ?: failure(FailurePhase.PLAYBACK, FailureCode.UNKNOWN)
    }

    override suspend fun selectAudioTrack(
        generation: Long,
        trackId: PlaybackTrackId,
    ): PlaybackResult<Unit> = lock.withLock {
        if (this.generation != generation) return@withLock stale(FailurePhase.PLAYBACK)
        backend?.selectAudioTrack(trackId) ?: failure(FailurePhase.PLAYBACK, FailureCode.UNKNOWN)
    }

    override suspend fun selectSubtitleTrack(
        generation: Long,
        trackId: PlaybackTrackId,
    ): PlaybackResult<Unit> = lock.withLock {
        if (this.generation != generation) return@withLock stale(FailurePhase.PLAYBACK)
        backend?.selectSubtitleTrack(trackId) ?: failure(FailurePhase.PLAYBACK, FailureCode.UNKNOWN)
    }

    override suspend fun setSubtitlesEnabled(
        generation: Long,
        enabled: Boolean,
    ): PlaybackResult<Unit> = lock.withLock {
        if (this.generation != generation) return@withLock stale(FailurePhase.PLAYBACK)
        backend?.setSubtitlesEnabled(enabled) ?: failure(FailurePhase.PLAYBACK, FailureCode.UNKNOWN)
    }

    override suspend fun attachExternalSubtitle(
        generation: Long,
        subtitleId: ExternalSubtitleId,
    ): PlaybackResult<Unit> = lock.withLock {
        if (this.generation != generation) return@withLock stale(FailurePhase.PLAYBACK)
        backend?.attachExternalSubtitle(subtitleId) ?: failure(FailurePhase.PLAYBACK, FailureCode.UNKNOWN)
    }

    override suspend fun applyRequirements(
        generation: Long,
        requirements: PlaybackRequirements,
    ): PlaybackResult<Unit> = lock.withLock {
        if (this.generation != generation) return@withLock stale(FailurePhase.PLAYBACK)
        val current = backend ?: return@withLock failure(FailurePhase.PLAYBACK, FailureCode.UNKNOWN)
        // Request/network/source construction is unchanged for an in-place requirements update.
        return@withLock current.applyRequirementsOnly(requirements)
    }

    private suspend fun Media3Backend.applyRequirementsOnly(
        requirements: PlaybackRequirements,
    ): PlaybackResult<Unit> {
        // The backend applies only fields classified in-place by the core. Network/source values are
        // deliberately retained from its immutable construction plan.
        return apply(
            Media3AdapterPlanFactory.create(
                requireNotNull(activeRequest),
                requireNotNull(activeEvidence),
                requireNotNull(activeGraph),
                requirements,
            ),
        )
    }

    private var activeRequest: com.nuvio.tv.playback.core.PlaybackRequest? = null

    override suspend fun release(generation: Long): PlaybackResult<Unit> = lock.withLock {
        val currentGeneration = this.generation
        if (currentGeneration != null && currentGeneration != generation) {
            return@withLock stale(FailurePhase.RELEASE)
        }
        val current = backend
        if (current != null) {
            when (val released = current.release()) {
                is PlaybackResult.Failure -> return@withLock released
                is PlaybackResult.Success -> Unit
            }
        }
        eventJob?.cancelAndJoin()
        eventJob = null
        backend = null
        val currentLease = lease
        if (currentLease != null && !currentLease.release()) {
            return@withLock failure(FailurePhase.RELEASE, FailureCode.SURFACE_LOST)
        }
        lease = null
        this.generation = null
        activeRequest = null
        activeGraph = null
        activeEvidence = null
        sourceReleased = false
        PlaybackResult.Success(Unit)
    }

    override suspend fun releaseSource(generation: Long): PlaybackResult<Unit> = lock.withLock {
        if (this.generation != generation) return@withLock stale(FailurePhase.RELEASE)
        val current = backend ?: return@withLock PlaybackResult.Success(Unit)
        when (val stopped = current.stopSource()) {
            is PlaybackResult.Failure -> stopped
            is PlaybackResult.Success -> {
                eventJob?.cancelAndJoin()
                eventJob = null
                sourceReleased = true
                this.generation = null
                PlaybackResult.Success(Unit)
            }
        }
    }

    override suspend fun hardAbort(generation: Long): PlaybackResult<Unit> = lock.withLock {
        val currentGeneration = this.generation
        if (currentGeneration != null && currentGeneration != generation) {
            return@withLock stale(FailurePhase.RELEASE)
        }
        val current = backend
        if (current != null) {
            when (val aborted = current.hardAbort()) {
                is PlaybackResult.Failure -> return@withLock aborted
                is PlaybackResult.Success -> Unit
            }
        }
        eventJob?.cancelAndJoin()
        eventJob = null
        backend = null
        val currentLease = lease
        if (currentLease != null && !currentLease.release()) {
            return@withLock failure(FailurePhase.RELEASE, FailureCode.SURFACE_LOST)
        }
        lease = null
        this.generation = null
        activeRequest = null
        activeGraph = null
        activeEvidence = null
        sourceReleased = false
        PlaybackResult.Success(Unit)
    }

    override suspend fun snapshotMetrics(
        generation: Long,
    ): PlaybackResult<PlaybackEngineMetricsSnapshot> = lock.withLock {
        if (this.generation != generation) return@withLock stale(FailurePhase.PLAYBACK)
        val current = backend ?: return@withLock failure(FailurePhase.PLAYBACK, FailureCode.UNKNOWN)
        when (val metrics = current.metrics()) {
            is PlaybackResult.Failure -> metrics
            is PlaybackResult.Success -> PlaybackResult.Success(
                PlaybackEngineMetricsSnapshot(
                    generation = generation,
                    videoFramesRendered = metrics.value.videoRendered.toLong(),
                    videoFramesSkipped = metrics.value.videoSkipped.toLong(),
                    videoFramesDropped = metrics.value.videoDropped.toLong(),
                    audioBuffersRendered = metrics.value.audioRendered.toLong(),
                    audioBuffersSkipped = metrics.value.audioSkipped.toLong(),
                    audioBuffersDropped = metrics.value.audioDropped.toLong(),
                ),
            )
        }
    }

    private fun publish(generation: Long, event: Media3BackendEvent) {
        if (this.generation != generation) return
        val normalized = when (event) {
            Media3BackendEvent.BytesReceived -> PlaybackEvent.BytesReceived(generation)
            is Media3BackendEvent.TracksAvailable -> PlaybackEvent.TracksAvailable(
                generation,
                event.hasVideo,
                event.audioTrackCount,
                event.subtitleTrackCount,
            )
            is Media3BackendEvent.TimelineUpdated -> PlaybackEvent.TimelineUpdated(generation, event.facts)
            is Media3BackendEvent.TrackCatalogUpdated -> PlaybackEvent.TrackCatalogUpdated(generation, event.catalog)
            is Media3BackendEvent.PlaybackRateChanged -> PlaybackEvent.PlaybackRateChanged(generation, event.rate)
            Media3BackendEvent.FirstAudio -> PlaybackEvent.FirstAudio(generation)
            Media3BackendEvent.FirstVideoFrame -> PlaybackEvent.FirstVideoFrame(generation)
            Media3BackendEvent.BufferingStarted -> PlaybackEvent.BufferingStarted(generation)
            Media3BackendEvent.BufferingEnded -> PlaybackEvent.BufferingEnded(generation)
            is Media3BackendEvent.StateObserved -> PlaybackEvent.EngineStateObserved(
                generation,
                event.state,
                event.playWhenReady,
                event.isLoading,
            )
            is Media3BackendEvent.VideoDecoderInitialized ->
                PlaybackEvent.VideoDecoderInitialized(generation, event.decoderName)
            is Media3BackendEvent.VideoInputFormatChanged ->
                PlaybackEvent.VideoInputFormatChanged(generation, event.sampleMimeType)
            is Media3BackendEvent.VideoFrameRateChanged ->
                PlaybackEvent.VideoFrameRateChanged(generation, event.frameRate)
            is Media3BackendEvent.VideoSizeChanged ->
                PlaybackEvent.VideoSizeChanged(generation, event.width, event.height)
            Media3BackendEvent.Ended -> PlaybackEvent.PlaybackEnded(generation, PlaybackEndReason.EOF)
            is Media3BackendEvent.Failed -> PlaybackEvent.Failed(generation, event.failure)
        }
        _events.tryEmit(normalized)
    }

    private fun stale(phase: FailurePhase): PlaybackResult.Failure = failure(phase, FailureCode.UNKNOWN)

    private fun failure(phase: FailurePhase, code: FailureCode): PlaybackResult.Failure = PlaybackResult.Failure(
        PlaybackFailure(
            code = code,
            domain = if (code == FailureCode.SURFACE_LOST) {
                FailureDomain.VIDEO_RENDERER_SURFACE
            } else {
                FailureDomain.DEVICE_RESOURCE
            },
            phase = phase,
            retryability = Retryability.HANDOFF_ELIGIBLE,
        ),
    )
}
