package com.nuvio.tv.core.sports.detail

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TsdbBlockAssemblerTest {
    private val asm = TsdbBlockAssembler()
    private val docs = SportsFixtures.tsdbDocsEpl()

    @Test fun `header is teams with badges and score`() {
        val h = asm.header(docs) as GameHeader.Teams
        assertEquals("home", "Leeds United", h.home.name)
        assertEquals("away", "Tottenham Hotspur", h.away.name)
        assertEquals("score", "0", h.home.score)
        assertTrue("badge", h.home.badgeUrl!!.startsWith("https://"))
    }
    @Test fun `status codes map to state and live clock`() {
        assertEquals("2H is live", GameState.LIVE, asm.state(docs))
        assertEquals("clock", "2H", asm.live(docs)?.clock)
    }
    @Test fun `status vocabulary`() {
        fun st(s: String, post: String = "no") = asm.state(TsdbDocs(event = Json.parseToJsonElement("""{"strStatus":"$s","strPostponed":"$post"}""").let { it as kotlinx.serialization.json.JsonObject }))
        assertEquals("NS", GameState.UPCOMING, st("NS"))
        assertEquals("FT", GameState.FINAL, st("FT"))
        assertEquals("Match Finished", GameState.FINAL, st("Match Finished"))
        assertEquals("HT", GameState.LIVE, st("HT"))
        assertEquals("1H", GameState.LIVE, st("1H"))
        assertEquals("postponed", GameState.POSTPONED, st("NS", "yes"))
    }
    @Test fun `stats pair home and away values`() {
        val s = asm.block(BlockType.STATS, docs) as GameBlock.Stats
        assertTrue("passes", s.rows.any { it.label.contains("Passes", true) && it.home == "131" && it.away == "201" })
        assertEquals("source", SourceId.TSDB, s.source)
    }
    @Test fun `timeline cards become card items with side`() {
        val t = asm.block(BlockType.TIMELINE, docs) as GameBlock.Timeline
        val card = t.items.first { it.kind == TimelineKind.CARD }
        assertEquals("away side (strHome=No)", SideKey.AWAY, card.side)
        assertEquals("time", "15'", card.time)
        assertTrue("text names player", card.text.contains("Micky van de Ven"))
    }
    @Test fun `tv listing groups channels by country`() {
        val b = asm.block(BlockType.BROADCAST, docs) as GameBlock.Broadcast
        assertTrue("iceland", b.channels.any { it.region == "Iceland" && it.name == "Stöð 2 Sport IS" })
    }
    @Test fun `highlights carry a url`() {
        val h = asm.block(BlockType.HIGHLIGHTS, docs)
        assertTrue("highlights or absent but never throws", h == null || (h as GameBlock.Highlights).url.isNotBlank())
    }
    @Test fun `lineup groups starters by side (synthetic shape, unverified)`() {
        val lu = Json.parseToJsonElement("""{"lineup":[
          {"strPlayer":"A","strPosition":"Goalkeeper","intSquadNumber":"1","strHome":"Yes","strSubstitute":"No","strFormation":"4-3-3"},
          {"strPlayer":"B","strPosition":"Forward","intSquadNumber":"9","strHome":"No","strSubstitute":"Yes","strFormation":"4-4-2"}]}""")
        val b = asm.block(BlockType.LINEUP, TsdbDocs(lineup = lu)) as GameBlock.Lineup
        assertEquals("home formation", "4-3-3", b.sides[0].formation)
        assertEquals("starter", true, b.sides[0].players[0].starter)
        assertEquals("sub", false, b.sides[1].players[0].starter)
    }
    @Test fun `missing documents give no blocks and no throw`() {
        val empty = TsdbDocs()
        assertNull("header", asm.header(empty))
        assertTrue("all", asm.all(empty).isEmpty())
        assertTrue("html body", asm.all(TsdbDocs(event = null, stats = Json.parseToJsonElement("{\"lookup\":null}"))).isEmpty())
    }
}
