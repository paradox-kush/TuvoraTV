package com.nuvio.tv.playback.mpv

import android.content.Context
import android.view.Surface
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackEndReason
import com.nuvio.tv.playback.core.PlaybackEngineState
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.PlaybackTimelineFacts
import com.nuvio.tv.playback.core.PlaybackTrackCatalog
import com.nuvio.tv.playback.core.PlaybackTrackDescriptor
import com.nuvio.tv.playback.core.PlaybackTrackId
import com.nuvio.tv.playback.core.PlaybackTrackType
import com.nuvio.tv.playback.core.ExternalSubtitleId
import com.nuvio.tv.playback.core.ExternalSubtitleResolver
import com.nuvio.tv.playback.core.RestorableTrackSelection
import com.nuvio.tv.playback.core.Retryability
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

internal enum class MpvBackendLifecycle {
    CREATED, INITIALIZED, ATTACHED, LOADING, PLAYING, IDLE, RELEASING, DEAD,
}

internal enum class MpvEndReason { EOF, ERROR, STOP, QUIT, REDIRECT, UNKNOWN }

internal sealed interface MpvBackendEvent {
    data object BytesReceived : MpvBackendEvent
    data class TracksAvailable(
        val hasVideo: Boolean,
        val audioTrackCount: Int,
        val subtitleTrackCount: Int,
        val videoDimensions: com.nuvio.tv.playback.core.VideoDimensions? = null,
    ) : MpvBackendEvent
    data class TimelineUpdated(val facts: PlaybackTimelineFacts) : MpvBackendEvent
    data class TrackCatalogUpdated(val catalog: PlaybackTrackCatalog) : MpvBackendEvent
    data class PlaybackRateChanged(val rate: Float) : MpvBackendEvent
    data object FirstAudio : MpvBackendEvent
    data object FirstVideoFrame : MpvBackendEvent
    data object BufferingStarted : MpvBackendEvent
    data object BufferingEnded : MpvBackendEvent
    data class StateObserved(
        val state: PlaybackEngineState,
        val playWhenReady: Boolean,
        val isLoading: Boolean,
    ) : MpvBackendEvent
    data class VideoDecoderInitialized(val decoderName: String) : MpvBackendEvent
    data class VideoInputFormatChanged(val sampleMimeType: String?) : MpvBackendEvent
    data class VideoFrameRateChanged(val frameRate: Float) : MpvBackendEvent
    data class VideoSizeChanged(val width: Int, val height: Int) : MpvBackendEvent
    data class Ended(val reason: PlaybackEndReason) : MpvBackendEvent
    data class Failed(val failure: PlaybackFailure) : MpvBackendEvent
}

internal data class MpvMetrics(
    val videoRendered: Long?,
    val videoSkipped: Long?,
    val videoDropped: Long?,
)

internal interface MpvBackend {
    val events: Flow<MpvBackendEvent>
    suspend fun attachSurface(lease: MpvSurfaceLease): PlaybackResult<Unit>
    suspend fun start(): PlaybackResult<Unit>
    suspend fun setPaused(paused: Boolean): PlaybackResult<Unit>
    suspend fun setVolume(volume: Float): PlaybackResult<Unit>
    suspend fun seekTo(positionMs: Long): PlaybackResult<Unit> = unsupportedMpvControl()
    suspend fun setPlaybackRate(rate: Float): PlaybackResult<Unit> = unsupportedMpvControl()
    suspend fun selectAudioTrack(trackId: PlaybackTrackId): PlaybackResult<Unit> = unsupportedMpvControl()
    suspend fun selectSubtitleTrack(trackId: PlaybackTrackId): PlaybackResult<Unit> = unsupportedMpvControl()
    suspend fun setSubtitlesEnabled(enabled: Boolean): PlaybackResult<Unit> = unsupportedMpvControl()
    suspend fun attachExternalSubtitle(subtitleId: ExternalSubtitleId): PlaybackResult<Unit> =
        unsupportedMpvControl()
    suspend fun apply(plan: MpvAdapterPlan): PlaybackResult<Unit>
    suspend fun detachSurface(): PlaybackResult<Unit>
    suspend fun metrics(): PlaybackResult<MpvMetrics>
    suspend fun stopSource(): PlaybackResult<Unit>
    suspend fun release(): PlaybackResult<Unit>
    suspend fun hardAbort(): PlaybackResult<Unit>
}

private fun unsupportedMpvControl(): PlaybackResult.Failure = PlaybackResult.Failure(
    PlaybackFailure(
        code = FailureCode.NO_ELIGIBLE_GRAPH,
        domain = FailureDomain.DEVICE_RESOURCE,
        phase = FailurePhase.PLAYBACK,
        retryability = Retryability.FATAL,
        deterministic = true,
    ),
)

internal fun interface MpvBackendFactory {
    suspend fun create(plan: MpvAdapterPlan): PlaybackResult<MpvBackend>
}

internal interface MpvNativeCore {
    fun create(context: Context)
    fun setOption(name: String, value: String): Boolean
    fun initialize()
    fun addObserver(observer: MpvNativeObserver)
    fun removeObserver(observer: MpvNativeObserver)
    fun observeLong(name: String)
    fun observeDouble(name: String)
    fun observeBoolean(name: String)
    fun observeString(name: String)
    fun observeNode(name: String)
    fun attachSurface(surface: Surface): Boolean
    fun detachSurfaceWithResult(): Boolean
    fun command(vararg values: String)
    fun setString(name: String, value: String)
    fun setBoolean(name: String, value: Boolean)
    fun long(name: String): Long?
    fun node(name: String): MPVNode
    fun destroyWithResult(): Boolean
    /** Independent final-termination entry used only after graceful teardown failed. */
    fun forceTerminateWithResult(): Boolean
}

