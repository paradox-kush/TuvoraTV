package com.nuvio.tv.playback.mpv

import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackEngine
import com.nuvio.tv.playback.core.PlaybackEngineMetricsSnapshot
import com.nuvio.tv.playback.core.PlaybackEngineStart
import com.nuvio.tv.playback.core.PlaybackEvent
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackGraph
import com.nuvio.tv.playback.core.PlaybackRequirements
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.Retryability
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

/** Facts-only, generation-bound libmpv adapter. Recovery and handoff remain session policy. */
class MpvEngine internal constructor(
    private val scope: CoroutineScope,
    private val surfaceHost: MpvSurfaceHost,
    private val backendFactory: MpvBackendFactory,
) : PlaybackEngine {
    override val type: EngineType = EngineType.LIBMPV
    private val lock = Mutex()
    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
    override val events: Flow<PlaybackEvent> = _events.asSharedFlow()

    @Volatile private var generation: Long? = null
    private var lease: MpvSurfaceLease? = null
    private var backend: MpvBackend? = null
    private var eventJob: Job? = null
    private var activeStart: PlaybackEngineStart? = null

    override suspend fun attachSurface(generation: Long, graph: PlaybackGraph): PlaybackResult<Unit> = lock.withLock {
        if (!graph.isStructurallyValid() || graph.engine != EngineType.LIBMPV) {
            return@withLock failure(FailurePhase.SURFACE_ATTACHMENT, FailureCode.SURFACE_LOST)
        }
        if (backend != null || (this.generation != null && this.generation != generation)) {
            return@withLock failure(FailurePhase.SURFACE_ATTACHMENT, FailureCode.RESOURCE_BUDGET_EXCEEDED)
        }
        if (lease != null) return@withLock PlaybackResult.Success(Unit)
        when (val acquired = surfaceHost.acquire(graph.surfaceMode, graph.secureOutput)) {
            is PlaybackResult.Failure -> acquired
            is PlaybackResult.Success -> {
                if (acquired.value.mode != graph.surfaceMode || acquired.value.secure != graph.secureOutput) {
                    acquired.value.release()
                    failure(FailurePhase.SURFACE_ATTACHMENT, FailureCode.SURFACE_LOST)
                } else {
                    this.generation = generation
                    lease = acquired.value
                    PlaybackResult.Success(Unit)
                }
            }
        }
    }

    override suspend fun detachSurface(generation: Long): PlaybackResult<Unit> = lock.withLock {
        if (this.generation != generation) return@withLock stale(FailurePhase.SURFACE_ATTACHMENT)
        backend?.let {
            val detached = it.detachSurface()
            if (detached is PlaybackResult.Failure) return@withLock detached
        }
        val current = lease ?: return@withLock PlaybackResult.Success(Unit)
        if (!current.release()) return@withLock failure(FailurePhase.RELEASE, FailureCode.SURFACE_LOST)
        lease = null
        PlaybackResult.Success(Unit)
    }

    override suspend fun start(input: PlaybackEngineStart): PlaybackResult<Unit> = lock.withLock {
        if (generation != input.generation) return@withLock stale(FailurePhase.ENGINE_START)
        if (backend != null) return@withLock failure(FailurePhase.ENGINE_START, FailureCode.RESOURCE_BUDGET_EXCEEDED)
        val currentLease = lease ?: return@withLock failure(FailurePhase.SURFACE_ATTACHMENT, FailureCode.SURFACE_LOST)
        val plan = when (val planned = MpvAdapterPlanFactory.create(input)) {
            is PlaybackResult.Failure -> return@withLock planned
            is PlaybackResult.Success -> planned.value
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
                activeStart = input
                eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    candidate.events.collect { publish(input.generation, it) }
                }
                when (val started = candidate.start()) {
                    is PlaybackResult.Success -> started
                    is PlaybackResult.Failure -> {
                        eventJob?.cancelAndJoin()
                        eventJob = null
                        when (val released = candidate.release()) {
                            is PlaybackResult.Failure -> released
                            is PlaybackResult.Success -> {
                                backend = null
                                activeStart = null
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

    override suspend fun applyRequirements(
        generation: Long,
        requirements: PlaybackRequirements,
    ): PlaybackResult<Unit> = lock.withLock {
        if (this.generation != generation) return@withLock stale(FailurePhase.PLAYBACK)
        val current = backend ?: return@withLock failure(FailurePhase.PLAYBACK, FailureCode.UNKNOWN)
        val previous = activeStart ?: return@withLock failure(FailurePhase.PLAYBACK, FailureCode.UNKNOWN)
        val updated = previous.copy(requirements = requirements)
        val plan = when (val planned = MpvAdapterPlanFactory.create(updated)) {
            is PlaybackResult.Failure -> return@withLock planned
            is PlaybackResult.Success -> planned.value
        }
        when (val applied = current.apply(plan)) {
            is PlaybackResult.Success -> {
                activeStart = updated
                applied
            }
            is PlaybackResult.Failure -> applied
        }
    }

    override suspend fun snapshotMetrics(generation: Long): PlaybackResult<PlaybackEngineMetricsSnapshot> =
        lock.withLock {
            if (this.generation != generation) return@withLock stale(FailurePhase.PLAYBACK)
            val current = backend ?: return@withLock failure(FailurePhase.PLAYBACK, FailureCode.UNKNOWN)
            when (val metrics = current.metrics()) {
                is PlaybackResult.Failure -> metrics
                is PlaybackResult.Success -> PlaybackResult.Success(
                    PlaybackEngineMetricsSnapshot(
                        generation = generation,
                        videoFramesRendered = metrics.value.videoRendered,
                        videoFramesSkipped = metrics.value.videoSkipped,
                        videoFramesDropped = metrics.value.videoDropped,
                        audioBuffersRendered = null,
                        audioBuffersSkipped = null,
                        audioBuffersDropped = null,
                    ),
                )
            }
        }

    override suspend fun release(generation: Long): PlaybackResult<Unit> = finish(generation, hard = false)

    override suspend fun hardAbort(generation: Long): PlaybackResult<Unit> = finish(generation, hard = true)

    private suspend fun finish(generation: Long, hard: Boolean): PlaybackResult<Unit> = lock.withLock {
        val currentGeneration = this.generation
        if (currentGeneration != null && currentGeneration != generation) return@withLock stale(FailurePhase.RELEASE)
        backend?.let { current ->
            val result = if (hard) current.hardAbort() else current.release()
            if (result is PlaybackResult.Failure) return@withLock result
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
        activeStart = null
        PlaybackResult.Success(Unit)
    }

    private fun publish(generation: Long, event: MpvBackendEvent) {
        if (this.generation != generation) return
        val normalized = when (event) {
            MpvBackendEvent.BytesReceived -> PlaybackEvent.BytesReceived(generation)
            is MpvBackendEvent.TracksAvailable -> PlaybackEvent.TracksAvailable(
                generation, event.hasVideo, event.audioTrackCount, event.subtitleTrackCount,
            )
            MpvBackendEvent.FirstAudio -> PlaybackEvent.FirstAudio(generation)
            MpvBackendEvent.FirstVideoFrame -> PlaybackEvent.FirstVideoFrame(generation)
            MpvBackendEvent.BufferingStarted -> PlaybackEvent.BufferingStarted(generation)
            MpvBackendEvent.BufferingEnded -> PlaybackEvent.BufferingEnded(generation)
            is MpvBackendEvent.StateObserved -> PlaybackEvent.EngineStateObserved(
                generation, event.state, event.playWhenReady, event.isLoading,
            )
            is MpvBackendEvent.VideoDecoderInitialized ->
                PlaybackEvent.VideoDecoderInitialized(generation, event.decoderName)
            is MpvBackendEvent.VideoSizeChanged ->
                PlaybackEvent.VideoSizeChanged(generation, event.width, event.height)
            is MpvBackendEvent.Ended -> PlaybackEvent.PlaybackEnded(generation, event.reason)
            is MpvBackendEvent.Failed -> PlaybackEvent.Failed(generation, event.failure)
        }
        _events.tryEmit(normalized)
    }

    private fun stale(phase: FailurePhase) = failure(phase, FailureCode.UNKNOWN)

    private fun failure(phase: FailurePhase, code: FailureCode) = PlaybackResult.Failure(
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
