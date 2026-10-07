package com.nuvio.tv.core.iptv

import com.google.gson.JsonParser
import org.junit.Test
import org.junit.Assert.assertEquals

class XtreamEpisodeRowsTest {
    private fun parse(raw: String) = XtreamEpisodeRows.parse(JsonParser.parseString(raw))

    @Test fun seasonArraysRetainSpecialsAndMixedPrimitiveTypes() {
        val rows = parse("""{"0":[{"id":"11","episode_num":"2","info":[]}],"2":[{"id":12,"episode_num":1,"info":{"plot":"P"}}]}""")
        assertEquals(listOf(0, 2), rows.map { it.season })
        assertEquals(listOf(2, 1), rows.map { it.number })
        assertEquals("P", rows[1].plot)
    }
    @Test fun keyedEpisodesAndAliasesRemainPlayable() {
        val rows = parse("""{"3":{"7":{"episode_id":"91","name":"Seven","container_extension":"mkv"}}}""")
        assertEquals(XtreamEpisodeRows.Row("91",3,7,"Seven",null,null,"mkv"), rows.single())
    }
    @Test fun flatArrayUsesExplicitSeasonAndEpisode() {
        val rows = parse("""[{"stream_id":42,"season_number":"4","episode_number":"6"},{"title":"junk"}]""")
        assertEquals(listOf("42"), rows.map { it.id })
        assertEquals(4, rows.single().season)
        assertEquals(6, rows.single().number)
    }
}
