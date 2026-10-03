package com.nuvio.tv.core.iptv.stalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * B02 (twin of Mobile's StalkerListParamsTest): stock Ministra reads a non-`*` `genre` on vod lists
 * as a GENRE filter, so sending the category id there emptied every Movies row. Category goes in
 * `category` for vod/series; `itv` keeps filtering by `genre`.
 */
class StalkerListParamsTest {

    @Test
    fun `a vod category goes in category with no genre filter`() {
        val p = StalkerListParams.forPage("vod", "12", null, 1)
        assertEquals("category", "12", p["category"])
        assertEquals("genre", "*", p["genre"])
        assertEquals("page", "1", p["p"])
    }

    @Test
    fun `a series category goes in category with no genre filter`() {
        val p = StalkerListParams.forPage("series", "7", null, 3)
        assertEquals("category", "7", p["category"])
        assertEquals("genre", "*", p["genre"])
    }

    @Test
    fun `live keeps filtering by genre and sends no category`() {
        val p = StalkerListParams.forPage("itv", "5", null, 2)
        assertEquals("genre", "5", p["genre"])
        assertFalse("no category for itv", "category" in p)
    }

    @Test
    fun `search rides along and nothing means all`() {
        assertEquals("search", "arrival", StalkerListParams.forPage("vod", null, "arrival", 1)["search"])
        assertEquals("all", "*", StalkerListParams.forPage("vod", null, null, 1)["category"])
        assertFalse("no search key", "search" in StalkerListParams.forPage("vod", null, null, 1))
    }
}
