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
import com.nuvio.tv.playback.core.Retryability
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    ) : MpvBackendEvent
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
    suspend fun apply(plan: MpvAdapterPlan): PlaybackResult<Unit>
    suspend fun detachSurface(): PlaybackResult<Unit>
    suspend fun metrics(): PlaybackResult<MpvMetrics>
    suspend fun release(): PlaybackResult<Unit>
    suspend fun hardAbort(): PlaybackResult<Unit>
}

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
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val coreFactory: () -> MpvNativeCore = { AndroidMpvNativeCore(MPV()) },
) : MpvBackendFactory {
    private val appContext = context.applicationContext

    override suspend fun create(plan: MpvAdapterPlan): PlaybackResult<MpvBackend> =
        PlaybackResult.Success(
            AndroidMpvBackend(appContext, plan, dispatcher, coreFactory()),
        )
}

internal class AndroidMpvBackend(
    private val context: Context,
    private var plan: MpvAdapterPlan,
    private val dispatcher: CoroutineDispatcher,
    private val core: MpvNativeCore,
) : MpvBackend, MpvNativeObserver {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _events = MutableSharedFlow<MpvBackendEvent>(extraBufferCapacity = 64)
    override val events: Flow<MpvBackendEvent> = _events.asSharedFlow()

    @Volatile internal var lifecycle: MpvBackendLifecycle = MpvBackendLifecycle.CREATED
        private set
    private var lease: MpvSurfaceLease? = null
    private var lastStreamPosition = -1L
    private var firstVideoFrameSent = false
    private var firstAudioSent = false
    private var confirmedAudioOnly = false
    @Volatile private var terminalEventsSuppressed = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var paused = plan.startPaused
    private var releaseTask: kotlinx.coroutines.Deferred<PlaybackResult<Unit>>? = null

    override suspend fun attachSurface(lease: MpvSurfaceLease): PlaybackResult<Unit> =
        withContext(dispatcher) {
            if (lifecycle != MpvBackendLifecycle.CREATED) return@withContext stateFailure(FailurePhase.SURFACE_ATTACHMENT)
            try {
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
                observeFacts()
                lifecycle = MpvBackendLifecycle.INITIALIZED
                if (!core.attachSurface(lease.surface)) throw IllegalStateException("mpv surface rejected")
                lease.markAttached()
                this@AndroidMpvBackend.lease = lease
                lifecycle = MpvBackendLifecycle.ATTACHED
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
            paused = plan.startPaused
            lifecycle = MpvBackendLifecycle.LOADING
            core.command("loadfile", plan.url, "replace")
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

    override suspend fun detachSurface(): PlaybackResult<Unit> = withContext(dispatcher) {
        if (lifecycle == MpvBackendLifecycle.DEAD) return@withContext PlaybackResult.Success(Unit)
        val currentLease = lease ?: return@withContext PlaybackResult.Success(Unit)
        if (!core.detachSurfaceWithResult()) return@withContext releaseFailure(FailureCode.SURFACE_LOST)
        currentLease.confirmDetached()
        PlaybackResult.Success(Unit)
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

    override suspend fun release(): PlaybackResult<Unit> = initiateRelease().await()

    override suspend fun hardAbort(): PlaybackResult<Unit> = initiateRelease().await()

    @Synchronized private fun initiateRelease(): kotlinx.coroutines.Deferred<PlaybackResult<Unit>> {
        releaseTask?.let { return it }
        terminalEventsSuppressed = true
        return scope.async {
            lifecycle = MpvBackendLifecycle.RELEASING
            runCatching { core.command("stop") }
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
        }
    }

    override fun property(name: String, value: Double) {
        scope.launch {
            if (terminalEventsSuppressed) return@launch
            if (name == "audio-pts" && value.isFinite() && confirmedAudioOnly && !firstAudioSent) {
                firstAudioSent = true
                _events.tryEmit(MpvBackendEvent.FirstAudio)
            }
        }
    }

    override fun property(name: String, value: String) {
        scope.launch {
            if (terminalEventsSuppressed) return@launch
            if (name == "hwdec-current" && value.isNotBlank()) {
                _events.tryEmit(MpvBackendEvent.VideoDecoderInitialized(value))
            }
        }
    }

    override fun property(name: String, value: MPVNode) {
        if (name != "track-list") return
        scope.launch {
            if (terminalEventsSuppressed) return@launch
            val tracks = parseTracks(value)
            confirmedAudioOnly = !tracks.hasVideo && tracks.audio > 0
            _events.tryEmit(MpvBackendEvent.TracksAvailable(tracks.hasVideo, tracks.audio, tracks.subtitles))
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
                    val tracks = runCatching { parseTracks(core.node("track-list")) }.getOrNull()
                    if (tracks != null) {
                        confirmedAudioOnly = !tracks.hasVideo && tracks.audio > 0
                        _events.tryEmit(MpvBackendEvent.TracksAvailable(tracks.hasVideo, tracks.audio, tracks.subtitles))
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
        when (end.reason) {
            MpvEndReason.EOF -> _events.tryEmit(MpvBackendEvent.Ended(PlaybackEndReason.EOF))
            MpvEndReason.ERROR -> _events.tryEmit(MpvBackendEvent.Failed(normalizeMpvError(end.fileError)))
            MpvEndReason.STOP -> _events.tryEmit(MpvBackendEvent.Ended(PlaybackEndReason.STOPPED))
            MpvEndReason.QUIT -> _events.tryEmit(MpvBackendEvent.Ended(PlaybackEndReason.SHUTDOWN))
            MpvEndReason.REDIRECT -> Unit // Internal redirect transition, not a terminal playback fact.
            MpvEndReason.UNKNOWN -> _events.tryEmit(MpvBackendEvent.Failed(normalizeMpvError(null)))
        }
    }

    private fun observeFacts() {
        core.observeLong("stream-pos")
        core.observeLong(PRESENTED_VIDEO_FRAMES)
        core.observeLong("decoder-frame-drop-count")
        core.observeLong("frame-drop-count")
        core.observeLong("video-params/w")
        core.observeLong("video-params/h")
        core.observeDouble("audio-pts")
        core.observeBoolean("paused-for-cache")
        core.observeString("hwdec-current")
        core.observeNode("track-list")
    }

    private fun emitVideoSizeIfKnown() {
        if (videoWidth > 0 && videoHeight > 0) {
            _events.tryEmit(MpvBackendEvent.VideoSizeChanged(videoWidth, videoHeight))
        }
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
        plan.preInitOptions["sid"]?.let { core.setString("sid", it) }
        plan.preInitOptions["alang"]?.let { core.setString("alang", it) }
        plan.preInitOptions["slang"]?.let { core.setString("slang", it) }
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

private data class ParsedTracks(val hasVideo: Boolean, val audio: Int, val subtitles: Int)

private fun parseTracks(node: MPVNode): ParsedTracks {
    val types = node.asArray().orEmpty().mapNotNull { it.asMap()?.get("type")?.asString() }
    return ParsedTracks(
        hasVideo = types.any { it == "video" },
        audio = types.count { it == "audio" },
        subtitles = types.count { it == "sub" },
    )
}

internal fun normalizeMpvError(raw: String?): PlaybackFailure {
    val value = raw.orEmpty().lowercase()
    val (code, domain, retryability) = when {
        "timeout" in value -> Triple(FailureCode.NETWORK_TIMEOUT, FailureDomain.NETWORK, Retryability.RETRYABLE_WITH_FRESH_REQUEST)
        "401" in value || "403" in value || "unauthorized" in value || "forbidden" in value ->
            Triple(FailureCode.AUTHORIZATION_REJECTED, FailureDomain.AUTHORIZATION_PROVIDER_LIMIT, Retryability.FATAL)
        "tls" in value || "certificate" in value ->
            Triple(FailureCode.TLS_HANDSHAKE_FAILED, FailureDomain.TLS, Retryability.HANDOFF_ELIGIBLE)
        "network" in value || "resolve" in value || "connect" in value ->
            Triple(FailureCode.NETWORK_UNREACHABLE, FailureDomain.NETWORK, Retryability.RETRYABLE_WITH_FRESH_REQUEST)
        raw != null -> Triple(FailureCode.DEMUX_FAILED, FailureDomain.DEMUX, Retryability.HANDOFF_ELIGIBLE)
        else -> Triple(FailureCode.UNKNOWN, FailureDomain.UNKNOWN, Retryability.HANDOFF_ELIGIBLE)
    }
    return PlaybackFailure(code, domain, FailurePhase.PLAYBACK, retryability)
}