internal interface MpvNativeObserver {
    fun property(name: String, value: Long) = Unit
    fun property(name: String, value: Double) = Unit
    fun property(name: String, value: Boolean) = Unit
    fun property(name: String, value: String) = Unit
    fun property(name: String, value: MPVNode) = Unit
    fun event(id: Int, data: MPVNode) = Unit
}

internal class AndroidMpvBackendFactory(
    context: Context,
    private val externalSubtitleResolver: ExternalSubtitleResolver = ExternalSubtitleResolver { null },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val coreFactory: () -> MpvNativeCore = { AndroidMpvNativeCore(MPV()) },
) : MpvBackendFactory {
    private val appContext = context.applicationContext

    override suspend fun create(plan: MpvAdapterPlan): PlaybackResult<MpvBackend> =
        PlaybackResult.Success(
            AndroidMpvBackend(appContext, plan, dispatcher, coreFactory(), externalSubtitleResolver),
        )
}

internal class AndroidMpvBackend(
    private val context: Context,
    private var plan: MpvAdapterPlan,
    private val dispatcher: CoroutineDispatcher,
    private val core: MpvNativeCore,
    private val externalSubtitleResolver: ExternalSubtitleResolver = ExternalSubtitleResolver { null },
) : MpvBackend, MpvNativeObserver {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Independent lane for forced termination; see [initiateForcedTermination]. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val terminationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val _events = MutableSharedFlow<MpvBackendEvent>(extraBufferCapacity = 64)
    override val events: Flow<MpvBackendEvent> = _events.asSharedFlow()

    @Volatile internal var lifecycle: MpvBackendLifecycle = MpvBackendLifecycle.CREATED
        private set
    private var lease: MpvSurfaceLease? = null
    private var lastStreamPosition = -1L
    private var firstVideoFrameSent = false
    private var firstAudioSent = false
    @Volatile private var terminalEventsSuppressed = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var lastVideoFrameRate: Float? = null
    private var paused = plan.startPaused
    private var positionMs = plan.startPositionMs
    private var durationMs: Long? = null
    private var bufferedDurationMs = 0L
    private var seekable = false
    private var trackRevision = 0L
    private var trackReferences: Map<PlaybackTrackId, MpvTrackReference> = emptyMap()
    private var restorationApplied = false
    private var releaseTask: kotlinx.coroutines.Deferred<PlaybackResult<Unit>>? = null
    private var forceTerminationTask: kotlinx.coroutines.Deferred<PlaybackResult<Unit>>? = null
    private var stopAcknowledgement: CompletableDeferred<Boolean>? = null
    private var manualSubtitleSelection = false
    private var appliedSubtitlesEnabled = plan.preInitOptions["sid"] != "no"

    override suspend fun attachSurface(lease: MpvSurfaceLease): PlaybackResult<Unit> =
        withContext(dispatcher) {
            if (this@AndroidMpvBackend.lease != null) {
                return@withContext stateFailure(FailurePhase.SURFACE_ATTACHMENT)
            }
            try {
                if (lifecycle == MpvBackendLifecycle.CREATED) {
                    core.create(context)
                    core.addObserver(this@AndroidMpvBackend)
                    if (plan.url.startsWith("https://", ignoreCase = true)) {
                        if (!core.setOption("tls-ca-file", ensureTlsCaFile().absolutePath)) {
                            throw IllegalStateException("Rejected mpv TLS trust option")
                        }
                    }
                    plan.preInitOptions.forEach { (name, value) ->
                        if (!core.setOption(name, value)) throw IllegalStateException("Rejected mpv option: $name")
                    }
                    core.initialize()
                    // Legacy-proven re-assert (NuvioMpvSurfaceView:135): wrapper/init layers have
                    // historically overwritten idle post-init; idle is runtime-settable and a
                    // non-idle=yes core self-terminates after `stop`. Assert it after init so the
                    // reuse lane can never inherit a dying core.
                    runCatching { core.setString("idle", "yes") }
                    observeFacts()
                    lifecycle = MpvBackendLifecycle.INITIALIZED
                } else if (lifecycle !in activeStates) {
                    return@withContext stateFailure(FailurePhase.SURFACE_ATTACHMENT)
                }
                if (!core.attachSurface(lease.surface)) throw IllegalStateException("mpv surface rejected")
                lease.markAttached()
                this@AndroidMpvBackend.lease = lease
                if (lifecycle == MpvBackendLifecycle.INITIALIZED) lifecycle = MpvBackendLifecycle.ATTACHED
                PlaybackResult.Success(Unit)
            } catch (_: Throwable) {
                stateFailure(FailurePhase.SURFACE_ATTACHMENT)
            }
        }

    override suspend fun start(): PlaybackResult<Unit> = withContext(dispatcher) {
        if (lifecycle != MpvBackendLifecycle.ATTACHED) return@withContext stateFailure(FailurePhase.ENGINE_START)
        try {
            applyRuntime(plan)
            core.setBoolean("pause", plan.startPaused)
            core.setString("speed", plan.playbackRate.toString())
            paused = plan.startPaused
            lifecycle = MpvBackendLifecycle.LOADING
            if (plan.startPositionMs > 0) {
                core.command(
                    "loadfile",
                    plan.url,
                    "replace",
                    "start=${plan.startPositionMs / 1_000.0}",
                )
            } else {
                core.command("loadfile", plan.url, "replace")
            }
            _events.tryEmit(MpvBackendEvent.PlaybackRateChanged(plan.playbackRate))
            PlaybackResult.Success(Unit)
        } catch (_: Throwable) {
            stateFailure(FailurePhase.ENGINE_START)
        }
    }

    override suspend fun setPaused(paused: Boolean): PlaybackResult<Unit> = withContext(dispatcher) {
        if (lifecycle !in activeStates) return@withContext stateFailure(FailurePhase.PLAYBACK)
        try {
            core.setBoolean("pause", paused)
            this@AndroidMpvBackend.paused = paused
            PlaybackResult.Success(Unit)
        } catch (_: Throwable) {
            stateFailure(FailurePhase.PLAYBACK)
        }
    }

    override suspend fun seekTo(positionMs: Long): PlaybackResult<Unit> = withContext(dispatcher) {
        if (lifecycle !in activeStates || positionMs < 0) return@withContext stateFailure(FailurePhase.PLAYBACK)
        runCatching {
            core.command("seek", (positionMs / 1_000.0).toString(), "absolute+exact")
            this@AndroidMpvBackend.positionMs = positionMs
            emitTimelineFacts()
        }.fold(
            onSuccess = { PlaybackResult.Success(Unit) },
            onFailure = { stateFailure(FailurePhase.PLAYBACK) },
        )
    }

    override suspend fun setPlaybackRate(rate: Float): PlaybackResult<Unit> = withContext(dispatcher) {
        if (lifecycle !in activeStates || !rate.isFinite() || rate !in 0.25f..4f) {
            return@withContext stateFailure(FailurePhase.PLAYBACK)
        }
        runCatching {
            core.setString("speed", rate.toString())
            _events.tryEmit(MpvBackendEvent.PlaybackRateChanged(rate))
        }.fold(
            onSuccess = { PlaybackResult.Success(Unit) },
            onFailure = { stateFailure(FailurePhase.PLAYBACK) },
        )
    }

    override suspend fun selectAudioTrack(trackId: PlaybackTrackId): PlaybackResult<Unit> =
        selectTrack(trackId, PlaybackTrackType.AUDIO, "aid")

    override suspend fun selectSubtitleTrack(trackId: PlaybackTrackId): PlaybackResult<Unit> =
        selectTrack(trackId, PlaybackTrackType.SUBTITLE, "sid")

    private suspend fun selectTrack(
        trackId: PlaybackTrackId,
        type: PlaybackTrackType,
        property: String,
    ): PlaybackResult<Unit> = withContext(dispatcher) {
        val reference = trackReferences[trackId]
            ?.takeIf { it.type == type }
            ?: return@withContext stateFailure(FailurePhase.PLAYBACK)
        runCatching { core.setString(property, reference.nativeId) }.fold(
            onSuccess = {
                if (type == PlaybackTrackType.SUBTITLE) manualSubtitleSelection = true
                PlaybackResult.Success(Unit)
            },
            onFailure = { stateFailure(FailurePhase.PLAYBACK) },
        )
    }

    override suspend fun setSubtitlesEnabled(enabled: Boolean): PlaybackResult<Unit> =
        withContext(dispatcher) {
            runCatching { core.setString("sid", if (enabled) "auto" else "no") }.fold(
                onSuccess = {
                    manualSubtitleSelection = true
                    appliedSubtitlesEnabled = enabled
                    PlaybackResult.Success(Unit)
                },
                onFailure = { stateFailure(FailurePhase.PLAYBACK) },
            )
        }

    override suspend fun setVolume(volume: Float): PlaybackResult<Unit> = withContext(dispatcher) {
        runCatching { core.setString("volume", (volume.coerceIn(0f, 1f) * 100f).toString()) }.fold(
            onSuccess = { PlaybackResult.Success(Unit) },
            onFailure = { stateFailure(FailurePhase.PLAYBACK) },
        )
    }

    override suspend fun attachExternalSubtitle(
        subtitleId: ExternalSubtitleId,
    ): PlaybackResult<Unit> = withContext(dispatcher) {
        if (lifecycle !in activeStates) return@withContext stateFailure(FailurePhase.PLAYBACK)
        val registration = externalSubtitleResolver.resolve(subtitleId)
            ?: return@withContext stateFailure(FailurePhase.PLAYBACK)
        runCatching {
            val command = mutableListOf("sub-add", registration.uri, "select")
            registration.label?.let(command::add)
            if (registration.label != null) registration.language?.let(command::add)
            core.command(*command.toTypedArray())
            manualSubtitleSelection = true
        }.fold(
            onSuccess = { PlaybackResult.Success(Unit) },
            onFailure = { stateFailure(FailurePhase.PLAYBACK) },
        )
    }

    override suspend fun apply(plan: MpvAdapterPlan): PlaybackResult<Unit> = withContext(dispatcher) {
        if (lifecycle !in activeStates) return@withContext stateFailure(FailurePhase.PLAYBACK)
        try {
            this@AndroidMpvBackend.plan = plan
            applyRuntime(plan)
            PlaybackResult.Success(Unit)
        } catch (_: Throwable) {
            stateFailure(FailurePhase.PLAYBACK)
        }
    }

    // Non-child + bounded for the same reason as stopSource: native detach must be abandonable.
    override suspend fun detachSurface(): PlaybackResult<Unit> {
        val task = scope.async {
            detachSurfaceOnLane()
        }
        return withTimeoutOrNull(NATIVE_CALL_ABANDON_TIMEOUT_MS) { task.await() }
            ?: releaseFailure(FailureCode.SURFACE_LOST)
    }

    private fun detachSurfaceOnLane(): PlaybackResult<Unit> {
        if (lifecycle == MpvBackendLifecycle.DEAD) return PlaybackResult.Success(Unit)
        val currentLease = lease ?: return PlaybackResult.Success(Unit)
        // Stop the VO before the surface leaves (upstream order; see initiateRelease).
        runCatching { core.setString("vo", "null") }
        if (!core.detachSurfaceWithResult()) return releaseFailure(FailureCode.SURFACE_LOST)
        currentLease.confirmDetached()
        lease = null
        return PlaybackResult.Success(Unit)
    }

    override suspend fun metrics(): PlaybackResult<MpvMetrics> = withContext(dispatcher) {
        if (lifecycle !in activeStates) return@withContext stateFailure(FailurePhase.PLAYBACK)
        PlaybackResult.Success(
            MpvMetrics(
                videoRendered = core.long(PRESENTED_VIDEO_FRAMES),
                videoSkipped = core.long("decoder-frame-drop-count"),
                videoDropped = core.long("frame-drop-count"),
            ),
        )
    }

    // NOT withContext: mpv_command is a synchronous native call that blocks until the core's
    // playloop services it (libmpv contract). A wedged playloop (device-observed: emulator
    // ANGLE/goldfish, thread dump anchored at MPV.command) blocks that thread forever, and a
    // structured withContext child can never be abandoned by a caller timeout — the session's
    // withTimeoutOrNull would wait on it indefinitely, hanging the release barrier. The body
    // therefore runs as a NON-CHILD task on the backend scope, and the caller-facing await is
    // bounded here: on expiry the task (and, on a true wedge, its lane thread) is abandoned and
    // the caller gets a typed failure so the barrier can escalate to hard abort.
    override suspend fun stopSource(): PlaybackResult<Unit> {
        val task = scope.async {
            if (lifecycle == MpvBackendLifecycle.DEAD) return@async PlaybackResult.Success(Unit)
            if (lifecycle == MpvBackendLifecycle.ATTACHED || lifecycle == MpvBackendLifecycle.IDLE) {
                return@async PlaybackResult.Success(Unit)
            }
            if (lifecycle !in activeStates) return@async stateFailure(FailurePhase.RELEASE)
            val acknowledgement = CompletableDeferred<Boolean>()
            stopAcknowledgement = acknowledgement
            runCatching { core.command("stop") }
                .getOrElse {
                    stopAcknowledgement = null
                    return@async releaseFailure(FailureCode.RESOURCE_RELEASE_FAILED)
                }
            val stopped = withTimeoutOrNull(SOURCE_STOP_TIMEOUT_MS) { acknowledgement.await() } == true
            if (stopAcknowledgement === acknowledgement) stopAcknowledgement = null
            if (!stopped) return@async releaseFailure(FailureCode.RESOURCE_RELEASE_FAILED)
            resetSourceFacts()
            lifecycle = MpvBackendLifecycle.ATTACHED
            PlaybackResult.Success(Unit)
        }
        return withTimeoutOrNull(NATIVE_CALL_ABANDON_TIMEOUT_MS) { task.await() }
            ?: releaseFailure(FailureCode.RESOURCE_RELEASE_FAILED)
    }

    override suspend fun release(): PlaybackResult<Unit> = initiateRelease().await()

    override suspend fun hardAbort(): PlaybackResult<Unit> {
        val graceful = releaseTask
        if (graceful == null) return initiateForcedTermination().await()
        // A wedged native teardown never completes the graceful task. Hard abort must not wait
        // on it indefinitely — after a bounded grace it escalates to forced termination, which
        // runs on an independent lane precisely so a stuck serialized call cannot starve it.
        val gracefulResult = kotlinx.coroutines.withTimeoutOrNull(GRACEFUL_RESULT_WAIT_MS) {
            graceful.await()
        }
        if (gracefulResult is PlaybackResult.Success) return PlaybackResult.Success(Unit)
        return initiateForcedTermination().await()
    }

    @Synchronized private fun initiateRelease(): kotlinx.coroutines.Deferred<PlaybackResult<Unit>> {
        releaseTask?.let { return it }
        terminalEventsSuppressed = true
        return scope.async {
            lifecycle = MpvBackendLifecycle.RELEASING
            runCatching { core.command("stop") }
            // Upstream teardown order (mpv-android BaseMPVView): stop the video output BEFORE
            // detaching the surface. Detaching while the VO still owns the surface is the
            // documented native deadlock race; `vo=null` deinitializes the VO first.
            runCatching { core.setString("vo", "null") }
            runCatching {
                if (lease != null && core.detachSurfaceWithResult()) lease?.confirmDetached()
            }
            runCatching { core.removeObserver(this@AndroidMpvBackend) }
            val destroyed = runCatching { core.destroyWithResult() }.getOrDefault(false)
            if (!destroyed) return@async releaseFailure(FailureCode.RESOURCE_RELEASE_FAILED)
            lease?.confirmCoreDestroyed()
            lifecycle = MpvBackendLifecycle.DEAD
            PlaybackResult.Success(Unit)
        }.also { releaseTask = it }
    }

    @Synchronized private fun initiateForcedTermination(): kotlinx.coroutines.Deferred<PlaybackResult<Unit>> {
        forceTerminationTask?.let { return it }
        terminalEventsSuppressed = true
        // Deliberately NOT the serialized backend dispatcher: a native call wedged on that lane
        // (device-observed: emulator ANGLE/goldfish teardown blocking inside `stop`) would queue
        // forced termination behind itself forever. The fork's forceTerminateWithResult is the
        // designated concurrent-safe last-resort entry (idempotent before/after initialization),
        // so it gets its own single-thread lane and can prove death while the graceful call is
        // still stuck. The wedged thread itself stays leaked until process death — bounded,
        // fail-closed session release is the containment, not thread recovery.
        return terminationScope.async {
            lifecycle = MpvBackendLifecycle.RELEASING
            val terminated = runCatching { core.forceTerminateWithResult() }.getOrDefault(false)
            if (!terminated) return@async releaseFailure(FailureCode.RESOURCE_RELEASE_FAILED)
            lease?.confirmCoreDestroyed()
            lifecycle = MpvBackendLifecycle.DEAD
            PlaybackResult.Success(Unit)
        }.also { forceTerminationTask = it }
    }

    override fun property(name: String, value: Long) {
        scope.launch {
            if (lifecycle == MpvBackendLifecycle.DEAD || lifecycle == MpvBackendLifecycle.RELEASING) return@launch
            when (name) {
                "stream-pos" -> if (value > 0 && value > lastStreamPosition) {
                    if (lastStreamPosition < 0 || value - lastStreamPosition >= BYTE_PROGRESS_STEP) {
                        _events.tryEmit(MpvBackendEvent.BytesReceived)
                        lastStreamPosition = value
                    }
                }
                PRESENTED_VIDEO_FRAMES -> if (value > 0 && !firstVideoFrameSent) {
                    firstVideoFrameSent = true
                    lifecycle = MpvBackendLifecycle.PLAYING
                    _events.tryEmit(MpvBackendEvent.FirstVideoFrame)
                }
                "video-params/w" -> {
                    videoWidth = value.toInt()
                    emitVideoSizeIfKnown()
                }
                "video-params/h" -> {
                    videoHeight = value.toInt()
                    emitVideoSizeIfKnown()
                }
            }
        }
    }

    override fun property(name: String, value: Boolean) {
        scope.launch {
            if (terminalEventsSuppressed) return@launch
            if (name == "paused-for-cache") {
                _events.tryEmit(if (value) MpvBackendEvent.BufferingStarted else MpvBackendEvent.BufferingEnded)
            }
            if (name == "seekable") {
                seekable = value
                emitTimelineFacts()
            }
        }
    }

    override fun property(name: String, value: Double) {
        scope.launch {
            if (terminalEventsSuppressed) return@launch
            if (name == "audio-pts" && value.isFinite() && !firstAudioSent) {
                firstAudioSent = true
                _events.tryEmit(MpvBackendEvent.FirstAudio)
            }
            when (name) {
                "time-pos" -> {
                    positionMs = (value * 1_000).toLong().coerceAtLeast(0)
                    emitTimelineFacts()
                }
                "duration" -> {
                    durationMs = (value * 1_000).toLong().takeIf { it >= 0 }
                    emitTimelineFacts()
                }
                "demuxer-cache-duration" -> {
                    bufferedDurationMs = (value * 1_000).toLong().coerceAtLeast(0)
                    emitTimelineFacts()
                }
                "speed" -> if (value.isFinite() && value.toFloat() in 0.25f..4f) {
                    _events.tryEmit(MpvBackendEvent.PlaybackRateChanged(value.toFloat()))
                }
            }
        }
    }

    override fun property(name: String, value: String) {
        scope.launch {
            if (terminalEventsSuppressed) return@launch
            if (name == "hwdec-current" && value.isNotBlank()) {
                _events.tryEmit(MpvBackendEvent.VideoDecoderInitialized(value))
            }
            if (name == "video-codec" && value.isNotBlank()) {
                _events.tryEmit(MpvBackendEvent.VideoInputFormatChanged(value.toVideoMimeType()))
            }
        }
    }

    override fun property(name: String, value: MPVNode) {
        if (name != "track-list") return
        scope.launch {
            if (terminalEventsSuppressed) return@launch
            emitTrackFacts(parseMpvTracks(value))
        }
    }

    override fun event(id: Int, data: MPVNode) {
        scope.launch {
            if (terminalEventsSuppressed) return@launch
            when (id) {
                MPV_EVENT_START_FILE -> {
                    lifecycle = MpvBackendLifecycle.LOADING
                    _events.tryEmit(MpvBackendEvent.StateObserved(PlaybackEngineState.BUFFERING, !paused, true))
                }
                MPV_EVENT_FILE_LOADED -> {
                    val tracks = runCatching { parseMpvTracks(core.node("track-list")) }.getOrNull()
                    if (tracks != null) {
                        // Availability keeps the existing file-loaded fallback. Stable frame-rate
                        // evidence is emitted only by the already-observed track-list callback.
                        emitTrackFacts(tracks, includeFrameRate = false)
                    }
                    lifecycle = MpvBackendLifecycle.PLAYING
                    _events.tryEmit(MpvBackendEvent.StateObserved(PlaybackEngineState.READY, !paused, false))
                }
                MPV_EVENT_END_FILE -> handleEndFile(parseEndFile(data))
                MPV_EVENT_SHUTDOWN -> _events.tryEmit(MpvBackendEvent.Ended(PlaybackEndReason.SHUTDOWN))
            }
        }
    }

    private fun handleEndFile(end: ParsedMpvEndFile) {
        lifecycle = MpvBackendLifecycle.IDLE
        if (end.reason == MpvEndReason.STOP) stopAcknowledgement?.complete(true)
        when (end.reason) {
            MpvEndReason.EOF -> _events.tryEmit(MpvBackendEvent.Ended(PlaybackEndReason.EOF))
            MpvEndReason.ERROR -> _events.tryEmit(MpvBackendEvent.Failed(normalizeMpvError(end.fileError)))
            MpvEndReason.STOP -> _events.tryEmit(MpvBackendEvent.Ended(PlaybackEndReason.STOPPED))
            MpvEndReason.QUIT -> _events.tryEmit(MpvBackendEvent.Ended(PlaybackEndReason.SHUTDOWN))
            MpvEndReason.REDIRECT -> Unit // Internal redirect transition, not a terminal playback fact.
            MpvEndReason.UNKNOWN -> _events.tryEmit(MpvBackendEvent.Failed(normalizeMpvError(null)))
        }
    }

    private fun resetSourceFacts() {
        lastStreamPosition = -1L
        firstVideoFrameSent = false
        firstAudioSent = false
        videoWidth = 0
        videoHeight = 0
        lastVideoFrameRate = null
        positionMs = 0
        durationMs = null
        bufferedDurationMs = 0
        seekable = false
        trackReferences = emptyMap()
        trackRevision = 0
        restorationApplied = false
        manualSubtitleSelection = false
        appliedSubtitlesEnabled = plan.preInitOptions["sid"] != "no"
    }

    private fun observeFacts() {
        core.observeLong("stream-pos")
        core.observeLong(PRESENTED_VIDEO_FRAMES)
        core.observeLong("decoder-frame-drop-count")
        core.observeLong("frame-drop-count")
        core.observeLong("video-params/w")
        core.observeLong("video-params/h")
        core.observeDouble("audio-pts")
        core.observeDouble("time-pos")
        core.observeDouble("duration")
        core.observeDouble("demuxer-cache-duration")
        core.observeDouble("speed")
        core.observeBoolean("paused-for-cache")
        core.observeBoolean("seekable")
        core.observeString("hwdec-current")
        core.observeString("video-codec")
        core.observeNode("track-list")
    }

    private fun emitVideoSizeIfKnown() {
        if (videoWidth > 0 && videoHeight > 0) {
            _events.tryEmit(MpvBackendEvent.VideoSizeChanged(videoWidth, videoHeight))
        }
    }

    private fun emitTrackFacts(
        tracks: ParsedMpvTracks,
        includeFrameRate: Boolean = true,
    ) {
        _events.tryEmit(
            MpvBackendEvent.TracksAvailable(tracks.hasVideo, tracks.audio, tracks.subtitles, tracks.videoDimensions),
        )
        trackReferences = tracks.references.associateBy { it.id }
        trackRevision = if (trackRevision == Long.MAX_VALUE) Long.MAX_VALUE else trackRevision + 1
        _events.tryEmit(
            MpvBackendEvent.TrackCatalogUpdated(
                PlaybackTrackCatalog(
                    revision = trackRevision,
                    audio = tracks.audioTracks,
                    subtitles = tracks.subtitleTracks,
                    selectedAudioTrackId = tracks.selectedAudioTrackId,
                    selectedSubtitleTrackId = tracks.selectedSubtitleTrackId,
                    subtitlesEnabled = tracks.selectedSubtitleTrackId != null,
                ),
            ),
        )
        restoreTrackSelectionIfReady()
        if (!includeFrameRate) return
        tracks.selectedVideoFrameRate
            ?.takeIf { it != lastVideoFrameRate }
            ?.let { frameRate ->
                lastVideoFrameRate = frameRate
                _events.tryEmit(MpvBackendEvent.VideoFrameRateChanged(frameRate))
            }
    }

    private fun emitTimelineFacts() {
        _events.tryEmit(
            MpvBackendEvent.TimelineUpdated(
                PlaybackTimelineFacts(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedPositionMs = (positionMs + bufferedDurationMs).coerceAtLeast(positionMs),
                    seekable = seekable,
                ),
            ),
        )
    }

    private fun restoreTrackSelectionIfReady() {
        val checkpoint = plan.restorationCheckpoint ?: return
        if (restorationApplied || trackReferences.isEmpty()) return
        restorationApplied = true
        findTrackReference(checkpoint.selectedAudio)?.let { core.setString("aid", it.nativeId) }
        if (!checkpoint.subtitlesEnabled) {
            core.setString("sid", "no")
        } else {
            findTrackReference(checkpoint.selectedSubtitle)?.let { core.setString("sid", it.nativeId) }
        }
    }

    private fun findTrackReference(selection: RestorableTrackSelection?): MpvTrackReference? {
        selection ?: return null
        trackReferences[selection.originalId]?.let { exact ->
            if (exact.type == selection.type) return exact
        }
        return com.nuvio.tv.playback.core.TrackRestorationPolicy.bestMatch(
            trackReferences.values.filter { it.type == selection.type },
            selection,
        ) { it.descriptor }
    }

    private fun String.toVideoMimeType(): String? = when (lowercase()) {
        "h264", "avc", "avc1" -> "video/avc"
        "hevc", "h265", "hev1", "hvc1" -> "video/hevc"
        "av1", "av01" -> "video/av01"
        "vp9", "vp09" -> "video/x-vnd.on2.vp9"
        "mpeg2video", "mpeg2" -> "video/mpeg2"
        "mpeg4", "mpeg4video" -> "video/mp4v-es"
        "dolbyvision", "dovi" -> "video/dolby-vision"
        else -> null
    }

    private fun ensureTlsCaFile(): File {
        val destination = File(context.filesDir, "cacert.pem")
        if (destination.isFile && destination.length() > 0L) return destination
        val temporary = File(context.filesDir, "cacert.pem.clean-player.tmp")
        context.assets.open("cacert.pem").use { input ->
            temporary.outputStream().use(input::copyTo)
        }
        check(temporary.renameTo(destination) || destination.isFile) {
            "Unable to materialize libmpv TLS trust bundle"
        }
        if (temporary.exists()) temporary.delete()
        return destination
    }

    private fun applyRuntime(plan: MpvAdapterPlan) {
        plan.runtimeProperties.forEach(core::setString)
        val nextSubtitlesEnabled = plan.preInitOptions["sid"] != "no"
        val subtitlePreferenceChanged = nextSubtitlesEnabled != appliedSubtitlesEnabled
        if (!manualSubtitleSelection || subtitlePreferenceChanged) {
            plan.preInitOptions["sid"]?.let { core.setString("sid", it) }
            plan.preInitOptions["slang"]?.let { core.setString("slang", it) }
            manualSubtitleSelection = false
        }
        appliedSubtitlesEnabled = nextSubtitlesEnabled
        plan.preInitOptions["alang"]?.let { core.setString("alang", it) }
    }

    private fun stateFailure(phase: FailurePhase) = PlaybackResult.Failure(
        PlaybackFailure(
            code = FailureCode.UNKNOWN,
            domain = FailureDomain.DEVICE_RESOURCE,
            phase = phase,
            retryability = Retryability.HANDOFF_ELIGIBLE,
        ),
    )

    private fun releaseFailure(code: FailureCode) = PlaybackResult.Failure(
        PlaybackFailure(
            code = code,
            domain = FailureDomain.VIDEO_RENDERER_SURFACE,
            phase = FailurePhase.RELEASE,
            retryability = Retryability.FATAL,
        ),
    )

    private companion object {
        const val PRESENTED_VIDEO_FRAMES = "presented-video-frame-count"
        const val BYTE_PROGRESS_STEP = 256L * 1_024L
        const val SOURCE_STOP_TIMEOUT_MS = 3_000L

        /** How long hard abort waits on an in-flight graceful teardown before forcing death. */
        const val GRACEFUL_RESULT_WAIT_MS = 1_000L

        /**
         * Upper bound on any caller-facing wait for a blocking native call. On expiry the task
         * is abandoned (a truly wedged call leaks its lane thread until process death) and the
         * caller receives a typed failure instead of hanging the release barrier.
         */
        const val NATIVE_CALL_ABANDON_TIMEOUT_MS = 4_000L
        const val MPV_EVENT_SHUTDOWN = 1
        const val MPV_EVENT_START_FILE = 6
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
        val activeStates = setOf(
            MpvBackendLifecycle.ATTACHED,
            MpvBackendLifecycle.LOADING,
            MpvBackendLifecycle.PLAYING,
            MpvBackendLifecycle.IDLE,
        )
    }
}

