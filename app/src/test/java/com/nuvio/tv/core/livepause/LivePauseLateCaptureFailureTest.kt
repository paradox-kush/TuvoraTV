package com.nuvio.tv.core.livepause

import com.nuvio.tv.core.iptv.BoundedLoad
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LivePauseLateCaptureFailureTest {
    private val saved = BoundedLoad.sink
    @Before fun quiet() { BoundedLoad.sink = { _, _ -> } }
    @After fun restore() { BoundedLoad.sink = saved }

    @Test
    fun `a capture that fails right after its first piece never shifts onto the dead buffer`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        rig.controller.onPause()
        runCurrent()
        advanceTimeBy(LivePauseController.CAPTURE_OPEN_DELAY_MS + 1)
        runCurrent()

        // Ready and Failed land back to back, before the Prepared result is processed.
        rig.capture.flow.emit(LiveCaptureEvent.Ready("file:///a/index.m3u8"))
        rig.capture.flow.emit(LiveCaptureEvent.Failed(LivePauseFailure.STALLED))
        runCurrent()

        assertEquals(LivePauseState.Failed(LivePauseFailure.STALLED), rig.state)
        assertFalse("the dead buffer is never handed to the player", rig.engine.calls.any { it.startsWith("local:") })
    }
}
