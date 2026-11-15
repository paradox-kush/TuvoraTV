package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.GuideLayoutPreset
import org.junit.Assert.assertEquals
import org.junit.Test

/** Device pass 2 (2026-11-15). JUnit: (message, expected, actual). */
class GuideOptionsCurrentSizeTest {
    /** D3: Guide options marks the size in use, so the open panel says which one is current, focused or not. */
    @Test
    fun `the size in use is marked current`() {
        assertEquals(true, GuideOptionsPolicy.isCurrent(GuideOptionsPolicy.Row.Size(GuideLayoutPreset.STANDARD), GuideLayoutPreset.STANDARD))
        assertEquals(false, GuideOptionsPolicy.isCurrent(GuideOptionsPolicy.Row.Size(GuideLayoutPreset.STANDARD), GuideLayoutPreset.entries.first { it != GuideLayoutPreset.STANDARD }))
        assertEquals("the switch row is not a size", false, GuideOptionsPolicy.isCurrent(GuideOptionsPolicy.Row.ResumeOnOpen, GuideLayoutPreset.STANDARD))
    }
}