private class AndroidMpvNativeCore(private val mpv: MPV) : MpvNativeCore {
    private var observerBridge: MPV.EventObserver? = null

    override fun create(context: Context) = mpv.create(context)
    override fun setOption(name: String, value: String): Boolean = mpv.setOptionString(name, value) >= 0
    override fun initialize() = mpv.init()
    override fun addObserver(observer: MpvNativeObserver) {
        val bridge = object : MPV.EventObserver {
            override fun eventProperty(property: String) = Unit
            override fun eventProperty(property: String, value: Long) = observer.property(property, value)
            override fun eventProperty(property: String, value: Boolean) = observer.property(property, value)
            override fun eventProperty(property: String, value: String) = observer.property(property, value)
            override fun eventProperty(property: String, value: Double) = observer.property(property, value)
            override fun eventProperty(property: String, value: MPVNode) = observer.property(property, value)
            override fun event(eventId: Int, data: MPVNode) = observer.event(eventId, data)
        }
        observerBridge = bridge
        mpv.addObserver(bridge)
    }
    override fun removeObserver(observer: MpvNativeObserver) {
        observerBridge?.let(mpv::removeObserver)
        observerBridge = null
    }
    override fun observeLong(name: String) = mpv.observeProperty(name, MPV.mpvFormat.MPV_FORMAT_INT64)
    override fun observeDouble(name: String) = mpv.observeProperty(name, MPV.mpvFormat.MPV_FORMAT_DOUBLE)
    override fun observeBoolean(name: String) = mpv.observeProperty(name, MPV.mpvFormat.MPV_FORMAT_FLAG)
    override fun observeString(name: String) = mpv.observeProperty(name, MPV.mpvFormat.MPV_FORMAT_STRING)
    override fun observeNode(name: String) = mpv.observeProperty(name, MPV.mpvFormat.MPV_FORMAT_NODE)
    override fun attachSurface(surface: Surface): Boolean = mpv.attachSurfaceWithResult(surface)
    override fun detachSurfaceWithResult(): Boolean = mpv.detachSurfaceWithResult()
    override fun command(vararg values: String) = mpv.command(*values)
    override fun setString(name: String, value: String) = mpv.setPropertyString(name, value)
    override fun setBoolean(name: String, value: Boolean) = mpv.setPropertyBoolean(name, value)
    override fun long(name: String): Long? = mpv.getPropertyLong(name)
    override fun node(name: String): MPVNode = mpv.getPropertyNode(name) ?: MPVNode.None
    override fun destroyWithResult(): Boolean = mpv.destroyWithResult()
    override fun forceTerminateWithResult(): Boolean = mpv.destroyWithResult()
}

