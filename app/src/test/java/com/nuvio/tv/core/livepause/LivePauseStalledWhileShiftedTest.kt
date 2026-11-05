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

@OptIn(ExperimentalCoroutinesApi::class)
class LivePauseStalledWhileShiftedTest {
    private val saved = BoundedLoad.sink
    @Before fun quiet() { BoundedLoad.sink = { _, _ -> } }
    @After fun restore() { BoundedLoad.sink = saved }

    @Test
    fun `a capture that stalls while the viewer is behind keeps the buffer they are watching`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        toShifted(rig)

        rig.capture.flow.emit(LiveCaptureEvent.Failed(LivePauseFailure.STALLED))
        runCurrent()

        assertTrue("still playing the buffer: ${rig.state}", rig.state is LivePauseState.Shifted)
        assertFalse("the files are not deleted under the viewer", rig.capture.disposed)
    }

    @Test
    fun `when the stalled buffer runs out the viewer goes live and the files go`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        toShifted(rig)
        rig.capture.flow.emit(LiveCaptureEvent.Failed(LivePauseFailure.STALLED))
        runCurrent()
        rig.controller.onPlay()
        runCurrent()

        rig.engine.window = LivePauseWindow(0, 20_000, 19_000)
        advanceTimeBy(LivePauseController.TICK_MS * 2)
        runCurrent()

        assertEquals(LivePauseState.Off, rig.state)
        assertEquals("retune", rig.engine.calls.last())
        assertTrue(rig.capture.disposed)
    }
}
