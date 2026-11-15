package com.nuvio.tv.core.sports.detail

import com.nuvio.tv.core.sports.detail.screen.FieldGeometry
import com.nuvio.tv.core.sports.detail.screen.GameMomentPolicy
import com.nuvio.tv.core.sports.detail.screen.LeftColumnFit
import com.nuvio.tv.core.sports.detail.screen.LineupPitchPolicy
import com.nuvio.tv.core.sports.detail.screen.Moment
import com.nuvio.tv.core.sports.detail.screen.detailOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase B (spec 03): football field strip, soccer strip, pitch lineups, moments, left-column fit. Fixtures: live CFB + EPL captures 11-14/15. */
class FootballFieldAssemblerTest {
    private val asm = EspnBlockAssembler(SportsFixtures.mapping)
    private fun field(k: String) = asm.block(BlockType.FIELD, "football", EspnDocs(SportsFixtures.json("espn/gs_${k}_event.json"), null)) as? GameBlock.Field

    @Test fun `live goal-to-go in the red zone reads yard line possession and the feed text`() {
        val f = field("cfb_a")!!
        assertEquals("yard line from the home goal line", 98, f.yardLine)
        assertEquals("possession id 197 is the home side", SideKey.HOME, f.possession)
        assertEquals("down", 2, f.down); assertEquals("distance", 2, f.distance)
        assertEquals("feed text, never built by us", "2nd & Goal at TTU 2", f.line)
        assertTrue("red zone", f.redZone)
        assertEquals("timeouts", 3 to 1, f.homeTimeouts to f.awayTimeouts)
        assertEquals("drive start", 25, f.drive?.startYardLine)
        assertEquals("drive summary", "10 plays, 73 yards, 2:35", f.drive?.summary)
    }

    @Test fun `away possession and outside the red zone`() {
        val f = field("cfb_utah")!!
        assertEquals("ARIZ (home, id 12) has the ball", SideKey.HOME, f.possession)
        assertEquals(32, f.yardLine); assertFalse("not red zone", f.redZone)
        assertEquals("3rd & 3 at ARIZ 32", f.line)
    }

    @Test fun `no live situation means no field block`() {
        val ev = SportsFixtures.raw("espn/gs_nfl_event.json").let { kotlinx.serialization.json.Json.parseToJsonElement(it) }
        assertNull("a finished game has no situation", asm.block(BlockType.FIELD, "football", EspnDocs(ev, null)))
    }

    @Test fun `a yard line outside 0 to 100 drops the block and zero down means between plays`() {
        val bad = SportsFixtures.raw("espn/gs_cfb_a_event.json").replace("\"yardLine\":98,", "\"yardLine\":140,")
        assertNull("out of range", asm.block(BlockType.FIELD, "football", EspnDocs(kotlinx.serialization.json.Json.parseToJsonElement(bad), null)))
        val between = SportsFixtures.raw("espn/gs_cfb_a_event.json").replace("\"down\":2,", "\"down\":0,")
        assertNull("down 0 reads null", (asm.block(BlockType.FIELD, "football", EspnDocs(kotlinx.serialization.json.Json.parseToJsonElement(between), null)) as GameBlock.Field).down)
    }

    @Test fun `field is visible only while live and is listed for football`() {
        assertTrue("live", BlockVisibility.visible(BlockType.FIELD, GameState.LIVE))
        assertFalse("final", BlockVisibility.visible(BlockType.FIELD, GameState.FINAL))
        assertTrue(BlockType.FIELD in SportsFixtures.mapping.profile("football")!!.blockTypes)
        assertFalse("not for hockey", BlockType.FIELD in SportsFixtures.mapping.profile("hockey")!!.blockTypes)
    }

    @Test fun `new clients hide the situation text card when the field strip is present`() {
        val d = detailOf(GameBlock.Situation(SourceId.ESPN, "2nd & Goal"), GameBlock.Field(SourceId.ESPN, 98, SideKey.HOME, 2, 2, "2nd & Goal at TTU 2"))
        val m = com.nuvio.tv.core.sports.detail.screen.GameDetailTvModel.from(d, 0L)
        assertNull("situation hidden", m.situation); assertNotNull("field shown", m.field)
        val only = com.nuvio.tv.core.sports.detail.screen.GameDetailTvModel.from(detailOf(GameBlock.Situation(SourceId.ESPN, "2nd & Goal")), 0L)
        assertEquals("situation kept without a field", "2nd & Goal", only.situation)
    }
}