internal data class ParsedMpvEndFile(val reason: MpvEndReason, val fileError: String?)

internal fun parseEndFile(data: MPVNode): ParsedMpvEndFile {
    val values = data.asMap().orEmpty()
    val reason = when (values["reason"]?.asString()?.lowercase()) {
        "eof" -> MpvEndReason.EOF
        "error" -> MpvEndReason.ERROR
        "stop" -> MpvEndReason.STOP
        "quit" -> MpvEndReason.QUIT
        "redirect" -> MpvEndReason.REDIRECT
        else -> MpvEndReason.UNKNOWN
    }
    return ParsedMpvEndFile(reason, values["file_error"]?.asString())
}

internal data class ParsedMpvTracks(
    val hasVideo: Boolean,
    val audio: Int,
    val subtitles: Int,
    val selectedVideoFrameRate: Float?,
    /** Selected video track's container header size (demux-w/h); known before the decoder opens. */
    val videoDimensions: com.nuvio.tv.playback.core.VideoDimensions? = null,
    val audioTracks: List<PlaybackTrackDescriptor>,
    val subtitleTracks: List<PlaybackTrackDescriptor>,
    val selectedAudioTrackId: PlaybackTrackId?,
    val selectedSubtitleTrackId: PlaybackTrackId?,
    val references: List<MpvTrackReference>,
)

