package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Confirmed on the emulator (2026-10-02): clean live stretched every picture to its 16:9 box —
 * a 4:3 circle showed as a 1.33x-wide oval — because MediaCodec ignores pixel aspect when drawing
 * to a Surface and the clean surfaces are MATCH_PARENT.
 */
class LiveVideoFitPolicyTest {
    @Test
    fun `4 by 3 PAL with a 16 to 15 pixel ratio pillarboxes to 1440 of 1920`() {
        assertFit(720, 576, 16f / 15f, expectedWidthPx = 1440f, expectedHeightPx = 1080f)
    }

    @Test
    fun `square pixel 640x480 pillarboxes like any 4 by 3 picture`() {
        assertFit(640, 480, 1f, expectedWidthPx = 1440f, expectedHeightPx = 1080f)
    }

    @Test
    fun `anamorphic widescreen SD stays full width`() {
        assertFit(720, 576, 64f / 45f, expectedWidthPx = 1920f, expectedHeightPx = 1080f)
        assertFit(1440, 1080, 4f / 3f, expectedWidthPx = 1920f, expectedHeightPx = 1080f)
        assertFit(1920, 1080, 1f, expectedWidthPx = 1920f, expectedHeightPx = 1080f)
    }

    @Test
    fun `H264 PAL pixel ratios keep their true shape including overscan width`() {
        assertFit(720, 576, 12f / 11f, expectedWidthPx = 1472.7f, expectedHeightPx = 1080f)
        assertFit(720, 576, 16f / 11f, expectedWidthPx = 1920f, expectedHeightPx = 1056f)
    }

    @Test
    fun `a missing or invalid pixel ratio means square pixels`() {
        assertEquals(1.25f, LiveVideoFitPolicy.displayAspect(720, 576, 0f)!!, 0.0001f)
        assertEquals(1.25f, LiveVideoFitPolicy.displayAspect(720, 576, Float.NaN)!!, 0.0001f)
    }

    @Test
    fun `unknown dimensions keep the previous geometry`() {
        assertNull(LiveVideoFitPolicy.displayAspect(0, 576, 1f))
        assertNull(LiveVideoFitPolicy.fitScale(null, 1920, 1080))
        assertNull(LiveVideoFitPolicy.fitScale(4f / 3f, 0, 1080))
    }

    @Test
    fun `a sub one percent mismatch snaps to the full box`() {
        val scale = LiveVideoFitPolicy.fitScale(16f / 9f * 1.005f, 1920, 1080)!!
        assertEquals(1f, scale.x, 0f)
        assertEquals(1f, scale.y, 0f)
    }

    @Test
    fun `the 16 by 9 guide preview pane pillarboxes 4 by 3 too`() {
        val scale = LiveVideoFitPolicy.fitScale(4f / 3f, 640, 360)!!
        assertEquals(0.75f, scale.x, 0.0001f)
        assertEquals(1f, scale.y, 0f)
    }

    @Test
    fun `the fit is independent of box scale`() {
        assertEquals(
            LiveVideoFitPolicy.fitScale(4f / 3f, 1920, 1080),
            LiveVideoFitPolicy.fitScale(4f / 3f, 3840, 2160),
        )
    }

    private fun assertFit(
        width: Int,
        height: Int,
        pixelRatio: Float,
        expectedWidthPx: Float,
        expectedHeightPx: Float,
    ) {
        val aspect = LiveVideoFitPolicy.displayAspect(width, height, pixelRatio)
        val scale = LiveVideoFitPolicy.fitScale(aspect, 1920, 1080)!!
        assertEquals("width of ${width}x$height @$pixelRatio", expectedWidthPx, 1920f * scale.x, 0.5f)
        assertEquals("height of ${width}x$height @$pixelRatio", expectedHeightPx, 1080f * scale.y, 0.5f)
    }
}
