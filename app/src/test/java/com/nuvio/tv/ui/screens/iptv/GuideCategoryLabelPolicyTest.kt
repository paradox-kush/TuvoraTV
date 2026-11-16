package com.nuvio.tv.ui.screens.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sim pass 2026-11-16 (UX): the group column's "Search" row reads "Search channels". JUnit: (message, expected, actual). */
class GuideCategoryLabelPolicyTest {

    @Test
    fun `the search row with no query reads Search channels`() {
        assertEquals(
            "a bare Search says nothing about what it searches",
            GuideCategoryLabelPolicy.Label.SearchChannels,
            GuideCategoryLabelPolicy.of(GuideSpecial.SEARCH, "Search", ""),
        )
    }

    @Test
    fun `the search row with a query names it`() {
        assertEquals("query shown", GuideCategoryLabelPolicy.Label.SearchQuery("bbc"), GuideCategoryLabelPolicy.of(GuideSpecial.SEARCH, "Search", "bbc"))
    }

    @Test
    fun `every other row keeps its own name`() {
        assertEquals("group", GuideCategoryLabelPolicy.Label.Name("Sports"), GuideCategoryLabelPolicy.of(GuideSpecial.GROUP, "Sports", "bbc"))
        assertEquals("all", GuideCategoryLabelPolicy.Label.Name("All channels"), GuideCategoryLabelPolicy.of(GuideSpecial.ALL, "All channels", ""))
    }

    @Test
    fun `the row label is a translatable string reading Search channels`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val value = Regex("name=\"iptv_guide_search_row\">([^<]*)<").find(strings)?.groupValues?.get(1)
        assertEquals("iptv_guide_search_row", "Search channels", value)
        val src = File("src/main/java/com/nuvio/tv/ui/screens/iptv/XtreamLiveGuideScreen.kt").readText()
        assertTrue("the guide draws the row label from the policy", src.contains("GuideCategoryLabelPolicy.of("))
    }
}
