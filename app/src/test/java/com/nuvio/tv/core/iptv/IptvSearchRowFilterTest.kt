package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX44: provider heading/divider rows ("==== Sky Germany ====") never surface as search hits.
 * TV twin of NuvioMobile's IptvSearchRowFilterTest (same cases; JUnit: message first).
 */
class IptvSearchRowFilterTest {

    @Test
    fun `a name framed by decoration runs is a divider`() {
        listOf(
            "==== Sky Germany ====",
            "##### UK SPORTS #####",
            "----- Movies -----",
            "*** DE ***",
            "#### 4K ####",
            "====Sky Germany====",
            "== A ==",
            "==== Sky Germany ----",
            "  --- 24/7 ---  ",
            "=== |DE| Sky Cinema ===",
        ).forEach { assertTrue("expected divider: '$it'", IptvSearchRowFilter.isDivider(it)) }
    }

    @Test
    fun `a name made only of decoration is a divider`() {
        listOf("==========", "**", "#### ####", "-----").forEach {
            assertTrue("expected divider: '$it'", IptvSearchRowFilter.isDivider(it))
        }
    }

    @Test
    fun `real channels and titles are never dividers`() {
        listOf(
            "4K | Sky Sports",
            "#1 Movie",
            "Sky Sports F1",
            "M*A*S*H",
            "*batteries not included",
            "Sky Sports -- HD",
            "-- Sky News",
            "Sky News --",
            "## Sky Cinema",
            "= Sky =",
            "#Hashtag#",
            "1 = 2",
            "The #### Show",
            "Movie (2019) **",
            "-",
            "",
            "   ",
        ).forEach { assertFalse("expected a real row: '$it'", IptvSearchRowFilter.isDivider(it)) }
    }

    @Test
    fun `withoutDividers keeps real rows in order`() {
        val rows = listOf("==== Sky Germany ====", "Sky Sport 1", "#1 Movie", "----- UK -----", "4K | Sky Sports")
        assertEquals(
            "dividers dropped, real rows kept in playlist order",
            listOf("Sky Sport 1", "#1 Movie", "4K | Sky Sports"),
            IptvSearchRowFilter.withoutDividers(rows) { it },
        )
    }

    @Test
    fun `channel search never returns a divider row`() {
        val channels = listOf("==== Sky Germany ====", "DE: Sky Sport 1", "DE: Sky Cinema")
        assertEquals(
            "the 'Sky Germany' heading matches the words but is not a channel",
            listOf("DE: Sky Sport 1", "DE: Sky Cinema"),
            IptvChannelSearchPolicy.search(channels, "sky") { it },
        )
    }

    @Test
    fun `search rows drop divider hits in every row and drop a row left empty`() {
        fun hit(name: String, live: Boolean = false) =
            XtreamSearchIndex.Hit("id:$name", name, null, isLive = live, streamUrl = null, detailType = "movie")
        val rows = XtreamIptvSearchProvider.rowsOf(
            XtreamSearchIndex.Results(
                channels = listOf(hit("##### SKY DE #####", live = true), hit("4K | Sky Sports", live = true)),
                movies = listOf(hit("==== NETFLIX ===="), hit("#1 Movie")),
                series = listOf(hit("----- Series -----")),
            )
        )
        assertEquals("series row held only a divider, so it is gone", listOf("xtream_channels", "xtream_movies"), rows.map { it.catalogId })
        assertEquals("real hits survive", listOf("4K | Sky Sports", "#1 Movie"), rows.flatMap { r -> r.hits.map { it.name } })
    }
}
