package com.nuvio.tv.core.sports.detail

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EspnBlockAssemblerTest {
    private val asm = EspnBlockAssembler(SportsFixtures.mapping)

    private inline fun <reified T : GameBlock> block(type: BlockType, profile: String, k: String): T? =
        asm.block(type, profile, SportsFixtures.espnDocs(k)) as T?

    // ---- header / state ----
    @Test fun `nba header is teams with scores`() {
        val h = asm.header("basketball", SportsFixtures.espnDocs("nba")) as GameHeader.Teams
        assertEquals("home", "Atlanta Hawks", h.home.name)
        assertEquals("home short", "ATL", h.home.short)
        assertNotNull("away", h.away.name)
        assertTrue("home score", h.home.score!!.toInt() > 0)
        assertEquals("record", "6-4", h.home.record)
    }
    @Test fun `state and live info come from status`() {
        val d = SportsFixtures.espnDocs("nba")
        assertEquals("final", GameState.FINAL, asm.gameState(d))
        assertEquals("detail", "Final", asm.live(d)?.detail)
    }
    @Test fun `motorsport and fighting headers are event style`() {
        val f1 = asm.header("motorsport", SportsFixtures.espnDocs("f1", false))
        assertTrue("f1 event header", f1 is GameHeader.Event)
        assertTrue("f1 has a name", (f1 as GameHeader.Event).name.isNotBlank())
        assertTrue("ufc event header", asm.header("fighting", SportsFixtures.espnDocs("ufc", false)) is GameHeader.Event)
    }

    // ---- per sport blocks ----
    @Test fun `nba linescore has quarter columns and home first rows`() {
        val l = block<GameBlock.Linescore>(BlockType.LINESCORE, "basketball", "nba")!!
        assertEquals("columns", listOf("Q1", "Q2", "Q3", "Q4"), l.columns)
        assertEquals("home row first (ATL 28/38/39/34)", listOf("28", "38", "39", "34"), l.rows[0])
        assertEquals("two rows", 2, l.rows.size)
        assertEquals("source", SourceId.ESPN, l.source)
    }
    @Test fun `nba stats honour the allow list order and label`() {
        val s = block<GameBlock.Stats>(BlockType.STATS, "basketball", "nba")!!
        assertEquals("allow-list order", listOf("Field Goal %", "Three Point %", "Free Throw %", "Rebounds", "Assists", "Steals", "Blocks", "Turnovers"), s.rows.map { it.label })
        assertTrue("both sides filled", s.rows.all { it.home.isNotBlank() && it.away.isNotBlank() })
    }
    @Test fun `nba leaders carry side and athlete`() {
        val l = block<GameBlock.Leaders>(BlockType.LEADERS, "basketball", "nba")!!
        assertTrue("has items", l.items.size >= 4)
        assertTrue("both sides", l.items.map { it.side }.toSet().containsAll(setOf(SideKey.HOME, SideKey.AWAY)))
        assertTrue("athlete", l.items.all { it.athlete.isNotBlank() && it.value.isNotBlank() })
    }
    @Test fun `nba timeline is the last five scoring plays`() {
        val t = block<GameBlock.Timeline>(BlockType.TIMELINE, "basketball", "nba")!!
        assertEquals("last 5", 5, t.items.size)
        assertEquals("order kept, final play last", "Onyeka Okongwu makes free throw 2 of 2", t.items.last().text)
        assertEquals("clock", "4.5", t.items.last().time)
        assertEquals("period", "4th Quarter", t.items.last().period)
    }
    @Test fun `nba win probability is a home percent`() {
        val w = block<GameBlock.WinProbability>(BlockType.WIN_PROBABILITY, "basketball", "nba")!!
        assertEquals("1.0 -> 100", 100.0, w.homePercent, 0.001)
    }
    @Test fun `nfl linescore leaders timeline and win probability read real data`() {
        assertTrue("linescore", block<GameBlock.Linescore>(BlockType.LINESCORE, "football", "nfl")!!.rows.all { it.size >= 4 })
        assertTrue("leaders", block<GameBlock.Leaders>(BlockType.LEADERS, "football", "nfl")!!.items.any { it.athlete == "Trevor Lawrence" })
        val t = block<GameBlock.Timeline>(BlockType.TIMELINE, "football", "nfl")!!
        assertEquals("scoring play text", "Bhayshul Tuten 1 Yd Rush (Cam Little Kick)", t.items.first().text)
        assertEquals("11:20", t.items.first().time)
        assertNotNull("wp", block<GameBlock.WinProbability>(BlockType.WIN_PROBABILITY, "football", "nfl"))
        val s = block<GameBlock.Stats>(BlockType.STATS, "football", "nfl")!!
        assertTrue("total yards present", s.rows.any { it.label == "Total Yards" })
    }
    @Test fun `mlb linescore appends runs hits errors`() {
        val l = block<GameBlock.Linescore>(BlockType.LINESCORE, "baseball", "mlb")!!
        assertEquals("tail columns", listOf("R", "H", "E"), l.columns.takeLast(3))
        assertEquals("innings 1..9 first", "1", l.columns.first())
        assertEquals("row width matches columns", l.columns.size, l.rows[0].size)
    }
    @Test fun `mlb has no stats block because the shape is not mapped`() =
        assertNull("absent not empty", block<GameBlock.Stats>(BlockType.STATS, "baseball", "mlb"))
    @Test fun `nhl stats and period columns`() {
        val s = block<GameBlock.Stats>(BlockType.STATS, "hockey", "nhl")!!
        assertTrue("shots", s.rows.any { it.label == "Shots" })
        val l = block<GameBlock.Linescore>(BlockType.LINESCORE, "hockey", "nhl")!!
        assertTrue("P1 first", l.columns.first() == "P1")
    }
    @Test fun `epl stats timeline and form`() {
        val s = block<GameBlock.Stats>(BlockType.STATS, "soccer", "epl")!!
        assertEquals("allow order starts with possession", "Possession", s.rows.first().label)
        val t = block<GameBlock.Timeline>(BlockType.TIMELINE, "soccer", "epl")!!
        assertTrue("goal kind", t.items.any { it.kind == TimelineKind.GOAL && it.time == "79'" })
        val f = block<GameBlock.Form>(BlockType.FORM, "soccer", "epl")!!
        assertEquals("home form chars", listOf("W", "L", "D", "D", "L"), f.home)
        assertEquals("away form 5", 5, f.away.size)
    }
    @Test fun `epl broadcast lists channel names`() {
        val b = block<GameBlock.Broadcast>(BlockType.BROADCAST, "soccer", "epl")!!
        assertTrue("USA Net", b.channels.any { it.name == "USA Net" })
    }
    @Test fun `f1 and ufc results rows`() {
        val f1 = asm.block(BlockType.RESULTS, "motorsport", SportsFixtures.espnDocs("f1", false)) as GameBlock.Results
        assertTrue("sessions listed", f1.rows.isNotEmpty() && f1.rows.first().any { it == "FP1" })
        val ufc = asm.block(BlockType.RESULTS, "fighting", SportsFixtures.espnDocs("ufc", false)) as GameBlock.Results
        assertTrue("bouts listed", ufc.rows.isNotEmpty())
    }
    @Test fun `all assembles every available block for a profile`() {
        val all = asm.all("basketball", SportsFixtures.espnDocs("nba"))
        assertTrue("set", all.keys.containsAll(setOf(BlockType.LINESCORE, BlockType.STATS, BlockType.LEADERS, BlockType.TIMELINE, BlockType.WIN_PROBABILITY)))
    }

    // ---- situation (synthetic: no live fixture exists yet; shape from ESPN docs, UNVERIFIED) ----
    @Test fun `football situation is the down and distance text when live`() {
        val ev = Json.parseToJsonElement("""{"status":{"type":{"state":"in"}},"competitions":[{"situation":{"downDistanceText":"2nd & 7 at OPP 34"}}]}""")
        val s = asm.block(BlockType.SITUATION, "football", EspnDocs(ev, null)) as GameBlock.Situation
        assertEquals("text", "2nd & 7 at OPP 34", s.text)
    }
    @Test fun `baseball situation builds runners and count`() {
        val ev = Json.parseToJsonElement("""{"status":{"type":{"state":"in"}},"competitions":[{"situation":{"onFirst":true,"onSecond":false,"onThird":true,"balls":2,"strikes":1,"outs":1}}]}""")
        val s = asm.block(BlockType.SITUATION, "baseball", EspnDocs(ev, null)) as GameBlock.Situation
        assertEquals("text", "Runners on 1st, 3rd · 2-1 · 1 out", s.text)
    }
    @Test fun `situation is hidden once the game is not live`() =
        assertNull("final game", asm.block(BlockType.SITUATION, "football", SportsFixtures.espnDocs("nfl")))

    // ---- the mapping is data: a renamed path repairs a broken block ----
    @Test fun `a renamed espn field blanks the block and a mapping edit repairs it`() {
        val renamed = SportsFixtures.espnEvent("nba").toString().replace("\"linescores\"", "\"periodScores\"")
        val docs = EspnDocs(Json.parseToJsonElement(renamed), null)
        assertNull("broken by rename", asm.block(BlockType.LINESCORE, "basketball", docs))
        assertNotNull("other blocks still fine", asm.header("basketball", docs))

        val fixedText = SportsFixtures::class.java.classLoader!!.getResourceAsStream("sports/sports-mapping.v1.json")!!
            .bufferedReader().readText().replace("""competitors[*].linescores[*].displayValue""", """competitors[*].periodScores[*].displayValue""")
        val repaired = (SportsMappingParser.parse(fixedText) as MappingParseResult.Parsed).mapping
        val l = EspnBlockAssembler(repaired).block(BlockType.LINESCORE, "basketball", docs) as GameBlock.Linescore
        assertEquals("repaired by data only", listOf("28", "38", "39", "34"), l.rows[0])
    }

    @Test fun `assemblers never throw on junk documents`() {
        val junk = EspnDocs(Json.parseToJsonElement("""{"competitions":"nope","status":[1,2]}"""), Json.parseToJsonElement("[]"))
        for (p in SportsFixtures.mapping.profiles.keys) {
            assertTrue("$p all", asm.all(p, junk).isEmpty())
            assertNull("$p header", asm.header(p, junk))
        }
    }

    @Test fun `live period reads as an ordinal and the clock stays raw`() {
        val ev = Json.parseToJsonElement("""{"status":{"displayClock":"0:00","period":2,"type":{"state":"in","shortDetail":"End of 2nd"}},"competitions":[{}]}""")
        val l = asm.live(EspnDocs(ev, null), "hockey")!!
        assertEquals("2nd", l.periodLabel)
        assertEquals("0:00", l.clock)
        val q = asm.live(EspnDocs(Json.parseToJsonElement("""{"status":{"displayClock":"5:12","period":3,"type":{"state":"in"}}}"""), null), "basketball")!!
        assertEquals("3rd", q.periodLabel)
        val soccer = asm.live(EspnDocs(Json.parseToJsonElement("""{"status":{"displayClock":"78'","period":2,"type":{"state":"in"}}}"""), null), "soccer")!!
        assertNull("no period for soccer", soccer.periodLabel)
    }
}
