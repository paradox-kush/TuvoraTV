package com.nuvio.tv.ui.screens.iptv

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * B180 regression: a zap press moves the aim and the overlay's controls state. Neither may recompose
 * the (hidden) guide body; only the overlay's own scope. Mirrors the screen's wiring using the real
 * [GuideBodyProjection] and [FullscreenControlsState].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GuideFullscreenOverlayIsolationTest {
    @get:Rule val rule = createComposeRule()

    private fun ch(i: Int) = GuideChannel(contentId = "c$i", name = "Ch $i", logo = null, streamUrl = "", streamId = i, entityId = "e$i")
    private val channels = List(5) { ch(it) }

    private val bodyPasses = AtomicInteger()
    private val rowPasses = AtomicInteger()
    private val overlayPasses = AtomicInteger()

    @Composable
    private fun Harness(flow: MutableStateFlow<LiveGuideUiState>, controls: FullscreenControlsState) {
        val bodyFlow = remember { flow.map(GuideBodyProjection::of).distinctUntilChanged() }
        val body by bodyFlow.collectAsStateWithLifecycle(initialValue = GuideBodyProjection.of(flow.value))
        SideEffect { bodyPasses.incrementAndGet() }
        // Stands in for the guide rows: reads body state only.
        Box { Row(body.channels.size) }
        Box { Overlay(flow, controls) }
    }

    @Composable
    private fun Row(count: Int) { SideEffect { rowPasses.addAndGet(0); rowPasses.incrementAndGet() }; check(count >= 0) }

    @Composable
    private fun Overlay(flow: MutableStateFlow<LiveGuideUiState>, controls: FullscreenControlsState) {
        val ui by flow.collectAsStateWithLifecycle()
        @Suppress("UNUSED_VARIABLE") val aimed = ui.focusedChannelId
        @Suppress("UNUSED_VARIABLE") val tick = controls.tick
        SideEffect { overlayPasses.incrementAndGet() }
    }

    @Test
    fun `zap presses recompose the overlay and never the guide body`() {
        val flow = MutableStateFlow(LiveGuideUiState(channels = channels, focusedChannelId = "c0"))
        val controls = FullscreenControlsState()
        rule.setContent { Harness(flow, controls) }
        rule.waitForIdle()
        val body0 = bodyPasses.get()
        val rows0 = rowPasses.get()
        val overlay0 = overlayPasses.get()

        repeat(3) { n ->
            flow.value = flow.value.copy(focusedChannelId = "c${n + 1}")
            controls.show(); controls.zapReveal = true
            rule.waitForIdle()
        }

        assertEquals("guide body must not recompose on a zap press", body0, bodyPasses.get())
        assertEquals("rows must not recompose on a zap press", rows0, rowPasses.get())
        assertTrue("overlay does recompose", overlayPasses.get() > overlay0)
    }

    @Test
    fun `projection drops only the aim`() {
        val a = LiveGuideUiState(channels = channels, focusedChannelId = "c1")
        val b = a.copy(focusedChannelId = "c2")
        assertEquals(GuideBodyProjection.of(a), GuideBodyProjection.of(b))
        assertEquals(a.copy(focusedChannelId = null), GuideBodyProjection.of(a))
    }

    @Test
    fun `tuning needs a spinner or an aim that differs from playing`() {
        assertTrue(GuideZapTuningPolicy.tuning(true, null, null))
        assertTrue(GuideZapTuningPolicy.tuning(false, "c2", "c1"))
        assertEquals(false, GuideZapTuningPolicy.tuning(false, "c1", "c1"))
        assertEquals(false, GuideZapTuningPolicy.tuning(false, null, "c1"))
    }
}