internal data class MpvTrackReference(
    val id: PlaybackTrackId,
    val nativeId: String,
    val type: PlaybackTrackType,
    val descriptor: PlaybackTrackDescriptor,
)

/** Uses the selected track's demux header; estimated-vf-fps is runtime cadence, not content rate. */
internal fun parseMpvTracks(node: MPVNode): ParsedMpvTracks {
    val tracks = node.asArray().orEmpty().mapNotNull { it.asMap() }
    val types = tracks.mapNotNull { it["type"]?.asString() }
    val selectedVideo = tracks.firstOrNull { track ->
        track["type"]?.asString() == "video" && track["selected"]?.asBoolean() == true
    }
    val selectedVideoFrameRate = selectedVideo?.get("demux-fps")?.asDouble()?.toFloat()?.takeIf { frameRate ->
        com.nuvio.tv.playback.core.ContentFrameRatePolicy.validOrNull(frameRate) != null
    }
    val width = selectedVideo?.get("demux-w")?.asInt() ?: 0L
    val height = selectedVideo?.get("demux-h")?.asInt() ?: 0L
    val videoDimensions = if (width > 0 && height > 0 && width <= Int.MAX_VALUE && height <= Int.MAX_VALUE) {
        com.nuvio.tv.playback.core.VideoDimensions(width.toInt(), height.toInt())
    } else {
        null
    }
    val references = mutableListOf<MpvTrackReference>()
    val audioTracks = mutableListOf<PlaybackTrackDescriptor>()
    val subtitleTracks = mutableListOf<PlaybackTrackDescriptor>()
    var selectedAudio: PlaybackTrackId? = null
    var selectedSubtitle: PlaybackTrackId? = null
    tracks.forEachIndexed { index, track ->
        val type = when (track["type"]?.asString()) {
            "audio" -> PlaybackTrackType.AUDIO
            "sub" -> PlaybackTrackType.SUBTITLE
            else -> return@forEachIndexed
        }
        val nativeId = track["id"]?.asInt()?.toString() ?: index.toString()
        val id = PlaybackTrackId("mpv:${type.name.lowercase()}:$nativeId")
        val descriptor = PlaybackTrackDescriptor(
            id = id,
            type = type,
            label = track["title"]?.asString(),
            language = track["lang"]?.asString(),
            codec = track["codec"]?.asString(),
            forced = track["forced"]?.asBoolean() == true,
            default = track["default"]?.asBoolean() == true,
        )
        references += MpvTrackReference(id, nativeId, type, descriptor)
        if (type == PlaybackTrackType.AUDIO) audioTracks += descriptor else subtitleTracks += descriptor
        if (track["selected"]?.asBoolean() == true) {
            if (type == PlaybackTrackType.AUDIO) selectedAudio = id else selectedSubtitle = id
        }
    }
    return ParsedMpvTracks(
        hasVideo = types.any { it == "video" },
        audio = types.count { it == "audio" },
        subtitles = types.count { it == "sub" },
        selectedVideoFrameRate = selectedVideoFrameRate,
        videoDimensions = videoDimensions,
        audioTracks = audioTracks,
        subtitleTracks = subtitleTracks,
        selectedAudioTrackId = selectedAudio,
        selectedSubtitleTrackId = selectedSubtitle,
        references = references,
    )
}