class SoccerStripAssemblerTest {
    private val asm = EspnBlockAssembler(SportsFixtures.mapping)
    private val ev = SportsFixtures.json("espn/gs_epl_event.json")
    private fun strip(event: kotlinx.serialization.json.JsonElement = ev, summary: kotlinx.serialization.json.JsonElement? = null) =
        asm.block(BlockType.SOCCER_STRIP, "soccer", EspnDocs(event, summary)) as GameBlock.SoccerStrip

    @Test fun `scorers cards possession and corners read from the single-event scoreboard`() {
        val s = strip()
        assertEquals("home scorers in time order with the penalty tag", 2, s.homeScorers.size)
        assertTrue("first scorer carries the minute", s.homeScorers[0].endsWith("15'"))
        assertTrue("penalty tagged", s.homeScorers[1].endsWith("42' (pen)"))
        assertTrue("away has none", s.awayScorers.isEmpty())
        assertEquals("yellow home/away", 4 to 2, s.homeYellow to s.awayYellow)
        assertEquals("red home/away", 0 to 1, s.homeRed to s.awayRed)
        assertEquals("possession rounds 75.3", 75, s.homePossession)
        assertEquals("home corners", 7, s.homeCorners)
    }

    @Test fun `a finished match keeps scorers and cards but no last event`() = assertNull("final", strip().lastEvent)

    @Test fun `an own goal is listed under the team that got the goal`() {
        val og = SportsFixtures.raw("espn/gs_epl_event.json")
            .replaceFirst("\"ownGoal\":false", "\"ownGoal\":true").replaceFirst("\"penaltyKick\":false", "\"penaltyKick\":false")
        val s = strip(kotlinx.serialization.json.Json.parseToJsonElement(og))
        // the first detail in the capture is a card, so flip the first GOAL detail explicitly
        val goal = SportsFixtures.raw("espn/gs_epl_event.json").let { raw ->
            val i = raw.indexOf("\"text\":\"Goal\"")
            raw.substring(0, i) + raw.substring(i).replaceFirst("\"ownGoal\":false", "\"ownGoal\":true")
        }
        val s2 = strip(kotlinx.serialization.json.Json.parseToJsonElement(goal))
        assertTrue("(og) tag on the team that got the goal", s2.homeScorers.any { it.endsWith("(og)") })
        assertNotNull(s)
    }

    @Test fun `live match exposes the newest real event`() {
        val live = SportsFixtures.raw("espn/gs_epl_event.json").replace("\"state\":\"post\"", "\"state\":\"in\"")
        val s = strip(kotlinx.serialization.json.Json.parseToJsonElement(live))
        val last = s.lastEvent!!
        assertEquals("newest board event is the 69' yellow", "yellow", last.kind)
        assertEquals("away side", SideKey.AWAY, last.side)
        assertEquals("minute", "69'", last.minute)
    }

    @Test fun `a fresher summary commentary event wins and chatter never does`() {
        val live = SportsFixtures.raw("espn/gs_epl_event.json").replace("\"state\":\"post\"", "\"state\":\"in\"")
        val comm = """{"commentary":[
          {"text":"Corner, Brentford.","time":{"value":4500.0,"displayValue":"75'"},"play":{"type":{"text":"Corner Awarded"},"team":{"displayName":"Brentford"}}},
          {"text":"Foul by someone.","time":{"value":4560.0,"displayValue":"76'"},"play":{"type":{"text":"Foul"},"team":{"displayName":"Brentford"}}}]}"""
        val s = strip(kotlinx.serialization.json.Json.parseToJsonElement(live), kotlinx.serialization.json.Json.parseToJsonElement(comm))
        assertEquals("corner at 75' beats the 69' card; the foul is chatter", "corner", s.lastEvent!!.kind)
        assertEquals("away side by team name", SideKey.AWAY, s.lastEvent!!.side)
    }

