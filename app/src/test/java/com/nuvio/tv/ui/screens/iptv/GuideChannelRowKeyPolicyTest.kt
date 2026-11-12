package com.nuvio.tv.ui.screens.iptv

import androidx.compose.ui.input.key.Key
import com.nuvio.tv.ui.screens.iptv.GuideChannelRowKeyPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B106: the guide hint says "MENU hide", but MENU on a focused channel row did nothing — the row only
 * knew RIGHT and LEFT. MENU must hide the channel there, the same as in fullscreen.
 */
class GuideChannelRowKeyPolicyTest {

    private fun action(
        key: Key,
        isKeyDown: Boolean = true,
        rowFocused: Boolean = true,
        lockFocus: Boolean = false,
        timelineActive: Boolean = false,
        canExitCategory: Boolean = true,
        isFirstRow: Boolean = false,
        awayFromNow: Boolean = false,
        hasTabTarget: Boolean = true,
        repeatCount: Int = 0,
    ) = GuideChannelRowKeyPolicy.actionFor(
        key, isKeyDown, rowFocused, lockFocus, timelineActive, canExitCategory,
        isFirstRow = isFirstRow, awayFromNow = awayFromNow, hasTabTarget = hasTabTarget, repeatCount = repeatCount,
    )

    @Test
    fun `MENU on a focused channel row hides the channel`() {
        assertEquals("MENU down opens options", Action.OPEN_CHANNEL_MENU, action(Key.Menu))
    }

    @Test
    fun `the KeyUp half of MENU is consumed so it never reaches the activity`() {
        assertEquals("MENU up consumed", Action.CONSUME, action(Key.Menu, isKeyDown = false))
    }

    @Test
    fun `MENU in fullscreen is left to the root handler`() {
        // The root onPreviewKeyEvent already hides the aimed channel; the row must not hide twice.
        assertEquals("fullscreen", Action.PASS, action(Key.Menu, lockFocus = true))
    }

    @Test
    fun `MENU while a programme cell has focus does not hide the row's channel`() {
        assertEquals("timeline active", Action.PASS, action(Key.Menu, timelineActive = true))
        assertEquals("row only an ancestor", Action.PASS, action(Key.Menu, rowFocused = false))
    }

    @Test
    fun `RIGHT steps into the timeline and LEFT returns to the categories`() {
        assertEquals("right", Action.ENTER_TIMELINE, action(Key.DirectionRight))
        assertEquals("left", Action.EXIT_CATEGORY, action(Key.DirectionLeft))
        assertEquals("left with no category handler", Action.PASS, action(Key.DirectionLeft, canExitCategory = false))
        // Their KeyUp halves keep falling through, as before the extraction.
        assertEquals("right up", Action.PASS, action(Key.DirectionRight, isKeyDown = false))
    }

    @Test
    fun `OK and UP DOWN are untouched so the clickable and focus search keep them`() {
        assertEquals("ok", Action.PASS, action(Key.DirectionCenter))
        assertEquals("enter", Action.PASS, action(Key.Enter))
        assertEquals("up", Action.PASS, action(Key.DirectionUp))
        assertEquals("down", Action.PASS, action(Key.DirectionDown))
    }

    // B254: UP from the first channel row did nothing, so the only way to the tabs was LEFT and up
    // through every category (landing on Search).
    @Test
    fun `fresh UP on the first row goes to the tabs`() {
        assertEquals("first row", Action.FOCUS_TABS, action(Key.DirectionUp, isFirstRow = true))
    }

    @Test
    fun `a held UP on the first row stays put instead of flying into the tabs`() {
        assertEquals("repeat", Action.CONSUME, action(Key.DirectionUp, isFirstRow = true, repeatCount = 1))
    }

    @Test
    fun `UP on other rows is plain list movement`() {
        assertEquals("not first", Action.PASS, action(Key.DirectionUp, isFirstRow = false))
        assertEquals("not first repeat", Action.PASS, action(Key.DirectionUp, isFirstRow = false, repeatCount = 3))
    }

    @Test
    fun `UP on the first row in travelled time keeps going to return-to-now`() {
        assertEquals("away", Action.PASS, action(Key.DirectionUp, isFirstRow = true, awayFromNow = true))
    }

    @Test
    fun `UP on the first row with no tab target is left alone`() {
        assertEquals("no tabs", Action.PASS, action(Key.DirectionUp, isFirstRow = true, hasTabTarget = false))
    }

    @Test
    fun `UP is never taken in fullscreen or from a timeline cell`() {
        assertEquals("fullscreen", Action.PASS, action(Key.DirectionUp, isFirstRow = true, lockFocus = true))
        assertEquals("timeline", Action.PASS, action(Key.DirectionUp, isFirstRow = true, timelineActive = true))
    }
}
