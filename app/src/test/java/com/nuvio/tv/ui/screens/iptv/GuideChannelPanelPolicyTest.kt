package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.ui.screens.iptv.GuideChannelPanelPolicy.Row
import com.nuvio.tv.ui.screens.iptv.GuideChannelPanelPolicy.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Redesign change 3 (04-channel-panel). JUnit: (message, expected, actual). */
class GuideChannelPanelPolicyTest {
    private val hideOnly = listOf(GuideChannelMenuPolicy.Action.HIDE)
    private val favouritesMiddle = listOf(
        GuideChannelMenuPolicy.Action.MOVE_UP, GuideChannelMenuPolicy.Action.MOVE_DOWN, GuideChannelMenuPolicy.Action.REMOVE_FAVORITE,
    )

    @Test
    fun `the channel panel opens on Watch full screen, never on a toggle (UX211)`() {
        val rows = GuideChannelPanelPolicy.rows(Variant.CHANNEL, favorite = false, hideOnly, noGuide = false, guideSizeHere = false)
        assertEquals("order", listOf(Row.WATCH_FULLSCREEN, Row.ADD_FAVORITE, Row.ADD_TO_MULTIVIEW, Row.HIDE), rows)
        assertEquals(
            "a favourite offers Remove in the same slot",
            Row.REMOVE_FAVORITE,
            GuideChannelPanelPolicy.rows(Variant.CHANNEL, favorite = true, hideOnly, false, false)[1],
        )
    }

    @Test
    fun `Favorites view walks a channel with Move up and Move down and has no separate Remove row`() {
        val rows = GuideChannelPanelPolicy.rows(Variant.CHANNEL, favorite = true, favouritesMiddle, noGuide = false, guideSizeHere = false)
        assertEquals("order", listOf(Row.WATCH_FULLSCREEN, Row.REMOVE_FAVORITE, Row.ADD_TO_MULTIVIEW, Row.MOVE_UP, Row.MOVE_DOWN), rows)
        assertTrue("moves keep the panel open", Row.MOVE_UP.keepsPanelOpen && Row.MOVE_DOWN.keepsPanelOpen)
        assertFalse("everything else closes it", Row.HIDE.keepsPanelOpen || Row.ADD_FAVORITE.keepsPanelOpen)
    }

    @Test
    fun `no guide adds Fix guide and the guide size stays reachable until Guide options holds it`() {
        val rows = GuideChannelPanelPolicy.rows(Variant.CHANNEL, favorite = false, hideOnly, noGuide = true, guideSizeHere = true)
        assertEquals("order", listOf(Row.WATCH_FULLSCREEN, Row.ADD_FAVORITE, Row.ADD_TO_MULTIVIEW, Row.FIX_GUIDE, Row.GUIDE_SIZE, Row.HIDE), rows)
    }

    @Test
    fun `Start over is never a channel-panel row (UX392)`() {
        Variant.entries.forEach { variant ->
            val rows = GuideChannelPanelPolicy.rows(variant, false, favouritesMiddle + hideOnly, true, true)
            assertFalse("variant=$variant", rows.any { it.name.contains("START") })
        }
    }

    @Test
    fun `the full-screen panel opens on Open the guide`() {
        assertEquals(
            "order",
            listOf(Row.OPEN_GUIDE, Row.ADD_FAVORITE, Row.ADD_TO_MULTIVIEW, Row.STREAM_INFO),
            GuideChannelPanelPolicy.rows(Variant.FULLSCREEN, favorite = false, hideOnly, noGuide = true, guideSizeHere = true),
        )
    }

    @Test
    fun `Add to Multiview sits right under the favourite row in both variants and closes the panel`() {
        Variant.entries.forEach { variant ->
            listOf(false, true).forEach { favorite ->
                val rows = GuideChannelPanelPolicy.rows(variant, favorite, favouritesMiddle + hideOnly, noGuide = true, guideSizeHere = true)
                assertEquals("variant=$variant favorite=$favorite", 2, rows.indexOf(Row.ADD_TO_MULTIVIEW))
            }
        }
        assertFalse("it closes the panel", Row.ADD_TO_MULTIVIEW.keepsPanelOpen)
        val rows = GuideChannelPanelPolicy.rows(Variant.CHANNEL, favorite = false, hideOnly, noGuide = false, guideSizeHere = false)
        assertEquals("its own section label", GuideChannelPanelPolicy.Section.MULTIVIEW, GuideChannelPanelPolicy.labelAbove(rows, 2))
    }

    @Test
    fun `after a move the cursor stays on the move row, or its neighbour when that row went away`() {
        val before = listOf(Row.WATCH_FULLSCREEN, Row.REMOVE_FAVORITE, Row.MOVE_UP, Row.MOVE_DOWN)
        assertEquals("still there", Row.MOVE_UP, GuideChannelPanelPolicy.focusAfter(Row.MOVE_UP, before, before))
        val atTop = listOf(Row.WATCH_FULLSCREEN, Row.REMOVE_FAVORITE, Row.MOVE_DOWN)
        assertEquals("Move up reached the top", Row.MOVE_DOWN, GuideChannelPanelPolicy.focusAfter(Row.MOVE_UP, before, atTop))
        val atBottom = listOf(Row.WATCH_FULLSCREEN, Row.REMOVE_FAVORITE, Row.MOVE_UP)
        assertEquals("Move down reached the bottom", Row.MOVE_UP, GuideChannelPanelPolicy.focusAfter(Row.MOVE_DOWN, before, atBottom))
    }

    @Test
    fun `section labels head each section after the first`() {
        val rows = listOf(Row.WATCH_FULLSCREEN, Row.REMOVE_FAVORITE, Row.MOVE_UP, Row.MOVE_DOWN, Row.HIDE)
        assertEquals(
            "labels",
            listOf(null, GuideChannelPanelPolicy.Section.FAVORITES, GuideChannelPanelPolicy.Section.ORDER, null, GuideChannelPanelPolicy.Section.TIDY),
            rows.indices.map { GuideChannelPanelPolicy.labelAbove(rows, it) },
        )
    }

    @Test
    fun `the press that opened the panel never runs its first row (UX213)`() {
        val gate = PanelKeyGate()
        assertFalse("repeat of the long OK", gate.admit(isKeyDown = true, repeatCount = 3))
        assertFalse("its KeyUp", gate.admit(isKeyDown = false, repeatCount = 0))
        assertTrue("a fresh press", gate.admit(isKeyDown = true, repeatCount = 0))
        assertTrue("its KeyUp", gate.admit(isKeyDown = false, repeatCount = 0))
    }
}
