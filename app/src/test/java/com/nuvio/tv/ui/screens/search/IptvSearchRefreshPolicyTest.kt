package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.ui.screens.search.IptvSearchRefreshPolicy.Action
import com.nuvio.tv.ui.screens.search.IptvSearchRefreshPolicy.ShownSearch
import org.junit.Assert.assertEquals
import org.junit.Test

/** UX15: what the Search screen does when the IPTV source set changes under a shown result. */
class IptvSearchRefreshPolicyTest {

    private val shown = ShownSearch(query = "sky", iptvSignature = "sig-A")

    @Test
    fun `changed IPTV settings refresh only the IPTV rows of the shown search`() {
        assertEquals("only the IPTV lane re-runs — no addon refetch", Action.REFRESH_IPTV_ROWS,
            IptvSearchRefreshPolicy.onSourcesChanged(shown, displayedQuery = "sky", current = "sig-B"))
    }

    @Test
    fun `an unchanged source set does nothing`() {
        assertEquals("same signature", Action.NONE,
            IptvSearchRefreshPolicy.onSourcesChanged(shown, displayedQuery = "sky", current = "sig-A"))
    }

    @Test
    fun `nothing shown means nothing to refresh`() {
        assertEquals("no search yet", Action.NONE,
            IptvSearchRefreshPolicy.onSourcesChanged(null, displayedQuery = "", current = "sig-B"))
        assertEquals("query too short to have results", Action.NONE,
            IptvSearchRefreshPolicy.onSourcesChanged(ShownSearch("s", "sig-A"), displayedQuery = "s", current = "sig-B"))
    }

    @Test
    fun `a search the screen no longer shows is left alone`() {
        assertEquals("user moved on to another query", Action.NONE,
            IptvSearchRefreshPolicy.onSourcesChanged(shown, displayedQuery = "bbc", current = "sig-B"))
    }

    @Test
    fun `the IPTV lane appearing or disappearing reruns the whole search`() {
        assertEquals("first playlist enabled", Action.RERUN_SEARCH,
            IptvSearchRefreshPolicy.onSourcesChanged(ShownSearch("sky", null), displayedQuery = "sky", current = "sig-B"))
        assertEquals("last playlist disabled", Action.RERUN_SEARCH,
            IptvSearchRefreshPolicy.onSourcesChanged(shown, displayedQuery = "sky", current = null))
    }

    @Test
    fun `no IPTV before and after does nothing`() {
        assertEquals("still none", Action.NONE,
            IptvSearchRefreshPolicy.onSourcesChanged(ShownSearch("sky", null), displayedQuery = "sky", current = null))
    }
}
