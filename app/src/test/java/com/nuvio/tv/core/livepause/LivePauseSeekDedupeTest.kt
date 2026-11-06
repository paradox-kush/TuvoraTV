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
class LivePauseSeekDedupeTest {
    private val saved = BoundedLoad.sink
    @Before fun quiet() { BoundedLoad.sink = { _, _ -> } }
    @After fun restore() { BoundedLoad.sink = saved }

    @Test
    fun `a held key clamped at the window end seeks once not once per repeat`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        toShifted(rig)

        repeat(5) { rig.controller.seekInWindow(99_000) }
        runCurrent()

        assertEquals("one real seek to the clamped end", 1, rig.engine.calls.count { it == "seek:20000" })
    }

    @Test
    fun `a different target after the same one still seeks`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        toShifted(rig)

        rig.controller.seekInWindow(99_000)
        rig.controller.seekInWindow(10_000)
        rig.controller.seekInWindow(99_000)
        runCurrent()

        assertEquals(listOf("seek:20000", "seek:10000", "seek:20000"), rig.engine.calls.filter { it.startsWith("seek:") })
    }
}
