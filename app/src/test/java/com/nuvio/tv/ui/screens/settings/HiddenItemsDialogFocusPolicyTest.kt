package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.screens.settings.HiddenItemsDialogFocusPolicy.Target
import org.junit.Assert.assertEquals
import org.junit.Test

/** UX34: the Hidden channels & groups dialog opened with focus on Done instead of the first item. */
class HiddenItemsDialogFocusPolicyTest {

    @Test
    fun `a list with items focuses its first row`() {
        assertEquals("one item", Target.FIRST_ROW, HiddenItemsDialogFocusPolicy.initialFocus(1))
        assertEquals("many items", Target.FIRST_ROW, HiddenItemsDialogFocusPolicy.initialFocus(12))
    }

    @Test
    fun `an empty list focuses Done`() {
        assertEquals("empty", Target.DONE, HiddenItemsDialogFocusPolicy.initialFocus(0))
    }

    @Test
    fun `while loading focus waits for the list`() {
        assertEquals("loading", Target.NONE, HiddenItemsDialogFocusPolicy.initialFocus(null))
    }
}
