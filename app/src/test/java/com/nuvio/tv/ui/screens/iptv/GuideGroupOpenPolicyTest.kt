package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.ui.screens.iptv.GuideGroupOpenPolicy.Handoff
import com.nuvio.tv.ui.screens.iptv.GuideGroupOpenPolicy.Landing
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Device pass 1, finding 2: OK / RIGHT on a group moved focus into the list still on screen (the previous group's),
 * the switch then disposed that row and focus fell to the Live TV tab. Focus now holds on the group until the
 * group's own list is in (02-guide-normal: OK or RIGHT opens the group, focus on its remembered row, else row 1;
 * on the shown group just returns to its rows; on an empty group, its one action). JUnit: (message, expected, actual).
 */
class GuideGroupOpenPolicyTest {

    @Test
    fun `opening another group holds focus on the group until its list is in`() {
        assertEquals("the list on screen belongs to the previous group", Handoff.HOLD_ON_GROUP,
            GuideGroupOpenPolicy.handoff(opensShownGroup = false, listLanded = true))
        assertEquals("still loading", Handoff.HOLD_ON_GROUP,
            GuideGroupOpenPolicy.handoff(opensShownGroup = false, listLanded = false))
    }

    @Test
    fun `the shown group with its list in just returns to its rows`() {
        assertEquals(Handoff.INTO_LIST, GuideGroupOpenPolicy.handoff(opensShownGroup = true, listLanded = true))
    }

    @Test
    fun `the shown group still loading holds too`() {
        assertEquals(Handoff.HOLD_ON_GROUP, GuideGroupOpenPolicy.handoff(opensShownGroup = true, listLanded = false))
    }

    @Test
    fun `the landing waits for the opened group's own list`() {
        assertEquals("another group still shown", Landing.WAIT, GuideGroupOpenPolicy.landing(groupShown = false, loading = false, empty = false))
        assertEquals("loading", Landing.WAIT, GuideGroupOpenPolicy.landing(groupShown = true, loading = true, empty = true))
        assertEquals("rows in", Landing.ROW, GuideGroupOpenPolicy.landing(groupShown = true, loading = false, empty = false))
    }

    @Test
    fun `an empty or failed group lands on its one action`() {
        assertEquals(Landing.LIST_ACTION, GuideGroupOpenPolicy.landing(groupShown = true, loading = false, empty = true))
    }
}
