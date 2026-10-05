package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.picture.SubtitleBoxPadding
import com.nuvio.tv.core.picture.SubtitleStyleDefaults
import com.nuvio.tv.core.picture.AspectMode
import com.nuvio.tv.core.picture.VideoZoom
import com.nuvio.tv.core.picture.VideoZoomAxis
import com.nuvio.tv.core.picture.VideoZoomPolicy
import com.nuvio.tv.core.picture.PictureMemory
import com.nuvio.tv.core.picture.PictureChoice
import com.nuvio.tv.core.picture.PlayerPreferencePolicy
import com.nuvio.tv.core.picture.SubtitleStyleMpvMapping
import com.nuvio.tv.core.picture.SubtitleSideMargin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of NuvioMobile commonTest PlayerPreferencePolicyTest / VideoZoomPolicyTest /
 * SubtitleStyleMpvMappingTest. NOTE: JUnit order is assertEquals(message, expected, actual).
 */
class PlayerPreferencePolicyTest {

    // --- F37 ---

    @Test
    fun `series picture wins over the global aspect when remembering`() {
        val stored = PictureMemory(aspectMode = "CINEMA_ZOOM", zoom = VideoZoom(scaleX = 1.2f))
        val choice = PlayerPreferencePolicy.initialPicture(true, stored, AspectMode.ORIGINAL)
        assertEquals(AspectMode.CINEMA_ZOOM, choice.aspectMode)
        assertEquals(VideoZoom(scaleX = 1.2f), choice.zoom)
    }

    @Test
    fun `no series memory falls back to the global aspect and no zoom`() {
        assertEquals(
            PictureChoice(AspectMode.STRETCH, VideoZoom.IDENTITY),
            PlayerPreferencePolicy.initialPicture(true, null, AspectMode.STRETCH),
        )
    }

    @Test
    fun `remember off ignores the series memory`() {
        val stored = PictureMemory(aspectMode = "FULL_SCREEN", zoom = VideoZoom(scaleX = 1.5f))
        assertEquals(
            PictureChoice(AspectMode.ORIGINAL, VideoZoom.IDENTITY),
            PlayerPreferencePolicy.initialPicture(false, stored, AspectMode.ORIGINAL),
        )
        assertNull(PlayerPreferencePolicy.seriesMemory(false, stored))
    }

    @Test
    fun `an aspect name this build lacks falls back to the global aspect`() {
        val choice = PlayerPreferencePolicy.initialPicture(true, PictureMemory(aspectMode = "Panorama"), AspectMode.ORIGINAL)
        assertEquals(AspectMode.ORIGINAL, choice.aspectMode)
    }

    @Test
    fun `in-player choices persist only when remembering with a series key`() {
        assertTrue(PlayerPreferencePolicy.persistsSeriesChoice(true, "tt0388629"))
        assertFalse(PlayerPreferencePolicy.persistsSeriesChoice(false, "tt0388629"))
        assertFalse(PlayerPreferencePolicy.persistsSeriesChoice(true, " "))
        assertFalse(PlayerPreferencePolicy.persistsSeriesChoice(true, null))
    }

    @Test
    fun `identity zoom is stored as no zoom`() {
        assertNull(PlayerPreferencePolicy.pictureMemory(AspectMode.ORIGINAL, VideoZoom.IDENTITY).zoom)
        assertEquals("STRETCH", PlayerPreferencePolicy.pictureMemory(AspectMode.STRETCH, VideoZoom()).aspectMode)
        assertEquals(VideoZoom(scaleY = 1.1f), PlayerPreferencePolicy.pictureMemory(AspectMode.ORIGINAL, VideoZoom(scaleY = 1.1f)).zoom)
    }

    // --- F36 ---

    @Test
    fun `zoom steps are exact and do not drift`() {
        var zoom = VideoZoom.IDENTITY
        repeat(7) { zoom = VideoZoomPolicy.adjust(zoom, VideoZoomAxis.Width, +1) }
        assertEquals(1.35f, zoom.scaleX, 0f)
        repeat(7) { zoom = VideoZoomPolicy.adjust(zoom, VideoZoomAxis.Width, -1) }
        assertTrue(zoom.isIdentity)
    }

