package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Change 10 (schedule pill). JUnit: (message, expected, actual). */
class GuideSchedulePillPolicyTest {
    @Test
    fun `the pill shows in the cells or away from now and nowhere else`() {
        assertEquals("rows at now", false, GuideSchedulePillPolicy.shows(inCells = false, awayFromNow = false))
        assertEquals("in the cells", true, GuideSchedulePillPolicy.shows(inCells = true, awayFromNow = false))
        assertEquals("travelled", true, GuideSchedulePillPolicy.shows(inCells = false, awayFromNow = true))
    }

    @Test
    fun `the pill names the programme under the cursor, else the window, else now`() {
        assertEquals("cell", 500L, GuideSchedulePillPolicy.timeMs(500L, 100L, 900L, awayFromNow = true))
        assertEquals("window", 100L, GuideSchedulePillPolicy.timeMs(null, 100L, 900L, awayFromNow = true))
        assertEquals("now", 900L, GuideSchedulePillPolicy.timeMs(null, 100L, 900L, awayFromNow = false))
    }
}
