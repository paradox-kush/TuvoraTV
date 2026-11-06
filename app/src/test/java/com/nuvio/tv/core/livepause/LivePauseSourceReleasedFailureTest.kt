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
class LivePauseSourceReleasedFailureTest {
    private val saved = BoundedLoad.sink
    @Before fun quiet() { BoundedLoad.sink = { _, _ -> } }
    @After fun restore() { BoundedLoad.sink = saved }

    @Test
    fun `a capture that cannot be created after the live source was released ends as Failed not a black screen`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        rig.createFails = true

        rig.controller.onPause()
        runCurrent()

        assertEquals(
            "the source is released, so the viewer must be told and offered Retry",
            LivePauseState.Failed(LivePauseFailure.PROVIDER_REFUSED), rig.state,
        )
    }
}