    @Test fun `soccer strip is visible live and final and listed for soccer only`() {
        assertTrue(BlockVisibility.visible(BlockType.SOCCER_STRIP, GameState.LIVE)); assertTrue(BlockVisibility.visible(BlockType.SOCCER_STRIP, GameState.FINAL))
        assertTrue(BlockType.SOCCER_STRIP in SportsFixtures.mapping.profile("soccer")!!.blockTypes)
        assertFalse(BlockType.SOCCER_STRIP in SportsFixtures.mapping.profile("football")!!.blockTypes)
    }
}

class FieldGeometryTest {
    @Test fun `x is yards from the home goal line`() {
        assertEquals("NYG 25 at home", 0.25f, FieldGeometry.x(25), 1e-6f)
        assertEquals("WSH 42 is 58 from home", 0.58f, FieldGeometry.x(58), 1e-6f)
        assertEquals("clamped", 1f, FieldGeometry.x(130), 1e-6f)
    }
    @Test fun `home attacks toward the away goal and away toward home`() {
        assertEquals(1f, FieldGeometry.attackingToward("home"), 0f); assertEquals(0f, FieldGeometry.attackingToward("away"), 0f)
    }
    @Test fun `first down line`() {
        assertEquals("away ball at 58, 2nd and 5 -> 53", 0.53f, FieldGeometry.firstDownX(58, 5, "away")!!, 1e-6f)
        assertEquals("home ball at 25, 1st and 10 -> 35", 0.35f, FieldGeometry.firstDownX(25, 10, "home")!!, 1e-6f)
        assertNull("goal to go (home, 98 + 2)", FieldGeometry.firstDownX(98, 2, "home"))
        assertNull("unknown distance", FieldGeometry.firstDownX(50, null, "home"))
        assertNull("unknown possession", FieldGeometry.firstDownX(50, 5, null))
    }
    @Test fun `drive span is ordered`() {
        assertEquals(0.25f to 0.98f, FieldGeometry.driveSpan(25, 98))
        assertEquals("away drive runs the other way", 0.4f to 0.9f, FieldGeometry.driveSpan(90, 40))
        assertNull("no start", FieldGeometry.driveSpan(null, 40))
    }
}

class BMomentsTest {
    private fun fd(yard: Int, poss: SideKey? = SideKey.HOME, down: Int? = 1) = GameBlock.Field(SourceId.ESPN, yard, poss, down, 10, null)
    private fun d(vararg b: GameBlock, h: String = "7", a: String = "3") = detailOf(*b, home = Side("H", score = h), away = Side("A", score = a))

    @Test fun `ball moves with the same team is one FieldMove from the old spot to the new`() {
        val m = GameMomentPolicy.diff(d(fd(25)), d(fd(60))).filterIsInstance<Moment.FieldMove>().single()
        assertEquals(25, m.fromYard); assertEquals(60, m.toYard); assertFalse("a gain", m.loss)
    }
    @Test fun `a loss is the ball moving away from the attacked goal`() {
        assertTrue("home loses yards", GameMomentPolicy.diff(d(fd(60)), d(fd(55))).filterIsInstance<Moment.FieldMove>().single().loss)
        assertTrue("away loses yards when the yard line rises", GameMomentPolicy.diff(d(fd(40, SideKey.AWAY)), d(fd(45, SideKey.AWAY))).filterIsInstance<Moment.FieldMove>().single().loss)
    }
    @Test fun `down returning to 1 with the same possession is a first down`() {
        assertTrue(GameMomentPolicy.diff(d(fd(40, down = 3)), d(fd(55, down = 1))).filterIsInstance<Moment.FieldMove>().single().firstDown)
        assertFalse(GameMomentPolicy.diff(d(fd(40, down = 1)), d(fd(50, down = 2))).filterIsInstance<Moment.FieldMove>().single().firstDown)
    }
    @Test fun `possession change flips the arrow and is not a field move`() {
        val m = GameMomentPolicy.diff(d(fd(40)), d(fd(60, SideKey.AWAY)))
        assertEquals(listOf<Moment>(Moment.PossessionChange(SideKey.AWAY)), m.filterIsInstance<Moment.PossessionChange>())
        assertTrue(m.none { it is Moment.FieldMove })
    }
    @Test fun `a score rise of six for the side that had the ball is a touchdown`() {
        val m = GameMomentPolicy.diff(d(fd(95), h = "7"), d(fd(25, SideKey.AWAY), h = "14"))
        assertEquals(listOf<Moment>(Moment.Touchdown(SideKey.HOME)), m.filterIsInstance<Moment.Touchdown>())
        assertTrue("a field goal is not", GameMomentPolicy.diff(d(fd(80), h = "7"), d(fd(80), h = "10")).none { it is Moment.Touchdown })
    }
    @Test fun `a changed last soccer event is one SoccerMoment and an unchanged one nothing`() {
        fun s(text: String, minute: String) = GameBlock.SoccerStrip(SourceId.ESPN, lastEvent = SoccerEvent("goal", SideKey.HOME, minute, text))
        val m = GameMomentPolicy.diff(d(s("A", "10'")), d(s("B", "23'")))
        assertEquals(1, m.filterIsInstance<Moment.SoccerMoment>().size)
        assertTrue(GameMomentPolicy.diff(d(s("A", "10'")), d(s("A", "10'"))).none { it is Moment.SoccerMoment })
    }
}

