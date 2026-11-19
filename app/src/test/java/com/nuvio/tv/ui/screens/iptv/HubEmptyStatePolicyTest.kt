package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.BoundedLoad
import com.nuvio.tv.core.iptv.LoadStatus
import com.nuvio.tv.core.iptv.LoadSurface
import org.junit.Assert.assertEquals
import org.junit.Test

/** The hub's "Nothing here yet" card: Retry is always offered; a Retry that lands empty again says so. */
class HubEmptyStatePolicyTest {

    @Test
    fun `an empty page offers Retry and shows no checked notice before any retry`() {
        val p = HubEmptyStatePolicy.forEmpty(userRetried = false, load = LoadStatus.Empty)
        assertEquals("Retry must be offered on the empty card", true, p.offersRetry)
        assertEquals("no retry has run yet, so no 'checked' line", false, p.showCheckedJustNow)
    }

    @Test
    fun `an empty page after a retry shows checked just now`() {
        val p = HubEmptyStatePolicy.forEmpty(userRetried = true, load = LoadStatus.Empty)
        assertEquals("Retry stays on offer", true, p.offersRetry)
        assertEquals("the retry came back empty again", true, p.showCheckedJustNow)
    }

    @Test
    fun `a retry that is still loading shows no checked notice`() {
        val loading = BoundedLoad.begin(LoadSurface.HUB_CATEGORIES)
        val p = HubEmptyStatePolicy.forEmpty(userRetried = true, load = loading)
        assertEquals("the answer has not landed", false, p.showCheckedJustNow)
    }

    @Test
    fun `a failed retry shows no checked notice`() {
        val p = HubEmptyStatePolicy.forEmpty(userRetried = true, load = LoadStatus.Failed(timedOut = false))
        assertEquals("a failure is not 'still empty'", false, p.showCheckedJustNow)
    }
}
