package com.nuvio.tv.playback.host

import com.nuvio.tv.core.picture.AspectMode
import com.nuvio.tv.core.picture.SurfaceZoomTransform
import com.nuvio.tv.core.picture.VideoZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F28 (TV live aspect/zoom, S19/F36): live had no aspect or zoom control — the clean live surface
 * only ever fitted the picture. It now takes the regular player's model (AspectMode + lane F's
 * VideoZoom) on top of that fit, for every live surface: Media3 and mpv-embed surfaces are shaped by
 * the fit scale, mpv's GPU output letterboxes inside a full-box surface, and both are then scaled
 * the same way the VOD player scales its video view.
 */
class LivePictureGeometryTest {

    private val w = 1920
    private val h = 1080
    private val fourThree = 4f / 3f

    private fun t(gpu: Boolean, mode: AspectMode, zoom: VideoZoom = VideoZoom.IDENTITY, aspect: Float? = fourThree) =
        LivePictureGeometry.transform(gpuRendered = gpu, videoAspect = aspect, boxWidth = w, boxHeight = h, mode = mode, zoom = zoom)

    private fun assertTransform(msg: String, expected: SurfaceZoomTransform, actual: SurfaceZoomTransform?) {
        requireNotNull(actual) { "$msg: no transform" }
        assertEquals("$msg scaleX", expected.scaleX, actual.scaleX, 0.01f)
        assertEquals("$msg scaleY", expected.scaleY, actual.scaleY, 0.01f)
        assertEquals("$msg translationX", expected.translationX, actual.translationX, 0.5f)
        assertEquals("$msg translationY", expected.translationY, actual.translationY, 0.5f)
    }

    @Test
    fun `fit with no zoom is exactly today's live geometry`() {
        assertTransform("surface pillarbox", SurfaceZoomTransform(0.75f, 1f, 0f, 0f), t(gpu = false, mode = AspectMode.ORIGINAL))
        assertTransform("mpv gpu letterboxes itself", SurfaceZoomTransform(1f, 1f, 0f, 0f), t(gpu = true, mode = AspectMode.ORIGINAL))
    }

    @Test
    fun `crop fills the screen on both surface kinds`() {
        // 4:3 in 16:9: crop scales the picture by 16/9 / 4/3 = 1.333 uniformly.
        assertTransform("surface", SurfaceZoomTransform(1f, 1.333f, 0f, 0f), t(gpu = false, mode = AspectMode.FULL_SCREEN))
        assertTransform("gpu", SurfaceZoomTransform(1.333f, 1.333f, 0f, 0f), t(gpu = true, mode = AspectMode.FULL_SCREEN))
    }

    @Test
    fun `stretch fills the width without cropping`() {
        assertTransform("surface", SurfaceZoomTransform(1f, 1f, 0f, 0f), t(gpu = false, mode = AspectMode.STRETCH))
        assertTransform("gpu", SurfaceZoomTransform(1.333f, 1f, 0f, 0f), t(gpu = true, mode = AspectMode.STRETCH))
    }

    @Test
    fun `manual zoom multiplies the mode and pans by a share of the shown picture`() {
        val zoom = VideoZoom(scaleX = 1.2f, scaleY = 1.2f, panX = 0.1f, panY = 0f)
        assertTransform(
            "gpu fit + 120% + pan",
            SurfaceZoomTransform(1.2f, 1.2f, translationX = 0.1f * w * 1.2f, translationY = 0f),
            t(gpu = true, mode = AspectMode.ORIGINAL, zoom = zoom),
        )
    }

    @Test
    fun `an unknown picture size keeps the previous shape on a decoder surface`() {
        assertNull("surface waits for the size", t(gpu = false, mode = AspectMode.FULL_SCREEN, aspect = null))
        // mpv's GPU output fits by itself, so modes that need no size still apply.
        assertTransform("gpu slight zoom", SurfaceZoomTransform(1.15f, 1.15f, 0f, 0f), t(gpu = true, mode = AspectMode.SLIGHT_ZOOM, aspect = null))
    }

    @Test
    fun `no box yet means no transform`() {
        assertNull(LivePictureGeometry.transform(false, fourThree, 0, 0, AspectMode.ORIGINAL, VideoZoom.IDENTITY))
    }
}
