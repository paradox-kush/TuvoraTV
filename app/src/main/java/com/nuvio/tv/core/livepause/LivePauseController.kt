package com.nuvio.tv.core.livepause

import com.nuvio.tv.core.contracts.LivePauseEngine
import com.nuvio.tv.core.contracts.LivePauseWindow
import com.nuvio.tv.core.iptv.BoundedLoad
import com.nuvio.tv.core.iptv.LoadOutcome
import com.nuvio.tv.core.iptv.LoadSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// TV twin of NuvioMobile's commonMain features/livetv/pause/LivePauseController (same states, same
// decisions). Every platform detail is behind a port (LivePausePorts.kt).

/**
 * The pause / rewind state holder for ONE player instance (multiview-ready: nothing here is global).
 *
 * All decisions run on one actor coroutine, so its fields need no locks; the UI and the capture reach it by
 * posting commands, and read [state]. Mechanism per open comes from [LivePausePolicy.mechanism]:
 *  - DISK_BUFFER: pause -> stop the live source -> capture opens ([CAPTURE_OPEN_DELAY_MS] later) -> Preparing
 *    (BoundedLoad LIVE_PAUSE) until the first piece -> the player swaps to the local playlist, paused at the
 *    window start -> Shifted. Live = drop the buffer and open the channel again.
 *  - ENGINE_BACK_BUFFER (mpv): raise the demuxer caches while paused; the window comes from the engine.
 *  - PROVIDER_CATCHUP: remember the pause instant; resuming within [CATCH_UP_RESUME_THRESHOLD_MS] just
 *    resumes, later opens the provider's archive at that instant.
 */
