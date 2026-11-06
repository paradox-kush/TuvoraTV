package com.nuvio.tv.core.livepause

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.nuvio.tv.core.iptv.BoundedLoad
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A zap while a pause command is still inside the engine must stop that command for the channel left. */
@OptIn(ExperimentalCoroutinesApi::class)
class LivePauseZapMidCommandTest {
    private val saved = BoundedLoad.sink
    @Before fun quiet() { BoundedLoad.sink = { _, _ -> } }
    @After fun restore() { BoundedLoad.sink = saved }

    @Test
    fun `a zap while Pause is releasing the live source never opens a capture for the old channel`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rig = KitRig(CoroutineScope(Job() + dispatcher), dispatcher)
        rig.engine.releaseGate = CompletableDeferred()

        rig.controller.onPause()
        runCurrent()
        assertTrue("the actor is inside releaseLiveSource", "release" in rig.engine.calls)

        rig.controller.onChannelChanged()
        rig.engine.releaseGate!!.complete(Unit)
        runCurrent()
        advanceTimeBy(LivePauseController.CAPTURE_OPEN_DELAY_MS + 1)
        runCurrent()

        assertEquals("no capture (no mint) for the channel that was left", 0, rig.captures.size)
        assertEquals(LivePauseState.Off, rig.state)
    }

    @Test
    fun `a Pause queued behind a running command is dropped by a zap`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rig = KitRig(CoroutineScope(Job() + dispatcher), dispatcher)
        rig.engine.releaseGate = CompletableDeferred()
        rig.controller.onPause()
        runCurrent()
        rig.controller.onPlay() // queued for the old channel
        rig.controller.onChannelChanged()
        rig.engine.releaseGate!!.complete(Unit)
        runCurrent()
        assertEquals("the queued Play for the old channel never ran", false, "play" in rig.engine.calls)
    }

    @Test
    fun `a zap while Prepared is inside playLocalBuffer never shifts onto the old buffer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rig = KitRig(CoroutineScope(Job() + dispatcher), dispatcher)
        rig.engine.window = com.nuvio.tv.core.contracts.LivePauseWindow(0, 20_000, 0)
        rig.engine.localGate = CompletableDeferred()
        val seen = mutableListOf<LivePauseState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.controller.state.collect { seen += it } }

        startCapture(rig, ready = true)
        assertTrue("Prepared is inside playLocalBuffer", rig.engine.calls.any { it.startsWith("local:") })

        rig.controller.onChannelChanged()
        rig.engine.localGate!!.complete(Unit)
        runCurrent()

        assertTrue("the old buffer was never shifted onto: $seen", seen.none { it is LivePauseState.Shifted })
        assertEquals(LivePauseState.Off, rig.state)
    }

    @Test
    fun `a Tick queued behind a running command does not touch the engine after a zap`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rig = KitRig(CoroutineScope(Job() + dispatcher), dispatcher)
        rig.engine.facts = rig.engine.facts.copy(diskBuffer = false, engineBackBuffer = true)
        rig.engine.window = com.nuvio.tv.core.contracts.LivePauseWindow(0, 20_000, 5_000)
        rig.controller.onPause()
        runCurrent()
        rig.controller.onPlay()
        runCurrent()
        rig.engine.seekGate = CompletableDeferred()
        rig.controller.seekInWindow(6_000)
        runCurrent()
        // The player is now at the live edge: a tick would auto-go-live and touch the engine.
        rig.engine.window = com.nuvio.tv.core.contracts.LivePauseWindow(0, 20_000, 19_500)
        advanceTimeBy(LivePauseController.TICK_MS + 1)
        runCurrent()

        rig.controller.onChannelChanged()
        runCurrent()
        val callsAtZap = rig.engine.calls.size
        rig.engine.seekGate!!.complete(Unit)
        advanceTimeBy(LivePauseController.TICK_MS * 3)
        runCurrent()

        assertEquals("nothing reached the engine for the old channel after the zap", callsAtZap, rig.engine.calls.size)
    }

    @Test
    fun `a Pause cancelled while its capture is being created still disposes that capture`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val parent = CoroutineScope(Job() + dispatcher)
        // io is a different dispatcher from the actor's, as in production: the result comes back through a dispatch.
        val rig = KitRig(parent, StandardTestDispatcher(testScheduler))
        rig.onCreate = { parent.cancel() }

        rig.controller.onPause()
        runCurrent()
        advanceTimeBy(LivePauseController.CAPTURE_OPEN_DELAY_MS + 1)
        runCurrent()

        assertEquals("one capture was created", 1, rig.captures.size)
        assertTrue("and its folder was cleaned up", rig.captures[0].disposed)
    }
}
