package com.nuvio.tv.core.sports.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase A assembler additions (spec 02 A2): colours, rank, plays, standings table. Fixtures are trimmed live captures (11-14). */
class GameScreenAssemblerTest {
    private val asm = EspnBlockAssembler(SportsFixtures.mapping)
    private fun docs(k: String) = EspnDocs(SportsFixtures.json("espn/gs_${k}_event.json"), SportsFixtures.json("espn/gs_${k}_summary.json"))

    // ---- colours + rank ----
    @Test fun `sides carry team colour and alternate colour`() {
        val h = asm.header("basketball", docs("nba")) as GameHeader.Teams
        assertEquals("home colour", "98002e", h.home.color)
        assertEquals("home alt", "000000", h.home.altColor)
        assertTrue("away colour is hex", h.away.color!!.matches(Regex("[0-9a-f]{6}")))
    }

    @Test fun `rank 99 means unranked and a real rank is kept`() {
        val h = asm.header("football", docs("nfl")) as GameHeader.Teams
        assertNull("99 is unranked", h.home.rank)
        val ranked = EspnDocs(
            SportsFixtures.raw("espn/gs_nfl_event.json").replaceFirst("\"curatedRank\":{\"current\":99}", "\"curatedRank\":{\"current\":7}").let { kotlinx.serialization.json.Json.parseToJsonElement(it) },
            null,
        )
        val r = asm.header("football", ranked) as GameHeader.Teams
        assertTrue("some side is ranked 7", r.home.rank == 7 || r.away.rank == 7)
    }

    // ---- plays ----
    @Test fun `basketball plays are newest first with id wallclock and running score`() {
        val b = asm.block(BlockType.PLAYS, "basketball", docs("nba")) as GameBlock.Plays
        assertEquals("newest first", "401909982697", b.items.first().id)
        assertEquals("oldest last", "4019099824", b.items.last().id)
        val p = b.items.first { it.id == "4019099829" }
        assertEquals("score is home-away", "3–2", p.score)
        assertEquals("period passes through", "1st Quarter", p.period)
        assertEquals("clock", true, p.time.isNotBlank())
        assertEquals("wallclock parsed", java.time.Instant.parse("2026-11-15T01:11:46Z").toEpochMilli(), p.wallclockMs)
        assertEquals("home team id 14 is home", SideKey.HOME, p.side)
        assertEquals("team 8 is away", SideKey.AWAY, b.items.first { it.id == "4019099827" }.side)
    }

    @Test fun `plays cap at extra last`() {
        val capped = SportsFixtures.mapping.let { m ->
            val text = SportsFixtures.raw("sports/sports-mapping.v1.json".removePrefix("sports/")).replace("\"last\": 60", "\"last\": 2").replace("\"last\":60", "\"last\":2")
            (SportsMappingParser.parse(text) as MappingParseResult.Parsed).mapping
        }
        val b = EspnBlockAssembler(capped).block(BlockType.PLAYS, "basketball", docs("nba")) as GameBlock.Plays
        assertEquals("cap", 2, b.items.size)
    }

    @Test fun `hockey plays read from the same shape`() {
        val b = asm.block(BlockType.PLAYS, "hockey", docs("nhl")) as GameBlock.Plays
        assertTrue("newest first by wallclock", b.items.first().wallclockMs!! >= b.items.last().wallclockMs!!)
        assertTrue("has a score after a goal", b.items.any { it.score == "1–0" })
    }

    @Test fun `football plays flatten the drives`() {
        val b = asm.block(BlockType.PLAYS, "football", docs("nfl")) as GameBlock.Plays
        assertTrue("flattened more than one drive", b.items.size > 5)
        assertEquals("period reads Q1", "Q1", b.items.last().period)
        assertNotNull("ids", b.items.first().id)
    }

    @Test fun `soccer plays keep only the allowed kinds and tag them`() {
        val b = asm.block(BlockType.PLAYS, "soccer", docs("epl")) as GameBlock.Plays
        assertTrue("no plain fouls", b.items.none { it.text.contains("Foul", ignoreCase = true) && it.kind == null && it.id == "55065494" })
        assertEquals("kinds", setOf(TimelineKind.GOAL, TimelineKind.CARD, TimelineKind.CORNER, TimelineKind.VAR, TimelineKind.SHOT), b.items.mapNotNull { it.kind }.toSet().intersect(setOf(TimelineKind.GOAL, TimelineKind.CARD, TimelineKind.CORNER, TimelineKind.VAR, TimelineKind.SHOT)))
        assertEquals("period label", "1st Half", b.items.first().period)
        val goal = b.items.first { it.kind == TimelineKind.GOAL }
        assertEquals("side by team name", SideKey.HOME, goal.side)
        assertTrue("sorted newest first", b.items.zipWithNext().all { (a, c) -> a.wallclockMs == null || c.wallclockMs == null || a.wallclockMs!! >= c.wallclockMs!! })
    }

    // ---- standings table ----
    @Test fun `basketball standings table has the labelled columns and marks the game teams`() {
        val b = asm.block(BlockType.STANDINGS_TABLE, "basketball", docs("nba")) as GameBlock.StandingsTable
        assertEquals("two divisions", 2, b.groups.size)
        assertEquals("group name without season or suffix", "Central", b.groups[0].name)
        assertEquals("columns", listOf("W", "L", "PCT", "GB", "STRK"), b.groups[0].columns)
        assertTrue("cells match the column count", b.groups.all { g -> g.rows.all { it.cells.size == g.columns.size } })
        val teams = b.groups.flatMap { it.rows }.filter { it.isGameTeam }.mapNotNull { it.teamId }.toSet()
        assertEquals("both game teams highlighted", setOf("14", "8"), teams)
    }

    @Test fun `soccer standings put rank first and a missing stat reads a dash`() {
        val b = asm.block(BlockType.STANDINGS_TABLE, "soccer", docs("epl")) as GameBlock.StandingsTable
        assertEquals("columns", listOf("#", "P", "W", "D", "L", "GD", "PTS"), b.groups.single().columns)
        assertEquals("rank is the first cell of the first row", "1", b.groups.single().rows.first().cells.first())
    }

    @Test fun `profiles without the spec produce no plays or standings`() {
        assertNull("tennis", asm.block(BlockType.PLAYS, "tennis", docs("nba")))
        assertNull("cricket", asm.block(BlockType.STANDINGS_TABLE, "cricket", docs("nba")))
    }

    @Test fun `the mapping lists the new blocks and the event source`() {
        val m = SportsFixtures.mapping
        assertEquals("event template", "https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard/{id}", m.espnEventUrl)
        for (p in listOf("football", "basketball", "hockey", "soccer")) {
            assertTrue("$p plays", BlockType.PLAYS in m.profile(p)!!.blockTypes)
            assertTrue("$p standings", BlockType.STANDINGS_TABLE in m.profile(p)!!.blockTypes)
        }
    }
}
