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
import org.junit.Before
import org.junit.Test

/**
 * B241b: paused on the disk buffer, the "−00:02 · Live" counter stayed where it started. The window end came
 * only from the paused player's view of the local playlist; the capture's own count of what is saved now
 * moves it on too, where it is ahead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LivePauseDiskWindowTest {
    private val saved = BoundedLoad.sink
    @Before fun quiet() { BoundedLoad.sink = { _, _ -> } }
    @After fun restore() { BoundedLoad.sink = saved }

    @Test
    fun `paused on the disk buffer the counter follows what the capture has saved`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        // The player saw only the first 2 s piece and has not reported more.
        rig.engine.window = LivePauseWindow(0, 2_000, 0)
        startCapture(rig, ready = true)
        assertEquals(2_000L, (rig.state as LivePauseState.Shifted).behindLiveMs)

        rig.capture.flow.emit(LiveCaptureEvent.Window(totalMs = 14_000, trimmedMs = 0, bufferFull = false))
        runCurrent()
        advanceTimeBy(LivePauseController.TICK_MS + 1)
        runCurrent()

        val shifted = rig.state as LivePauseState.Shifted
        assertEquals("still paused", true, shifted.paused)
        assertEquals("the window ends where the capture is", 14_000L, shifted.windowEndMs)
        assertEquals("behind live grows while paused", 14_000L, shifted.behindLiveMs)
        assertEquals("the viewer stays where they paused", 0L, shifted.positionMs)
    }

    @Test
    fun `a player that already sees further keeps its own end`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        rig.engine.window = LivePauseWindow(0, 2_000, 0)
        startCapture(rig, ready = true)
        rig.capture.flow.emit(LiveCaptureEvent.Window(totalMs = 10_000, trimmedMs = 0, bufferFull = false))
        rig.engine.window = LivePauseWindow(0, 12_000, 4_000)
        runCurrent()
        advanceTimeBy(LivePauseController.TICK_MS + 1)
        runCurrent()

        val shifted = rig.state as LivePauseState.Shifted
        assertEquals(12_000L, shifted.windowEndMs)
        assertEquals(8_000L, shifted.behindLiveMs)
    }

    @Test
    fun `the window end only ever comes from the player or the capture, never beyond both`() {
        val player = LivePauseWindow(0, 2_000, 1_000)
        assertEquals("capture ahead", LivePauseWindow(0, 9_000, 1_000), LivePausePolicy.diskWindow(player, captureTotalMs = 9_000))
        assertEquals("player ahead", player, LivePausePolicy.diskWindow(player, captureTotalMs = 1_500))
        assertEquals("no capture report yet", player, LivePausePolicy.diskWindow(player, captureTotalMs = 0))
    }

    @Test
    fun `the engine back buffer window is never stretched by a disk capture count`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        rig.engine.facts = rig.engine.facts.copy(diskBuffer = false, engineBackBuffer = true)
        rig.engine.window = LivePauseWindow(0, 5_000, 5_000)
        rig.controller.onPause()
        runCurrent()
        advanceTimeBy(LivePauseController.TICK_MS + 1)
        runCurrent()
        assertEquals(5_000L, (rig.state as LivePauseState.Shifted).windowEndMs)
    }
}