    @Test
    fun `zoom and pan are clamped`() {
        assertEquals(VideoZoomPolicy.MAX_SCALE, VideoZoomPolicy.adjust(VideoZoom(), VideoZoomAxis.Both, 100).scaleY, 0f)
        assertEquals(VideoZoomPolicy.MIN_SCALE, VideoZoomPolicy.adjust(VideoZoom(), VideoZoomAxis.Height, -100).scaleY, 0f)
        assertEquals(-VideoZoomPolicy.MAX_PAN, VideoZoomPolicy.adjust(VideoZoom(), VideoZoomAxis.PanY, -100).panY, 0f)
    }

    @Test
    fun `mpv zoom properties are the full locale-free set`() {
        assertEquals(
            listOf("video-scale-x" to "1.33", "video-scale-y" to "1.00", "video-pan-x" to "-0.04", "video-pan-y" to "0.10"),
            VideoZoomPolicy.mpvProperties(VideoZoom(1.33f, 1f, -0.04f, 0.1f)),
        )
    }

    @Test
    fun `surface transform multiplies the aspect scale and pans by the on-screen picture`() {
        val t = VideoZoomPolicy.surfaceTransform(VideoZoom(scaleX = 1.5f, panX = 0.1f), 1000, 500, baseScaleX = 1.33f)
        assertEquals(1.33f * 1.5f, t.scaleX, 0.0001f)
        assertEquals(1f, t.scaleY, 0f)
        assertEquals(0.1f * 1000 * 1.33f * 1.5f, t.translationX, 0.01f)
    }

    // --- UX61 / F47 ---

    @Test
    fun `dim background uses background-box, not opaque-box`() {
        val p = SubtitleStyleMpvMapping.properties("#80000000", 0.5f, "#FF000000", 1.0, 0).toMap()
        assertEquals("background-box", p["sub-border-style"])
        assertEquals("#80000000", p["sub-back-color"])
        assertEquals(SubtitleStyleMpvMapping.BOX_PADDING, p["sub-shadow-offset"]!!.toDouble(), 0.0)
    }

    @Test
    fun `transparent background is a plain outline with no shadow`() {
        val p = SubtitleStyleMpvMapping.properties("#00000000", 0f, "#FF000000", 1.0, 0).toMap()
        assertEquals("outline-and-shadow", p["sub-border-style"])
        assertEquals("0.0", p["sub-shadow-offset"])
        assertEquals("1.0", p["sub-border-size"])
    }

    @Test
    fun `side margin is a percent of the reference width and never below the mpv default`() {
        assertEquals(64.0, SubtitleStyleMpvMapping.marginX(5), 0.0)
        assertEquals(SubtitleStyleMpvMapping.MPV_DEFAULT_MARGIN_X, SubtitleStyleMpvMapping.marginX(0), 0.0)
        assertEquals(96, SubtitleSideMargin.paddingPx(1920, 5))
    }

    // --- F47 default look (owner-approved 2026-10-04) ---

    @Test
    fun `new users get white text in a soft box with no outline and no screen margin`() {
        val style = com.nuvio.tv.data.local.SubtitleStyleSettings()
        assertEquals(android.graphics.Color.WHITE, style.textColor)
        assertFalse(style.outlineEnabled)
        assertEquals(SubtitleStyleDefaults.BOX_BACKGROUND, style.backgroundColor)
        val alpha = (style.backgroundColor ushr 24) / 255f
        assertTrue("soft translucent, not solid", alpha in 0.4f..0.75f)
        assertEquals(0, style.sideMarginPercent)
    }

    @Test
    fun `a profile with any stored style field keeps the pre-F47 look`() {
        assertEquals(SubtitleStyleDefaults.BOX_BACKGROUND, SubtitleStyleDefaults.background(anyFieldStored = false))
        assertFalse(SubtitleStyleDefaults.outlineEnabled(anyFieldStored = false))
        assertEquals(0, SubtitleStyleDefaults.background(anyFieldStored = true))
        assertTrue(SubtitleStyleDefaults.outlineEnabled(anyFieldStored = true))
    }

    @Test
    fun `the default box is padded and the exo cue gets inner padding on every line`() {
        assertTrue("visible inner padding", SubtitleStyleMpvMapping.BOX_PADDING >= 8.0)
        val pad = SubtitleBoxPadding.PAD_CHAR.toString()
        assertEquals("${pad}Hello$pad\n${pad}wide world$pad", SubtitleBoxPadding.padLines("Hello\nwide world", 1))
        assertEquals("x", SubtitleBoxPadding.padLines("x", 0))
    }
}