class LineupPitchPolicyTest {
    private fun xi(n: Int = 11, places: List<Int?> = (1..n).toList()) =
        (1..n).map { LineupPlayer("$it", "P$it", "M", true, places.getOrNull(it - 1)) }

    @Test fun `4-3-3 places the keeper then rows of 4 3 3`() {
        val s = LineupPitchPolicy.positions("4-3-3", xi())
        assertEquals(11, s.size)
        assertEquals("keeper first", 1, s[0].player.place)
        val xs = s.drop(1).map { it.x }
        assertEquals("rows 4 3 3", listOf(4, 3, 3), xs.distinct().map { x -> xs.count { it == x } })
        assertEquals("rows ordered toward the opponent", xs.distinct().sorted(), xs.distinct())
        assertTrue("evenly spread across the pitch", s.drop(1).take(4).map { it.y } == listOf(0.2f, 0.4f, 0.6f, 0.8f))
    }
    @Test fun `unknown formation missing places or not 11 starters give an empty list`() {
        assertTrue(LineupPitchPolicy.positions(null, xi()).isEmpty())
        assertTrue(LineupPitchPolicy.positions("4-3-4", xi()).isEmpty())
        assertTrue(LineupPitchPolicy.positions("4-3-3", xi(places = List(11) { null })).isEmpty())
        assertTrue(LineupPitchPolicy.positions("4-3-3", xi(10)).isEmpty())
        assertTrue(LineupPitchPolicy.positions("4-3-3", xi(places = listOf(1, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11))).isEmpty())
    }
}

class LeftColumnFitTest {
    private val full = LeftColumnFit.Parts(strip = LeftColumnFit.Strip.FOOTBALL, lastPlay = true, winChance = true, chart = true, source = true)

    @Test fun `everything fits when there is room`() {
        val p = LeftColumnFit.plan(1000, full)
        assertTrue(p.showChart); assertTrue(p.showLastPlay)
    }
    @Test fun `the chart is the first thing to drop then the last play line`() {
        val withChart = LeftColumnFit.heightOf(full, showChart = true, showLastPlay = true)
        val noChart = LeftColumnFit.heightOf(full, showChart = false, showLastPlay = true)
        val bare = LeftColumnFit.heightOf(full, showChart = false, showLastPlay = false)
        val a = LeftColumnFit.plan(withChart - 1, full)
        assertFalse("chart dropped first", a.showChart); assertTrue("last play kept", a.showLastPlay)
        val b = LeftColumnFit.plan(noChart - 1, full)
        assertFalse(b.showChart); assertFalse("then the last play line", b.showLastPlay)
        assertTrue("the bare column is the floor, it never drops scoreboard strip or source", bare > 0)
        val c = LeftColumnFit.plan(bare - 100, full)
        assertFalse(c.showChart); assertFalse(c.showLastPlay)
    }
    @Test fun `a column without a chart or last play is unaffected`() {
        val p = LeftColumnFit.plan(0, full.copy(chart = false, lastPlay = false))
        assertFalse(p.showChart); assertFalse(p.showLastPlay)
    }
    @Test fun `the football strip plus everything fits 472 dp only without the chart`() {
        val p = LeftColumnFit.plan(472, full)
        assertFalse("chart cannot fit beside the strip", p.showChart)
    }
}
