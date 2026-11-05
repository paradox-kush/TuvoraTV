package com.nuvio.tv.core.livepause

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
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
}
