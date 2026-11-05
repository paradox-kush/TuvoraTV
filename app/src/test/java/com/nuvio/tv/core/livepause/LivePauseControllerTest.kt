package com.nuvio.tv.core.livepause

import com.nuvio.tv.core.contracts.LivePauseEngine
import com.nuvio.tv.core.contracts.LivePauseEngineFacts
import com.nuvio.tv.core.contracts.LivePauseWindow
import com.nuvio.tv.core.iptv.BoundedLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LivePauseControllerTest {
    private val savedSink = BoundedLoad.sink

    @Before
    fun silenceTelemetry() {
        BoundedLoad.sink = { _, _ -> }
    }

    @After
    fun restoreTelemetry() {
        BoundedLoad.sink = savedSink
    }

    // ---- fakes

    private class FakeEngine : LivePauseEngine {
        val calls = mutableListOf<String>()
        var window: LivePauseWindow? = null
        var localAccepted = true
        var catchUpAccepted = true
        val budgets = mutableListOf<LivePausePolicy.EngineCachePlan>()
        val catchUps = mutableListOf<Pair<Long, Long>>()

        override suspend fun pause() { calls += "pause" }
        override suspend fun play() { calls += "play" }
        override suspend fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
        override fun window(): LivePauseWindow? = window
        override suspend fun setLiveCacheBudget(maxBytes: Long, maxBackBytes: Long, cacheSecs: Long) {
            calls += "budget"
            budgets += LivePausePolicy.EngineCachePlan(maxBytes, maxBackBytes, cacheSecs)
        }
        override suspend fun releaseLiveSource() { calls += "release" }
        override suspend fun playLocalBuffer(playlistUri: String): Boolean { calls += "local:$playlistUri"; return localAccepted }
        override suspend fun retune() { calls += "retune" }
        var facts = LivePauseEngineFacts(true, false, false, true, false, 4_000_000L, null)
        override fun facts() = facts
        override suspend fun openCatchUp(startEpochMs: Long, endEpochMs: Long): Boolean {
            calls += "catchup"
            catchUps += startEpochMs to endEpochMs
            return catchUpAccepted
        }
    }

    private class FakeCapture : LiveCapture {
        val flow = MutableSharedFlow<LiveCaptureEvent>(replay = 16, extraBufferCapacity = 16)
        var started = false
        var disposed = false
        override val events = flow
        override fun start() { started = true }
        override fun dispose() { disposed = true }
    }

    private class Fixture(scope: CoroutineScope, testScope: TestScope) {
        val engine = FakeEngine()
        val captures = mutableListOf<FakeCapture>()
        val specs = mutableListOf<LiveCaptureSpec>()
        var free = 10L * 1024 * 1024 * 1024
        var mono = 0L
        var wall = 1_000_000L
        var budget = 400L * 1024 * 1024
        var programmeEnd: Long? = null
        var singleUse = false
        val normal = LivePausePolicy.EngineCachePlan(maxBytes = 50L * 1024 * 1024, maxBackBytes = 25L * 1024 * 1024, cacheSecs = 30)

        val controller = LivePauseController(
            instanceId = "player-1",
            parentScope = scope,
            engine = engine,
            captureFactory = { spec, _ -> specs += spec; FakeCapture().also { captures += it } },
            minter = { LiveLink("http://x/live", emptyMap()) },
            env = LivePauseEnvironment(
                facts = { engine.facts.copy(programmeEndEpochMs = programmeEnd) },
                lowMemoryTier = { false },
                freeBytes = { free },
                engineMemoryBudgetBytes = { budget },
                normalEnginePlan = { normal },
                singleUseLink = { singleUse },
            ),
            monotonicMs = { mono },
            wallMs = { wall },
            io = StandardTestDispatcher(testScope.testScheduler),
        )
        val state get() = controller.state.value
        val capture get() = captures.last()
    }

    private fun TestScope.fixture() = Fixture(backgroundScope, this)

    private suspend fun TestScope.diskToShifted(f: Fixture) {
        f.controller.onPause()
        runCurrent()
        advanceTimeBy(LivePauseController.CAPTURE_OPEN_DELAY_MS + 1)
        runCurrent()
        f.capture.flow.emit(LiveCaptureEvent.Ready("file:///cache/live-pause/player-1/index.m3u8"))
        runCurrent()
        f.engine.window = LivePauseWindow(0, 6_000, 0)
        f.controller.onPlay()
        f.controller.onPause()
        runCurrent()
    }

    // ---- DISK_BUFFER

    @Test
    fun `disk pause freezes the picture releases the live source then prepares through BoundedLoad`() = runTest {
        val f = fixture()
        f.controller.onPause()
        runCurrent()

        assertEquals("pause then release, in that order", listOf("pause", "release"), f.engine.calls)
        val preparing = f.state as LivePauseState.Preparing
        assertEquals(
            "Preparing carries the LIVE_PAUSE stall deadline",
            20_000L, preparing.status.deadlineAtMs - preparing.status.startedAtMs,
        )
        assertFalse("the capture waits for the provider to see the connection end", f.capture.started)
        advanceTimeBy(LivePauseController.CAPTURE_OPEN_DELAY_MS + 1)
        runCurrent()
        assertTrue("the capture opens after 1.5 s", f.capture.started)
        assertEquals("keyed by the player instance", "player-1", f.specs.single().instanceId)
        assertEquals("keep window", LivePausePolicy.DEFAULT_KEEP_MS, f.specs.single().keepMs)
    }

    @Test
    fun `the first piece swaps to the local buffer and the viewer lands paused at the window start`() = runTest {
        val f = fixture()
        f.controller.onPause()
        runCurrent()
        advanceTimeBy(1_600)
        runCurrent()
        f.engine.window = LivePauseWindow(0, 2_000, 0)
        f.capture.flow.emit(LiveCaptureEvent.Ready("file:///a/index.m3u8"))
        runCurrent()

        assertTrue("local playlist handed to the player", f.engine.calls.contains("local:file:///a/index.m3u8"))
        val shifted = f.state as LivePauseState.Shifted
        assertTrue("prepared paused", shifted.paused)
        assertEquals("behind live by the whole window", 2_000L, shifted.behindLiveMs)
        assertEquals("at the window start", 0L, shifted.positionMs)
    }

    @Test
    fun `play after a disk pause plays the buffer and the window keeps growing`() = runTest {
        val f = fixture()
        diskToShifted(f)
        f.controller.onPlay()
        f.engine.window = LivePauseWindow(0, 10_000, 3_000)
        runCurrent()

        val shifted = f.state as LivePauseState.Shifted
        assertFalse(shifted.paused)
        assertEquals(7_000L, shifted.behindLiveMs)
        assertEquals("play was sent to the engine", "play", f.engine.calls.last())
    }

    @Test
    fun `seekInWindow is bounded by the window`() = runTest {
        val f = fixture()
        diskToShifted(f)
        f.engine.window = LivePauseWindow(0, 20_000, 0)
        f.controller.onPlay(); f.controller.onPause(); runCurrent()
        f.controller.seekInWindow(99_000)
        runCurrent()
        assertEquals("clamped to the end", "seek:20000", f.engine.calls.last())
        f.controller.seekInWindow(-5)
        runCurrent()
        assertEquals("clamped to the start", "seek:0", f.engine.calls.last())
    }

    @Test
    fun `Live drops the buffer and retunes`() = runTest {
        val f = fixture()
        diskToShifted(f)
        f.controller.goLive()
        runCurrent()

        assertTrue("capture deleted", f.capture.disposed)
        assertEquals("retune", "retune", f.engine.calls.last())
        assertEquals(LivePauseState.Off, f.state)
    }

    @Test
    fun `a buffer that never starts ends as TIMED_OUT and is deleted`() = runTest {
        val f = fixture()
        f.controller.onPause()
        runCurrent()
        advanceTimeBy(20_001 + 20_000)
        runCurrent()

        assertEquals(LivePauseState.Failed(LivePauseFailure.TIMED_OUT), f.state)
        assertTrue("capture deleted", f.capture.disposed)
    }

    @Test
    fun `every capture failure before the first piece surfaces its reason`() = runTest {
        for (reason in LivePauseFailure.entries) {
            val f = fixture()
            f.controller.onPause()
            runCurrent()
            advanceTimeBy(1_600)
            runCurrent()
            f.capture.flow.emit(LiveCaptureEvent.Failed(reason))
            runCurrent()
            assertEquals("reason $reason", LivePauseState.Failed(reason), f.state)
            assertTrue("capture of $reason deleted", f.capture.disposed)
            f.controller.dispose()
            runCurrent()
        }
    }

    @Test
    fun `a failure after the viewer is shifted also fails with its reason`() = runTest {
        val f = fixture()
        diskToShifted(f)
        f.capture.flow.emit(LiveCaptureEvent.Failed(LivePauseFailure.STALLED))
        runCurrent()
        assertEquals(LivePauseState.Failed(LivePauseFailure.STALLED), f.state)
        assertTrue(f.capture.disposed)
    }

    @Test
    fun `a player that refuses the local buffer fails as UNSUPPORTED_STREAM`() = runTest {
        val f = fixture()
        f.engine.localAccepted = false
        f.controller.onPause()
        runCurrent()
        advanceTimeBy(1_600)
        runCurrent()
        f.capture.flow.emit(LiveCaptureEvent.Ready("file:///a/index.m3u8"))
        runCurrent()
        assertEquals(LivePauseState.Failed(LivePauseFailure.UNSUPPORTED_STREAM), f.state)
    }

    @Test
    fun `no free space refuses before the live source is touched and Live just plays`() = runTest {
        val f = fixture()
        f.free = 100L * 1024 * 1024
        f.controller.onPause()
        runCurrent()

        assertEquals(LivePauseState.Failed(LivePauseFailure.NO_SPACE), f.state)
        assertFalse("live source untouched", f.engine.calls.contains("release"))
        assertTrue("no capture was made", f.captures.isEmpty())

        f.controller.goLive()
        runCurrent()
        assertEquals("resumes the open source, no reconnect", "play", f.engine.calls.last())
        assertFalse(f.engine.calls.contains("retune"))
        assertEquals(LivePauseState.Off, f.state)
    }

    @Test
    fun `Retry after a failure restarts only the capture and keeps the live link unspent`() = runTest {
        val f = fixture()
        f.controller.onPause()
        runCurrent()
        advanceTimeBy(LivePauseController.CAPTURE_OPEN_DELAY_MS + 1)
        runCurrent()
        f.capture.flow.emit(LiveCaptureEvent.Failed(LivePauseFailure.PROVIDER_REFUSED))
        runCurrent()
        f.controller.retry()
        runCurrent()

        assertEquals("a new capture", 2, f.captures.size)
        assertTrue("the old one is gone", f.captures.first().disposed)
        assertFalse("no retune: the live source is already released and a retune would spend a link", f.engine.calls.contains("retune"))
        assertTrue(f.state is LivePauseState.Preparing)
    }

    @Test
    fun `a single use link is flagged for the capture and a channel zap disposes the buffer`() = runTest {
        val f = fixture()
        f.singleUse = true
        f.controller.onPause()
        runCurrent()
        assertTrue(f.specs.single().singleUseLink)
        f.controller.onChannelChanged()
        runCurrent()
        assertTrue("old channel's buffer deleted", f.capture.disposed)
        assertEquals(LivePauseState.Off, f.state)
        assertFalse("a zap retunes by itself; the controller does not", f.engine.calls.contains("retune"))
    }

    @Test
    fun `dispose deletes the capture`() = runTest {
        val f = fixture()
        diskToShifted(f)
        f.controller.dispose()
        runCurrent()
        assertTrue(f.capture.disposed)
        assertEquals(LivePauseState.Off, f.state)
    }

    // ---- ENGINE_BACK_BUFFER (mpv)

    private fun Fixture.mpv() {
        engine.facts = engine.facts.copy(diskBuffer = false, engineBackBuffer = true)
    }

    @Test
    fun `mpv pause raises the caches and play restores the live plan`() = runTest {
        val f = fixture().also { it.mpv() }
        f.engine.window = LivePauseWindow(0, 30_000, 30_000)
        f.controller.onPause()
        runCurrent()

        val paused = f.engine.budgets.single()
        assertTrue("forward cache raised for the pause", paused.maxBytes >= f.normal.maxBytes)
        assertEquals("pause", f.engine.calls.last())
        assertTrue(f.state is LivePauseState.Shifted)

        f.controller.onPlay()
        runCurrent()
        assertEquals("live plan restored on play", 2, f.engine.budgets.size)
        assertTrue("back buffer kept for rewind", f.engine.budgets.last().maxBackBytes >= paused.maxBackBytes)
    }

    @Test
    fun `mpv never raises caches the device cannot afford but pause still pauses`() = runTest {
        val f = fixture().also { it.mpv() }
        f.budget = f.normal.maxBytes + f.normal.maxBackBytes - 1
        f.engine.window = LivePauseWindow(0, 30_000, 30_000)
        f.controller.onPause()
        f.controller.onLiveStarted()
        runCurrent()
        assertTrue("no cache change", f.engine.budgets.isEmpty())
        assertEquals("pause", f.engine.calls.last())
    }

    @Test
    fun `mpv Live seeks to the edge when the window is healthy and retunes when it is not`() = runTest {
        val f = fixture().also { it.mpv() }
        f.engine.window = LivePauseWindow(0, 30_000, 10_000)
        f.controller.onPause(); runCurrent()
        f.controller.goLive(); runCurrent()
        assertTrue(f.engine.calls.contains("seek:30000"))
        assertFalse(f.engine.calls.contains("retune"))

        val g = fixture().also { it.mpv() }
        g.engine.window = null
        g.controller.onPause(); runCurrent()
        g.controller.goLive(); runCurrent()
        assertTrue("no window: retune", g.engine.calls.contains("retune"))
    }

    @Test
    fun `mpv catching up to the edge returns to live by itself`() = runTest {
        val f = fixture().also { it.mpv() }
        f.engine.window = LivePauseWindow(0, 30_000, 5_000)
        f.controller.onPause(); runCurrent()
        f.controller.onPlay(); runCurrent()
        assertTrue((f.state as LivePauseState.Shifted).behindLiveMs > 0)
        f.engine.window = LivePauseWindow(0, 30_000, 29_500)
        advanceTimeBy(600); runCurrent()
        assertEquals(LivePauseState.Off, f.state)
    }

    // ---- PROVIDER_CATCHUP

    private fun Fixture.archive() {
        engine.facts = engine.facts.copy(hasProviderArchive = true)
    }

    @Test
    fun `provider archive resumes plainly inside twenty seconds`() = runTest {
        val f = fixture().also { it.archive() }
        f.controller.onPause(); runCurrent()
        assertTrue((f.state as LivePauseState.Shifted).paused)
        f.mono += 19_000
        f.controller.onPlay(); runCurrent()
        assertEquals("play", f.engine.calls.last())
        assertTrue("no catch-up", f.engine.catchUps.isEmpty())
        assertEquals(LivePauseState.Off, f.state)
    }

    @Test
    fun `provider archive opens catch-up from the pause instant after twenty seconds`() = runTest {
        val f = fixture().also { it.archive() }
        f.programmeEnd = f.wall + 3_600_000
        val pausedAt = f.wall
        f.controller.onPause(); runCurrent()
        f.mono += 25_000; f.wall += 25_000
        f.controller.onPlay(); runCurrent()

        assertEquals(listOf(pausedAt to pausedAt + 3_600_000), f.engine.catchUps)
        val shifted = f.state as LivePauseState.Shifted
        assertFalse(shifted.paused)
        assertEquals("25 s behind", 25_000L, shifted.behindLiveMs)

        f.controller.goLive(); runCurrent()
        assertEquals("leaving catch-up is a retune", "retune", f.engine.calls.last())
        assertEquals(LivePauseState.Off, f.state)
    }

    @Test
    fun `without a programme end the catch-up runs for the keep window`() = runTest {
        val f = fixture().also { it.archive() }
        val pausedAt = f.wall
        f.controller.onPause(); runCurrent()
        f.mono += 30_000; f.wall += 30_000
        f.controller.onPlay(); runCurrent()
        assertEquals(pausedAt + LivePausePolicy.DEFAULT_KEEP_MS, f.engine.catchUps.single().second)
    }

    // ---- not pausable

    @Test
    fun `a replay is just paused and stays Off`() = runTest {
        val f = fixture()
        f.engine.facts = f.engine.facts.copy(isLive = false, isCatchUpPlayback = true, hasProviderArchive = true)
        f.controller.onPause(); runCurrent()
        assertEquals(listOf("pause"), f.engine.calls)
        assertEquals(LivePauseState.Off, f.state)
        f.controller.onPlay(); runCurrent()
        assertEquals("play", f.engine.calls.last())
    }

    @Test
    fun `an engine that can do nothing is just paused`() = runTest {
        val f = fixture()
        f.engine.facts = f.engine.facts.copy(diskBuffer = false, engineBackBuffer = false)
        f.controller.onPause(); runCurrent()
        assertEquals(listOf("pause"), f.engine.calls)
        assertNull(f.engine.window)
    }
}
