package com.nuvio.tv.core.iptv.stalker

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B02: which series dialect a portal speaks, decided by what it answered — never by its URL. */
class StalkerSeriesDialectTest {

    private fun obj(json: String) = JsonParser.parseString(json) as JsonObject

    @Test
    fun `a portal that answers type=series is XC-family`() {
        assertEquals("series answered", StalkerSeriesDialect.Dialect.XC,
            StalkerSeriesDialect.decide(seriesCategoriesUsable = true, vodCategoriesUsable = true))
    }

    @Test
    fun `no series module but working vod is genuine Ministra`() {
        assertEquals("vod answered", StalkerSeriesDialect.Dialect.MINISTRA,
            StalkerSeriesDialect.decide(seriesCategoriesUsable = false, vodCategoriesUsable = true))
    }

    @Test
    fun `neither answering proves nothing`() {
        assertNull("undecided", StalkerSeriesDialect.decide(seriesCategoriesUsable = false, vodCategoriesUsable = false))
    }

    @Test
    fun `is_series is read leniently`() {
        assertTrue("string 1", StalkerSeriesDialect.isSeriesRow(obj("""{"is_series":"1"}""")))
        assertTrue("int 1", StalkerSeriesDialect.isSeriesRow(obj("""{"is_series":1}""")))
        assertTrue("bool", StalkerSeriesDialect.isSeriesRow(obj("""{"is_series":true}""")))
        assertFalse("string 0", StalkerSeriesDialect.isSeriesRow(obj("""{"is_series":"0"}""")))
        assertFalse("absent (XC rows)", StalkerSeriesDialect.isSeriesRow(obj("""{"id":"1"}""")))
    }

    @Test
    fun `tree params walk movie_id then season_id then episode_id under type=vod`() {
        assertEquals(mapOf("type" to "vod", "action" to "get_ordered_list", "movie_id" to "500", "p" to "1"),
            StalkerSeriesDialect.seasonsParams(500, 1))
        assertEquals(mapOf("type" to "vod", "action" to "get_ordered_list", "movie_id" to "500", "season_id" to "5002", "p" to "2"),
            StalkerSeriesDialect.episodesParams(500, 5002, 2))
        assertEquals(mapOf("type" to "vod", "action" to "get_ordered_list", "movie_id" to "500", "season_id" to "5002",
            "episode_id" to "500203", "p" to "1"),
            StalkerSeriesDialect.filesParams(500, 5002, 500203))
    }

    @Test
    fun `season and episode numbers come from their own fields, names as fallback`() {
        assertEquals(StalkerSeriesDialect.Node(5002, 2), StalkerSeriesDialect.season(obj("""{"id":"5002","season_number":"2","name":"Season 2"}""")))
        assertEquals(StalkerSeriesDialect.Node(77, 4), StalkerSeriesDialect.season(obj("""{"id":"77","name":"Season 4. Finale"}""")))
        assertEquals(StalkerSeriesDialect.Node(500203, 3), StalkerSeriesDialect.episode(obj("""{"id":"500203","series_number":"3"}""")))
        assertNull("no id", StalkerSeriesDialect.episode(obj("""{"series_number":"3"}""")))
    }
}
