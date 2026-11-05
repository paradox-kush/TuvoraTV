package com.nuvio.tv.core.livepause

import com.nuvio.tv.core.iptv.BoundedLoad
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LivePausePlayInFailedTest {
    private val saved = BoundedLoad.sink
    @Before fun quiet() { BoundedLoad.sink = { _, _ -> } }
    @After fun restore() { BoundedLoad.sink = saved }

    @Test
    fun `Play on the Failed screen goes live`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        rig.free = 1L
        rig.controller.onPause()
        runCurrent()
        assertEquals(LivePauseState.Failed(LivePauseFailure.NO_SPACE), rig.state)

        rig.controller.onPlay()
        runCurrent()

        assertEquals("Play leaves the Failed card", LivePauseState.Off, rig.state)
        assertEquals("and plays", "play", rig.engine.calls.last())
    }
}
