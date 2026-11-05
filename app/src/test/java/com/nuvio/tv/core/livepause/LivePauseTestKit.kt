package com.nuvio.tv.core.livepause

import com.nuvio.tv.core.contracts.LivePauseEngine
import com.nuvio.tv.core.contracts.LivePauseEngineFacts
import com.nuvio.tv.core.contracts.LivePauseWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.io.IOException
import kotlin.coroutines.CoroutineContext

// Shared fakes for the review-fix tests (the original LivePauseControllerTest keeps its own private ones).

internal class KitEngine : LivePauseEngine {
    val calls = mutableListOf<String>()
    var window: LivePauseWindow? = null
    /** When set, playLocalBuffer suspends here until completed. */
    var localGate: CompletableDeferred<Unit>? = null
    var facts = LivePauseEngineFacts(true, false, false, true, false, 4_000_000L, null)

    override suspend fun pause() { calls += "pause" }
    override suspend fun play() { calls += "play" }
    override suspend fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
    override fun window(): LivePauseWindow? = window
    override suspend fun setLiveCacheBudget(maxBytes: Long, maxBackBytes: Long, cacheSecs: Long) { calls += "budget" }
    override suspend fun releaseLiveSource() { calls += "release" }
    override suspend fun playLocalBuffer(playlistUri: String): Boolean {
        calls += "local:$playlistUri"
        localGate?.await()
        return true
    }
    override suspend fun retune() { calls += "retune" }
    override suspend fun openCatchUp(startEpochMs: Long, endEpochMs: Long): Boolean { calls += "catchup"; return true }
    override fun facts() = facts
}

internal class KitCapture(val spec: LiveCaptureSpec) : LiveCapture {
    val flow = MutableSharedFlow<LiveCaptureEvent>(replay = 16, extraBufferCapacity = 16)
    var started = false
    var disposed = false
    override val events = flow
    override fun start() {
        started = true
        // A start is a mint: it draws from the shared budget like the real capture's open does.
        spec.linkBudget.tryAcquire()
    }
    override fun dispose() { disposed = true }
}

/** Runs every block inside [inside] = true, so a probe can tell IO work from actor work. */
internal class ProbeDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    @Volatile var inside = false
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context, Runnable {
            inside = true
            try { block.run() } finally { inside = false }
        })
    }
}

internal class KitRig(parent: CoroutineScope, io: CoroutineDispatcher, probe: ProbeDispatcher? = null) {
    val engine = KitEngine()
    val captures = mutableListOf<KitCapture>()
    var free = 10L * 1024 * 1024 * 1024
    var createFails = false
    var singleUse = false
    val freeOnIo = mutableListOf<Boolean>()
    val createOnIo = mutableListOf<Boolean>()
    val normal = LivePausePolicy.EngineCachePlan(50L * 1024 * 1024, 25L * 1024 * 1024, 30)

    val controller = LivePauseController(
        instanceId = "player-1",
        parentScope = parent,
        engine = engine,
        captureFactory = { spec, _ ->
            createOnIo += probe?.inside ?: false
            if (createFails) throw IOException("cannot create")
            KitCapture(spec).also { captures += it }
        },
        minter = { LiveLink("http://x/live", emptyMap()) },
        env = LivePauseEnvironment(
            facts = { engine.facts },
            lowMemoryTier = { false },
            freeBytes = { freeOnIo += probe?.inside ?: false; free },
            engineMemoryBudgetBytes = { 400L * 1024 * 1024 },
            normalEnginePlan = { normal },
            singleUseLink = { singleUse },
        ),
        monotonicMs = { 0L },
        wallMs = { 1_000_000L },
        io = io,
    )
    val state get() = controller.state.value
    val capture get() = captures.last()
}

/** Pause, let the capture open, and (when [ready]) deliver the first piece. */
internal suspend fun TestScope.startCapture(rig: KitRig, ready: Boolean = false) {
    rig.controller.onPause()
    runCurrent()
    advanceTimeBy(LivePauseController.CAPTURE_OPEN_DELAY_MS + 1)
    runCurrent()
    if (ready) {
        rig.capture.flow.emit(LiveCaptureEvent.Ready("file:///cache/live-pause/player-1/index.m3u8"))
        runCurrent()
    }
}

/** Preparing -> Ready -> Shifted(paused) with a 0..20 s window. */
internal suspend fun TestScope.toShifted(rig: KitRig) {
    rig.engine.window = LivePauseWindow(0, 20_000, 0)
    startCapture(rig, ready = true)
}
