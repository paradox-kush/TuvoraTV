package com.nuvio.tv.core.livepause

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.nuvio.tv.core.iptv.BoundedLoad

@OptIn(ExperimentalCoroutinesApi::class)
class LivePauseDisposalTest {
    private val saved = BoundedLoad.sink
    @Before fun quiet() { BoundedLoad.sink = { _, _ -> } }
    @After fun restore() { BoundedLoad.sink = saved }

    @Test
    fun `cancelling the parent while the actor waits in an engine call still deletes the capture`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val parent = CoroutineScope(Job() + dispatcher)
        val rig = KitRig(parent, dispatcher)
        rig.engine.localGate = CompletableDeferred()

        startCapture(rig, ready = true)
        assertTrue("the actor is inside playLocalBuffer", rig.engine.calls.any { it.startsWith("local:") })
        assertFalse("nothing disposed yet", rig.capture.disposed)

        parent.cancel()
        runCurrent()

        assertTrue("the ViewModel was released mid-command: the files must still go", rig.capture.disposed)
        assertEquals(1, rig.captures.size)
    }
}
