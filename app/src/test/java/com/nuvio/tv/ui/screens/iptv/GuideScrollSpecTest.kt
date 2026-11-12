package com.nuvio.tv.ui.screens.iptv

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.ExperimentalFoundationApi
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B56. JUnit here, so the argument order is (message, expected, actual). */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
class GuideScrollSpecTest {

    private val guide = GuideScrollSpec.around(NuvioScrollDefaults.smoothScrollSpec)

    /** Share of a one-row scroll the spring has covered [ms] after the move. */
    private fun doneAfter(spec: AnimationSpec<Float>, ms: Long, rowPx: Float = 120f): Float =
        TargetBasedAnimation(spec, Float.VectorConverter, 0f, rowPx).getValueFromNanos(ms * 1_000_000L) / rowPx

    @Test
    fun `a row is mostly in place before the next held-key move is accepted`() {
        val done = doneAfter(guide.scrollAnimationSpec, GuideKeyRepeatPolicy.VERTICAL_GATE_MS)
        assertTrue(
            "the guide scroll must cover 90% of a row within one gated repeat, covered ${"%.2f".format(done)}",
            done >= 0.9f,
        )
    }

    @Test
    fun `the app-wide spring would trail a held key`() {
        // Pins why the guide does not inherit it: about 44% of a row per gated repeat.
        val done = doneAfter(NuvioScrollDefaults.smoothScrollSpec.scrollAnimationSpec, GuideKeyRepeatPolicy.VERTICAL_GATE_MS)
        assertTrue("app-wide spring covered ${"%.2f".format(done)}", done < 0.6f)
    }

    @Test
    fun `the guide keeps the app's distance rule`() {
        listOf(
            Triple(0f, 60f, 600f), Triple(500f, 60f, 600f), Triple(-80f, 60f, 600f), Triple(240f, 60f, 600f),
        ).forEach { (offset, size, container) ->
            assertEquals(
                "same pivot for offset=$offset",
                NuvioScrollDefaults.smoothScrollSpec.calculateScrollDistance(offset, size, container),
                guide.calculateScrollDistance(offset, size, container),
                0.0001f,
            )
        }
    }
}
