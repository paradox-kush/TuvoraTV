package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsInspectionPolicyTest {
    @Test
    fun `turned-on playlists are asked as usual, off ones are inspected, portals are never touched while off`() {
        val p = DetailsInspectionPolicy
        assertEquals(DetailsInspectionPolicy.Plan.NORMAL, p.plan(XtreamAccount.SOURCE_XTREAM, availableHere = true))
        assertEquals(DetailsInspectionPolicy.Plan.INSPECT, p.plan(XtreamAccount.SOURCE_XTREAM, availableHere = false))
        assertEquals(DetailsInspectionPolicy.Plan.SKIP, p.plan(XtreamAccount.SOURCE_STALKER, availableHere = false))
        assertEquals(DetailsInspectionPolicy.Plan.NORMAL, p.plan(XtreamAccount.SOURCE_STALKER, availableHere = true))
    }
}