internal fun normalizeMpvError(raw: String?): PlaybackFailure {
    val value = raw.orEmpty().lowercase()
    val inferredStatus = inferHttpAuthorizationStatus(value)
    val (code, domain, retryability) = when {
        "timeout" in value -> Triple(FailureCode.NETWORK_TIMEOUT, FailureDomain.NETWORK, Retryability.RETRYABLE_WITH_FRESH_REQUEST)
        inferredStatus != null ->
            Triple(FailureCode.AUTHORIZATION_REJECTED, FailureDomain.AUTHORIZATION_PROVIDER_LIMIT, Retryability.FATAL)
        "tls" in value || "certificate" in value ->
            Triple(FailureCode.TLS_HANDSHAKE_FAILED, FailureDomain.TLS, Retryability.HANDOFF_ELIGIBLE)
        "network" in value || "resolve" in value || "connect" in value ->
            Triple(FailureCode.NETWORK_UNREACHABLE, FailureDomain.NETWORK, Retryability.RETRYABLE_WITH_FRESH_REQUEST)
        raw != null -> Triple(FailureCode.DEMUX_FAILED, FailureDomain.DEMUX, Retryability.HANDOFF_ELIGIBLE)
        else -> Triple(FailureCode.UNKNOWN, FailureDomain.UNKNOWN, Retryability.HANDOFF_ELIGIBLE)
    }
    return PlaybackFailure(
        code,
        domain,
        FailurePhase.PLAYBACK,
        retryability,
        httpStatus = inferredStatus,
        statusProvenance = inferredStatus?.let {
            com.nuvio.tv.playback.core.HttpStatusProvenance.INFERRED_FROM_NETWORK_ERROR
        },
    )
}

private val MPV_HTTP_AUTHORIZATION = Regex(
    "(?:http(?:\\s+(?:error|status))?|server\\s+returned|status\\s+code)\\s*[:=]?\\s*(401|403)\\b|\\b(401\\s+unauthorized|403\\s+forbidden)\\b",
    RegexOption.IGNORE_CASE,
)

internal fun inferHttpAuthorizationStatus(raw: String): Int? {
    val match = MPV_HTTP_AUTHORIZATION.find(raw) ?: return null
    return match.groupValues.asSequence()
        .drop(1)
        .mapNotNull { group -> Regex("\\d{3}").find(group)?.value?.toIntOrNull() }
        .firstOrNull { it == 401 || it == 403 }
}