class LivePauseController(
    val instanceId: String,
    parentScope: CoroutineScope,
    private val engine: LivePauseEngine,
    private val captureFactory: LiveCaptureFactory,
    private val minter: LiveLinkMinter,
    private val env: LivePauseEnvironment,
    private val monotonicMs: () -> Long,
    private val wallMs: () -> Long,
    private val tickMs: Long = TICK_MS,
    /** Where disk work runs (free-space probe, capture directory setup); never the actor's own (Main) dispatcher. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val ownJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownJob)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow<LivePauseState>(LivePauseState.Off)

    val state: StateFlow<LivePauseState> = mutableState.asStateFlow()

    // ---- actor-confined state (touched only inside handle())
    private var mechanism = LivePauseMechanism.UNAVAILABLE
    private var generation = 0
    private var capture: LiveCapture? = null
    private var eventsJob: Job? = null
    private var prepareJob: Job? = null
    private var tickJob: Job? = null
    private var ready: CompletableDeferred<String>? = null
    private var sourceReleased = false
    private var captureWindow = LiveCaptureEvent.Window(0, 0, false)
    private var pauseMono = 0L
    private var pauseWall = 0L
    private var catchUpBaseWall: Long? = null
    private var disposed = false
    /** A capture failure that arrived after its first piece but before the Prepared result was handled. */
    private var captureFailure: LivePauseFailure? = null

    init {
        scope.launch {
            try {
                for (command in commands) {
                    if (disposed) break
                    handle(command)
                }
            } finally {
                // Cancelled mid-command (the player was released while the actor was inside an engine call)
                // or finished: Dispose may never have been handled, but the buffer must not outlive us.
                // Non-suspending: the capture hands the file deletion to its own cleanup scope.
                disposeCapture()
            }
        }
    }

    // ---- the UI's methods: post and return

    fun onPause() = post(Command.Pause)
    fun onPlay() = post(Command.Play)
    fun seekInWindow(positionMs: Long) = post(Command.Seek(positionMs))
    fun goLive() = post(Command.GoLive)

    /** Failed -> Retry: go live, then pause again. */
    fun retry() = post(Command.Retry)

    /** The viewer zapped: the buffer belongs to the old channel; the zap itself retunes. */
    fun onChannelChanged() = post(Command.ChannelChanged)

    /** A live channel just started playing: mpv gets its live cache plan. */
    fun onLiveStarted() = post(Command.LiveStarted)

    /** Cancels the capture and deletes its files. */
    fun dispose() {
        commands.trySend(Command.Dispose)
        commands.close()
    }

    private fun post(command: Command) {
        commands.trySend(command)
    }

    private sealed interface Command {
        data object Pause : Command
        data object Play : Command
        data class Seek(val positionMs: Long) : Command
        data object GoLive : Command
        data object Retry : Command
        data object ChannelChanged : Command
        data object LiveStarted : Command
        data object Dispose : Command
        data object Tick : Command
        class Capture(val generation: Int, val event: LiveCaptureEvent) : Command
        class Prepared(val generation: Int, val outcome: LoadOutcome<String>) : Command
    }

    private class CaptureFailure(val reason: LivePauseFailure) : RuntimeException("live pause capture failed")

    private suspend fun handle(command: Command) {
        try {
            when (command) {
                Command.Pause -> pause()
                Command.Play -> play()
                is Command.Seek -> seek(command.positionMs)
                Command.GoLive -> goLive(failedFallsBackToPlay = true)
                Command.Retry -> retryNow()
                Command.ChannelChanged -> reset()
                Command.LiveStarted -> applyEnginePlan(paused = false)
                Command.Dispose -> {
                    reset()
                    disposed = true
                    ownJob.cancel()
                }
                Command.Tick -> tick()
                is Command.Capture -> onCapture(command)
                is Command.Prepared -> onPrepared(command)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // An engine port failed under a command: never leave the viewer on a spinner.
            val now = state.value
            // After the live source was released the picture is frozen on nothing: that is a failure the
            // viewer must see (and Retry), not a quiet Off.
            if (now is LivePauseState.Preparing || now is LivePauseState.Shifted ||
                (sourceReleased && now == LivePauseState.Off)
            ) {
                fail(LivePauseFailure.PROVIDER_REFUSED)
            }
        }
    }

    // ---- pause

    private suspend fun pause() {
        when (val current = state.value) {
            is LivePauseState.Preparing, is LivePauseState.Failed -> return
            is LivePauseState.Shifted -> if (!current.paused) {
                engine.pause()
                publishShifted(paused = true)
            }
            LivePauseState.Off -> startPause()
        }
    }

    private suspend fun startPause() {
        mechanism = mechanismNow()
        when (mechanism) {
            LivePauseMechanism.UNAVAILABLE -> engine.pause()
            LivePauseMechanism.PROVIDER_CATCHUP -> {
                pauseMono = monotonicMs()
                pauseWall = wallMs()
                catchUpBaseWall = null
                engine.pause()
                enterShifted(paused = true)
            }
            LivePauseMechanism.ENGINE_BACK_BUFFER -> {
                applyEnginePlan(paused = true)
                engine.pause()
                enterShifted(paused = true)
            }
            LivePauseMechanism.DISK_BUFFER -> startDiskBuffer()
        }
    }

    private suspend fun startDiskBuffer() {
        val decision = LivePausePolicy.diskStart(withContext(io) { env.freeBytes() }, env.facts().bitrateBps, env.keepMs)
        engine.pause()
        if (decision !is LivePausePolicy.StartDecision.Start) {
            // Refused before anything was touched: the live source is still open.
            mutableState.value = LivePauseState.Failed(LivePauseFailure.NO_SPACE)
            return
        }
        sourceReleased = true
        engine.releaseLiveSource()
        captureWindow = LiveCaptureEvent.Window(0, 0, false)
        val gen = ++generation
        val readyForThis = CompletableDeferred<String>()
        ready = readyForThis
        captureFailure = null
        val spec =
            LiveCaptureSpec(
                instanceId = instanceId,
                minter = minter,
                singleUseLink = env.singleUseLink(),
                keepMs = decision.keepMs,
                bitrateBps = env.facts().bitrateBps,
                maxCaptureBitrateBps = LivePausePolicy.maxCaptureBitrateBps(env.lowMemoryTier()),
            )
        // deleteRecursively + mkdirs: disk work, so off the actor's (Main) thread. Not cancellable: a capture
        // that was created must reach `capture` so the teardown can delete it.
        val created = withContext(NonCancellable + io) { captureFactory.create(spec, scope) }
        capture = created
        eventsJob = scope.launch { created.events.collect { post(Command.Capture(gen, it)) } }
        mutableState.value = LivePauseState.Preparing(BoundedLoad.begin(LoadSurface.LIVE_PAUSE))
        prepareJob = scope.launch {
            val outcome = BoundedLoad.run(LoadSurface.LIVE_PAUSE, report = mapOf("mechanism" to "disk")) {
                // The player just spent its connection (and, for a single-use link, its URL): let the
                // provider see it end before the capture opens its own.
                delay(CAPTURE_OPEN_DELAY_MS)
                created.start()
                readyForThis.await()
            }
            post(Command.Prepared(gen, outcome))
        }
    }

    private fun onCapture(command: Command.Capture) {
        if (command.generation != generation) return
        when (val event = command.event) {
            is LiveCaptureEvent.Ready -> ready?.complete(event.playlistUri)
            is LiveCaptureEvent.Window -> captureWindow = event
            is LiveCaptureEvent.Failed -> {
                val pending = ready
                if (state.value is LivePauseState.Preparing && pending != null && !pending.isCompleted) {
                    pending.completeExceptionally(CaptureFailure(event.reason))
                } else if (state.value is LivePauseState.Preparing) {
                    // Ready already went out: remember it, onPrepared must not shift onto a dead capture.
                    captureFailure = event.reason
                } else if (state.value is LivePauseState.Shifted) {
                    fail(event.reason)
                }
            }
        }
    }

    private suspend fun onPrepared(command: Command.Prepared) {
        if (command.generation != generation || state.value !is LivePauseState.Preparing) return
        when (val outcome = command.outcome) {
            is LoadOutcome.Loaded -> {
                val late = captureFailure
                if (late != null) {
                    fail(late)
                } else if (engine.playLocalBuffer(outcome.value)) {
                    enterShifted(paused = true)
                } else {
                    fail(LivePauseFailure.UNSUPPORTED_STREAM)
                }
            }
            is LoadOutcome.Empty -> fail(LivePauseFailure.PROVIDER_REFUSED)
            is LoadOutcome.Failed -> {
                val error = outcome.error
                fail(
                    when {
                        outcome.timedOut -> LivePauseFailure.TIMED_OUT
                        error is CaptureFailure -> error.reason
                        else -> LivePauseFailure.PROVIDER_REFUSED
                    },
                )
            }
        }
    }

    // ---- play

    private suspend fun play() {
        val current = state.value
        if (current is LivePauseState.Failed) {
            // Play on the Failed card is "get me the picture back".
            goLive(failedFallsBackToPlay = true)
            return
        }
        if (current !is LivePauseState.Shifted) {
            if (current == LivePauseState.Off) engine.play()
            return
        }
        when (mechanism) {
            LivePauseMechanism.PROVIDER_CATCHUP -> {
                if (catchUpBaseWall != null) {
                    engine.play()
                    publishShifted(paused = false)
                } else if (monotonicMs() - pauseMono < CATCH_UP_RESUME_THRESHOLD_MS) {
                    engine.play()
                    reset()
                } else {
                    openCatchUp(pauseWall)
                }
            }
            LivePauseMechanism.ENGINE_BACK_BUFFER -> {
                applyEnginePlan(paused = false)
                engine.play()
                publishShifted(paused = false)
            }
            else -> {
                engine.play()
                publishShifted(paused = false)
            }
        }
    }

    private suspend fun openCatchUp(startWall: Long) {
        val end = env.facts().programmeEndEpochMs?.takeIf { it > startWall } ?: (startWall + env.keepMs)
        if (engine.openCatchUp(startWall, end)) {
            catchUpBaseWall = startWall
            publishShifted(paused = false)
        } else {
            fail(LivePauseFailure.PROVIDER_REFUSED)
        }
    }

    // ---- seek

    private suspend fun seek(positionMs: Long) {
        val current = state.value as? LivePauseState.Shifted ?: return
        val target = positionMs.coerceIn(current.windowStartMs, maxOf(current.windowStartMs, current.windowEndMs))
        if (mechanism == LivePauseMechanism.PROVIDER_CATCHUP) {
            val base = catchUpBaseWall
            if (base == null) {
                openCatchUp(pauseWall + target)
            } else {
                engine.seekTo((target - (base - pauseWall)).coerceAtLeast(0L))
                publishShifted(paused = current.paused, positionOverride = target)
            }
            return
        }
        engine.seekTo(target)
        publishShifted(paused = current.paused, positionOverride = target)
    }

    // ---- live

    private suspend fun goLive(failedFallsBackToPlay: Boolean) {
        val current = state.value
        if (current == LivePauseState.Off) {
            engine.play()
            return
        }
        val touchedSource = sourceReleased || catchUpBaseWall != null || current !is LivePauseState.Failed
        val action = LivePausePolicy.goLive(mechanism, captureHealthy = engine.window() != null)
        val edge = engine.window()?.endMs
        val wasEngine = mechanism == LivePauseMechanism.ENGINE_BACK_BUFFER
        reset()
        when {
            action == LivePausePolicy.GoLiveAction.SEEK_TO_EDGE && edge != null -> {
                engine.seekTo(edge)
                engine.play()
                if (wasEngine) applyEnginePlan(paused = false)
            }
            touchedSource -> engine.retune()
            failedFallsBackToPlay -> engine.play()
        }
    }

    private suspend fun retryNow() {
        if (state.value !is LivePauseState.Failed) return
        goLive(failedFallsBackToPlay = true)
        pause()
    }

    // ---- shifted state

    private suspend fun enterShifted(paused: Boolean) {
        publishShifted(paused)
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(tickMs)
                post(Command.Tick)
            }
        }
    }

    private fun publishShifted(paused: Boolean, positionOverride: Long? = null) {
        val previous = state.value as? LivePauseState.Shifted
        val window: LivePauseWindow? = if (mechanism == LivePauseMechanism.PROVIDER_CATCHUP) {
            val end = (wallMs() - pauseWall).coerceAtLeast(0L)
            val base = catchUpBaseWall
            val position = if (base == null) 0L else (base - pauseWall) + (engine.window()?.positionMs ?: 0L)
            LivePauseWindow(0L, end, position.coerceIn(0L, end))
        } else {
            engine.window()
        }
        if (window == null) {
            if (previous != null) mutableState.value = previous.copy(paused = paused)
            else mutableState.value = LivePauseState.Shifted(paused, 0, 0, 0, 0, captureWindow.bufferFull)
            return
        }
        val position = (positionOverride ?: window.positionMs).coerceIn(window.startMs, maxOf(window.startMs, window.endMs))
        mutableState.value = LivePauseState.Shifted(
            paused = paused,
            behindLiveMs = (window.endMs - position).coerceAtLeast(0L),
            windowStartMs = window.startMs,
            windowEndMs = window.endMs,
            positionMs = position,
            bufferFull = captureWindow.bufferFull,
        )
    }

    private suspend fun tick() {
        val current = state.value as? LivePauseState.Shifted ?: return
        publishShifted(paused = current.paused)
        val updated = state.value as? LivePauseState.Shifted ?: return
        val canCatchUp = mechanism == LivePauseMechanism.ENGINE_BACK_BUFFER ||
            (mechanism == LivePauseMechanism.PROVIDER_CATCHUP && catchUpBaseWall != null)
        if (canCatchUp && !updated.paused && updated.behindLiveMs <= AUTO_LIVE_MS) goLive(failedFallsBackToPlay = false)
    }

    private fun mechanismNow(): LivePauseMechanism {
        val f = env.facts()
        return LivePausePolicy.mechanism(
            LivePauseChannel(f.isLive, f.isCatchUpPlayback, f.hasProviderArchive),
            LivePauseCapabilities(f.diskBuffer, f.engineBackBuffer),
        )
    }

    // ---- engine caches (mpv)

    private suspend fun applyEnginePlan(paused: Boolean) {
        val mech = if (state.value == LivePauseState.Off && !paused) {
            mechanismNow()
        } else {
            mechanism
        }
        if (mech != LivePauseMechanism.ENGINE_BACK_BUFFER) return
        val budget = env.engineMemoryBudgetBytes()
        val normal = env.normalEnginePlan()
        // Never raise a cache the device cannot afford: pause still pauses, rewind is whatever the normal
        // back buffer holds.
        if (budget < normal.maxBytes + normal.maxBackBytes) return
        val plan = if (paused) {
            LivePausePolicy.enginePausedPlan(budget, normal, env.facts().bitrateBps, env.keepMs)
        } else {
            LivePausePolicy.engineLivePlan(budget, normal, env.facts().bitrateBps, env.keepMs)
        }
        engine.setLiveCacheBudget(plan.maxBytes, plan.maxBackBytes, plan.cacheSecs)
    }

    // ---- teardown

    private fun fail(reason: LivePauseFailure) {
        disposeCapture()
        tickJob?.cancel()
        tickJob = null
        mutableState.value = LivePauseState.Failed(reason)
    }

    private fun disposeCapture() {
        eventsJob?.cancel()
        prepareJob?.cancel()
        eventsJob = null
        prepareJob = null
        ready = null
        captureFailure = null
        capture?.dispose()
        capture = null
        generation++
    }

    private fun reset() {
        disposeCapture()
        tickJob?.cancel()
        tickJob = null
        sourceReleased = false
        catchUpBaseWall = null
        mechanism = LivePauseMechanism.UNAVAILABLE
        mutableState.value = LivePauseState.Off
    }

    companion object {
        const val CAPTURE_OPEN_DELAY_MS = 1_500L
        const val CATCH_UP_RESUME_THRESHOLD_MS = 20_000L
        const val AUTO_LIVE_MS = 1_500L
        const val TICK_MS = 500L
    }
}
