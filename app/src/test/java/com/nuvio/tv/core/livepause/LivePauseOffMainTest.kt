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
class LivePauseOffMainTest {
    private val saved = BoundedLoad.sink
    @Before fun quiet() { BoundedLoad.sink = { _, _ -> } }
    @After fun restore() { BoundedLoad.sink = saved }

    @Test
    fun `the free space probe and the capture directory setup run on the IO dispatcher`() = runTest {
        val probe = ProbeDispatcher(StandardTestDispatcher(testScheduler))
        val rig = KitRig(backgroundScope, probe, probe)

        rig.controller.onPause()
        runCurrent()

        assertEquals("freeBytes ran once, on IO", listOf(true), rig.freeOnIo)
        assertEquals("the factory (deleteRecursively + mkdirs) ran once, on IO", listOf(true), rig.createOnIo)
    }
}
