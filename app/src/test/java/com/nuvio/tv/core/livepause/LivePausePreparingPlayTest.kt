package com.nuvio.tv.core.livepause

import com.nuvio.tv.core.contracts.LivePauseWindow
import com.nuvio.tv.core.iptv.BoundedLoad
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B241a: while the buffer is still preparing the overlay shows "paused", so the next ⏯ is a Play. It used to
 * do nothing (play() ignored Preparing) and the viewer was stuck on a frozen picture until the deadline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LivePausePreparingPlayTest {
    private val saved = BoundedLoad.sink
    @Before fun quiet() { BoundedLoad.sink = { _, _ -> } }
    @After fun restore() { BoundedLoad.sink = saved }

    @Test
    fun `Play while the buffer is preparing cancels the pause and retunes live`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        startCapture(rig)
        assertTrue("preparing", rig.state is LivePauseState.Preparing)

        rig.controller.onPlay()
        runCurrent()

        assertEquals("Play leaves Preparing", LivePauseState.Off, rig.state)
        assertTrue("the half-started capture is deleted", rig.capture.disposed)
        assertEquals("the live source was released, so live is a retune", "retune", rig.engine.calls.last())
    }

    @Test
    fun `the abandoned capture's late first piece never shifts the viewer back`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        startCapture(rig)
        val abandoned = rig.capture
        rig.controller.onPlay()
        runCurrent()

        // The old capture still reports (its events were already in flight) and time passes past every deadline.
        rig.engine.window = LivePauseWindow(0, 2_000, 0)
        abandoned.flow.emit(LiveCaptureEvent.Ready("file:///cache/live-pause/player-1/index.m3u8"))
        abandoned.flow.emit(LiveCaptureEvent.Window(2_000, 0, false))
        abandoned.flow.emit(LiveCaptureEvent.Failed(LivePauseFailure.STALLED))
        runCurrent()
        advanceTimeBy(5 * 60_000L)
        runCurrent()

        assertEquals("still live", LivePauseState.Off, rig.state)
        assertFalse("the local buffer was never handed to the player", rig.engine.calls.any { it.startsWith("local:") })
    }

    @Test
    fun `Pause while preparing still does nothing`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        startCapture(rig)
        rig.controller.onPause()
        runCurrent()
        assertTrue("a second Pause keeps preparing", rig.state is LivePauseState.Preparing)
        assertFalse(rig.capture.disposed)
    }
}
