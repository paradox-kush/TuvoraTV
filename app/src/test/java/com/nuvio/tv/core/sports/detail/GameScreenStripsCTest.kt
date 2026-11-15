package com.nuvio.tv.core.sports.detail

import com.nuvio.tv.core.sports.detail.screen.BasketballRunPolicy
import com.nuvio.tv.core.sports.detail.screen.BasketballStripPolicy
import com.nuvio.tv.core.sports.detail.screen.GameMomentPolicy
import com.nuvio.tv.core.sports.detail.screen.HockeyPlay
import com.nuvio.tv.core.sports.detail.screen.HockeyStrength
import com.nuvio.tv.core.sports.detail.screen.Moment
import com.nuvio.tv.core.sports.detail.screen.PowerPlayClock
import com.nuvio.tv.core.sports.detail.screen.RunPlay
import com.nuvio.tv.core.sports.detail.screen.detailOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase C (spec 04): basketball strip (direct), hockey strip (power-play side direct, clock derived behind a switch). */
class BasketballStripTest {
    private val base = SportsFixtures.mapping
    private fun json(s: String) = Json.parseToJsonElement(s)
    private fun withSwitches(body: String): SportsMapping {
        val text = SportsFixtures.raw("sports-mapping.v1.json").replace(Regex("\"uiSwitches\": \\{[^}]*\\}"), "\"uiSwitches\": {$body}")
        return (SportsMappingParser.parse(text) as MappingParseResult.Parsed).mapping
    }
    private fun strip(summary: String, m: SportsMapping = base) =
        EspnBlockAssembler(m).block(BlockType.BASKETBALL_STRIP, "basketball", EspnDocs(SportsFixtures.json("espn/gs_nba_event.json"), json(summary))) as? GameBlock.BasketballStrip

    @Test fun `both derived switches ship off in the bundled mapping and only a literal true turns one on`() {
        assertFalse(base.switchOn("hockeyPowerPlayClock")); assertFalse(base.switchOn("basketballRun")); assertTrue("chartMarkers is on", base.switchOn("chartMarkers"))
        val m = withSwitches("\"hockeyPowerPlayClock\": true, \"basketballRun\": \"true\", \"chartMarkers\": 1")
        assertTrue(m.switchOn("hockeyPowerPlayClock")); assertFalse("string", m.switchOn("basketballRun")); assertFalse("number", m.switchOn("chartMarkers"))
        val without = SportsFixtures.raw("sports-mapping.v1.json").replace(Regex("\"uiSwitches\": \\{[^}]*\\},"), "")
        assertEquals("no key = all off", emptySet<String>(), (SportsMappingParser.parse(without) as MappingParseResult.Parsed).mapping.uiSwitches)
    }

    @Test fun `the mapping lists the strips for their sports only`() {
        assertTrue(BlockType.BASKETBALL_STRIP in base.profile("basketball")!!.blockTypes)
        assertTrue(BlockType.HOCKEY_STRIP in base.profile("hockey")!!.blockTypes)
        assertFalse(BlockType.HOCKEY_STRIP in base.profile("basketball")!!.blockTypes)
        assertFalse("strips vanish after the whistle", BlockVisibility.visible(BlockType.BASKETBALL_STRIP, GameState.FINAL))
        assertTrue(BlockVisibility.visible(BlockType.HOCKEY_STRIP, GameState.LIVE))
    }

    @Test fun `inBonus - the competitor whose own bonusState is not NONE is in the bonus`() {
        val b = GameBlock.BasketballStrip(SourceId.ESPN, homeBonus = "DOUBLE", awayBonus = "NONE")
        assertTrue(BasketballStripPolicy.inBonus("home", b)); assertFalse(BasketballStripPolicy.inBonus("away", b))
        assertTrue("case-insensitive", BasketballStripPolicy.inBonus("away", GameBlock.BasketballStrip(SourceId.ESPN, awayBonus = "single")))
        assertFalse("unknown", BasketballStripPolicy.inBonus("home", GameBlock.BasketballStrip(SourceId.ESPN)))
        assertFalse("blank", BasketballStripPolicy.inBonus("home", GameBlock.BasketballStrip(SourceId.ESPN, homeBonus = "")))
    }

    private fun p(side: String?, pts: Int) = RunPlay(side, pts, scoring = true)
    private val miss = RunPlay("home", 0, scoring = false)

    @Test fun `run - 3 plus 3 plus 2 unanswered is an 8 point run and 7 is not enough`() {
        assertEquals(ScoringRun(SideKey.HOME, 8), BasketballRunPolicy.run(listOf(p("away", 2), p("home", 3), p("home", 3), p("home", 2))))
        assertNull(BasketballRunPolicy.run(listOf(p("home", 3), p("home", 2), p("home", 2))))
        assertNull("alternating", BasketballRunPolicy.run(listOf(p("home", 2), p("away", 2), p("home", 3), p("away", 3))))
    }
    @Test fun `run - the opponent scoring resets it and non scoring plays are ignored`() {
        assertNull(BasketballRunPolicy.run(listOf(p("home", 3), p("home", 3), p("home", 2), p("away", 1))))
        assertEquals(ScoringRun(SideKey.AWAY, 9), BasketballRunPolicy.run(listOf(p("home", 2), p("away", 3), miss, p("away", 3), miss, p("away", 3), miss)))
    }
    @Test fun `run - an unattributable scoring play is ambiguous and a long run reports its length`() {
        assertNull(BasketballRunPolicy.run(listOf(p("home", 3), p("home", 3), p(null, 2), p("home", 2)))); assertNull(BasketballRunPolicy.run(emptyList()))
        assertEquals(ScoringRun(SideKey.HOME, 14), BasketballRunPolicy.run(listOf(p("away", 2)) + List(7) { p("home", 2) }))
    }

    @Test fun `strip - fouls bonus and timeouts come straight from the header competitors of the live NBA capture`() {
        val summary = SportsFixtures.raw("espn/gs_nba_summary.json")
        val b = EspnBlockAssembler(base).block(BlockType.BASKETBALL_STRIP, "basketball", EspnDocs(SportsFixtures.json("espn/gs_nba_event.json"), json(summary))) as GameBlock.BasketballStrip
        assertEquals(2 to 4, b.homeFouls to b.awayFouls)
        assertEquals("DOUBLE" to "NONE", b.homeBonus to b.awayBonus)
        assertEquals(3 to 3, b.homeTimeouts to b.awayTimeouts)
        assertNull("no possession flag on that snapshot", b.possession); assertNull("derived run is off", b.run)
    }

    private val homeC = """{"homeAway":"home","id":"14","fouls":{"teamFoulsCurrent":5,"bonusState":"NONE"},"timeoutsRemaining":2,"possession":true}"""
    private val awayC = """{"homeAway":"away","id":"8","fouls":{"teamFoulsCurrent":1,"bonusState":"DOUBLE"},"timeoutsRemaining":4,"possession":false}"""
    private fun sum(hs: String, plays: String = "") = """{"header":{"competitions":[{"competitors":[$hs]}]},"plays":[$plays]}"""

    @Test fun `strip - possession is the competitor flagged true`() {
        assertEquals(SideKey.HOME, strip(sum("$homeC,$awayC"))?.possession)
        assertEquals(SideKey.AWAY, strip(sum("${homeC.replace("true", "false")},${awayC.replace("false", "true")}"))?.possession)
        assertNull(strip(sum("${homeC.replace("true", "false")},$awayC"))?.possession)
    }

    @Test fun `strip - a missing field hides only that element and nothing at all hides the block`() {
        val noFouls = strip(sum("""{"homeAway":"home","id":"14","timeoutsRemaining":2},$awayC"""))!!
        assertNull(noFouls.homeFouls); assertNull(noFouls.homeBonus); assertEquals(2, noFouls.homeTimeouts); assertEquals(1, noFouls.awayFouls)
        assertNull(strip(sum("""{"homeAway":"home","id":"14"},{"homeAway":"away","id":"8"}""")))
        assertNull(strip("{}"))
    }

    private fun scoring(id: Int, team: String, pts: Int) = """{"id":"$id","scoringPlay":true,"scoreValue":$pts,"team":{"id":"$team"},"text":"x","type":{"text":"Layup"}}"""
    private val eightZero = listOf(scoring(1, "8", 2), scoring(2, "14", 3), scoring(3, "14", 3), scoring(4, "14", 2)).joinToString(",")

    @Test fun `the run is derived only while its switch is on`() {
        val s = sum("$homeC,$awayC", eightZero)
        assertNull("off by default even though the plays make 8-0", strip(s)?.run)
        val on = withSwitches("\"basketballRun\": true, \"hockeyPowerPlayClock\": false")
        assertEquals(ScoringRun(SideKey.HOME, 8), strip(s, on)?.run)
        assertNull("under 8", strip(sum("$homeC,$awayC", listOf(scoring(1, "8", 2), scoring(2, "14", 3)).joinToString(",")), on)?.run)
    }

    private fun bd(b: GameBlock.BasketballStrip) = detailOf(b)

    @Test fun `moments - fouls timeouts and the bonus`() {
        val a = GameBlock.BasketballStrip(SourceId.ESPN, homeFouls = 3, awayFouls = 4, homeBonus = "NONE", awayBonus = "NONE", homeTimeouts = 3, awayTimeouts = 3)
        val b = a.copy(homeFouls = 4, awayBonus = "DOUBLE", awayTimeouts = 2)
        val d = GameMomentPolicy.diff(bd(a), bd(b))
        assertTrue(d.toString(), Moment.FoulCount(SideKey.HOME, 4) in d); assertTrue(Moment.BonusReached(SideKey.AWAY) in d); assertTrue(Moment.TimeoutUsed(SideKey.AWAY) in d)
        assertEquals(3, d.size)
    }
    @Test fun `moments - a new period resetting the fouls and a bonus already on are not moments`() {
        val a = GameBlock.BasketballStrip(SourceId.ESPN, homeFouls = 5, awayFouls = 5, homeBonus = "DOUBLE", homeTimeouts = 2)
        assertTrue(GameMomentPolicy.diff(bd(a), bd(a.copy(homeFouls = 0, awayFouls = 0))).isEmpty())
        assertTrue(GameMomentPolicy.diff(bd(a), bd(a.copy(homeFouls = 5))).isEmpty())
    }
}

class HockeyStripTest {
    private val base = SportsFixtures.mapping
    private fun json(s: String) = Json.parseToJsonElement(s)
    private fun withSwitches(body: String): SportsMapping {
        val text = SportsFixtures.raw("sports-mapping.v1.json").replace(Regex("\"uiSwitches\": \\{[^}]*\\}"), "\"uiSwitches\": {$body}")
        return (SportsMappingParser.parse(text) as MappingParseResult.Parsed).mapping
    }
    private val on = withSwitches("\"hockeyPowerPlayClock\": true, \"basketballRun\": false")

    private fun hp(side: String?, strength: String?, pen: String? = null, min: Int? = null, goal: Boolean = false, period: Int = 1, el: Int? = 0) =
        HockeyPlay(period, el, side, penaltyMinutes = min, penaltyType = pen, goal = goal, strength = strength)

    @Test fun `strength - power play is the play's own team and shorthanded is the other one`() {
        assertEquals("home", HockeyStrength.powerPlaySide(listOf(hp("away", "Even Strength"), hp("home", "Power Play"))))
        assertEquals("home", HockeyStrength.powerPlaySide(listOf(hp("away", "Shorthanded"))))
        assertEquals("away", HockeyStrength.powerPlaySide(listOf(hp("home", "Shorthanded"))))
    }
    @Test fun `strength - even strength and no data are no power play`() {
        assertNull(HockeyStrength.powerPlaySide(listOf(hp("home", "Power Play"), hp("home", "Even Strength"))))
        assertNull(HockeyStrength.powerPlaySide(listOf(hp("home", "Power Play"), hp(null, "Even Strength"))))
        assertNull(HockeyStrength.powerPlaySide(emptyList())); assertNull(HockeyStrength.powerPlaySide(listOf(hp("home", null))))
    }
    @Test fun `strength - penalty plays and team-less power play plays are skipped`() {
        assertNull(HockeyStrength.powerPlaySide(listOf(hp("home", "Even Strength"), hp("away", "Power Play", pen = "Major", min = 5))))
        assertEquals("away", HockeyStrength.powerPlaySide(listOf(hp("away", "Power Play"), hp(null, "Shorthanded"))))
    }

    private fun pen(side: String, period: Int, el: Int, min: Int = 2, type: String = "Minor") = hp(side, "Even Strength", pen = type, min = min, period = period, el = el)
    private fun now(elapsed: Int) = PowerPlayClock.REGULATION_PERIOD_SEC - elapsed

    @Test fun `clock - the play clock counts up and the status clock counts down`() {
        assertEquals(473, PowerPlayClock.elapsedInPeriod(3, 727)); assertEquals(0, PowerPlayClock.elapsedInPeriod(1, 1200))
        assertNull("overtime", PowerPlayClock.elapsedInPeriod(4, 200)); assertNull(PowerPlayClock.elapsedInPeriod(0, 200))
    }
    @Test fun `clock - the live capture agrees with that reading`() {
        val ev = SportsFixtures.json("espn/gs_nhl_event.json"); val sum = SportsFixtures.json("espn/gs_nhl_summary.json")
        val status = JsonPathReader.firstText(ev, "status.clock")!!.toDouble().toInt(); val period = JsonPathReader.firstText(ev, "status.period")!!.toInt()
        val last = JsonPathReader.read(sum, "plays[-1]").single()
        val (m, s) = JsonPathReader.firstText(last, "clock.displayValue")!!.split(":").map { it.toInt() }
        val played = PowerPlayClock.elapsedInPeriod(period, status)!!
        assertTrue("status clock is ahead of or at the newest play: $played vs ${m * 60 + s}", played - (m * 60 + s) in -1200..1200)
    }
    @Test fun `clock - one minor against the away team leaves the rest of its two minutes`() {
        val plays = listOf(pen("away", 1, 300))
        assertEquals(20, PowerPlayClock.secondsLeft(plays, 1, now(400), "home")); assertEquals(120, PowerPlayClock.secondsLeft(plays, 1, now(300), "home"))
    }
    @Test fun `clock - expired or no penalty gives no number`() {
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300)), 1, now(500), "home")); assertNull(PowerPlayClock.secondsLeft(emptyList(), 1, now(500), "home"))
    }
    @Test fun `clock - a double minor and a major are counted whole`() {
        assertEquals(190, PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300, 4, "Double Minor")), 1, now(350), "home"))
        assertEquals(250, PowerPlayClock.secondsLeft(listOf(pen("home", 1, 300, 5, "Major")), 1, now(350), "away"))
        assertEquals(100, PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300, 2, "Bench")), 1, now(320), "home"))
    }
    @Test fun `clock - a power play goal ends a minor but not a major`() {
        val ppg = hp("home", "Power Play", goal = true, el = 330)
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300), ppg), 1, now(340), "home"))
        assertEquals(260, PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300, 5, "Major"), ppg), 1, now(340), "home"))
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300, 4, "Double Minor"), ppg), 1, now(340), "home"))
    }
    @Test fun `clock - a goal by the penalised team does not end it`() =
        assertEquals(80, PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300), hp("away", "Shorthanded", goal = true, el = 330)), 1, now(340), "home"))
    @Test fun `clock - coincidental and overlapping penalties are ambiguous`() {
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("home", 1, 300, 5, "Major"), pen("away", 1, 300, 5, "Major")), 1, now(310), null))
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("home", 1, 300), pen("away", 1, 300)), 1, now(310), null))
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300), pen("away", 1, 330)), 1, now(340), "home"))
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300), pen("home", 1, 330)), 1, now(340), null))
    }
    @Test fun `clock - a penalty carries across the period break`() =
        assertEquals(40, PowerPlayClock.secondsLeft(listOf(pen("away", 1, 1150)), 2, now(30), "home"))
    @Test fun `clock - overtime and an unreadable clock give no number`() {
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 3, 1190)), 4, 250, "home"))
        assertNull(PowerPlayClock.secondsLeft(listOf(HockeyPlay(1, null, "away", 2, "Minor")), 1, now(400), "home"))
    }
    @Test fun `clock - misconducts and a contradicting feed side give no number`() {
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300, 10, "Misconduct")), 1, now(320), "home"))
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300)), 1, now(320), "away"))
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300)), 1, now(320), null))
    }

    private fun hockey(summary: String, event: kotlinx.serialization.json.JsonElement = SportsFixtures.json("espn/gs_nhl_event.json"), m: SportsMapping = base) =
        EspnBlockAssembler(m).block(BlockType.HOCKEY_STRIP, "hockey", EspnDocs(event, json(summary))) as? GameBlock.HockeyStrip

    @Test fun `strip - shots power plays and faceoffs are the boxscore numbers by team of the live NHL capture`() {
        val b = hockey(SportsFixtures.raw("espn/gs_nhl_summary.json"))!!
        // event: home id 9 (24 shots, PP 0/3, FO 33), away id 2 (20 shots, PP 0/5, FO 18)
        assertEquals(24 to 20, b.homeShots to b.awayShots); assertEquals("0/3" to "0/5", b.homePowerPlay to b.awayPowerPlay); assertEquals(33 to 18, b.homeFaceoffs to b.awayFaceoffs)
    }
    @Test fun `strip - a power play from the newest play and the clock only with its switch on`() {
        fun play(id: Int, team: String, strength: String, el: String, extra: String = "") =
            """{"id":"$id","text":"t$id","period":{"number":3,"displayValue":"3rd"},"clock":{"displayValue":"$el"},"team":{"id":"$team"},"strength":{"text":"$strength"},"scoringPlay":false,"type":{"text":"x"$extra}}"""
        val box = """"boxscore":{"teams":[{"team":{"id":"9"},"statistics":[{"label":"Shots","displayValue":"20"}]},{"team":{"id":"2"},"statistics":[{"label":"Shots","displayValue":"14"}]}]}"""
        // event capture: period 3, 368 s left = 832 elapsed (13:52). Away (id 2) minor at 13:00 -> home (id 9) power play, 2:00 -> ends 15:00 = 900 elapsed -> 68 s left.
        val s = """{$box,"plays":[${play(1, "2", "Even Strength", "13:00", ",\"penaltyMinutes\":\"2\",\"penaltyType\":\"Minor\"")},${play(2, "9", "Power Play", "13:30")}]}"""
        val off = hockey(s)!!
        assertEquals(SideKey.HOME, off.powerPlaySide); assertNull("derived: null while off", off.powerPlaySecondsLeft)
        assertEquals(68, hockey(s, m = on)?.powerPlaySecondsLeft)
    }
    @Test fun `strip - nothing in the summary means no block and other sports have none`() {
        assertNull(hockey("{}"))
        assertNull(EspnBlockAssembler(base).block(BlockType.HOCKEY_STRIP, "basketball", EspnDocs(SportsFixtures.json("espn/gs_nhl_event.json"), SportsFixtures.json("espn/gs_nhl_summary.json"))))
    }
    @Test fun `plays - a goal scored on the power play is tagged ppg and an even strength goal stays a goal`() {
        val plays = (EspnBlockAssembler(base).block(BlockType.PLAYS, "hockey", EspnDocs(SportsFixtures.json("espn/gs_nhl_event.json"), SportsFixtures.json("espn/gs_nhl_summary.json"))) as GameBlock.Plays).items
        assertEquals("even strength goal stays a goal", TimelineKind.GOAL, plays.first { it.text.contains("Jamie Benn Goal") }.kind)
        val ppg = """{"id":"x","text":"Someone Goal (1)","strength":{"text":"Power Play"},"scoringPlay":true,"type":{"text":"Goal"},"period":{"number":2},"clock":{"displayValue":"1:00"},"homeScore":1,"awayScore":0,"team":{"id":"9"}}"""
        val b = EspnBlockAssembler(base).block(BlockType.PLAYS, "hockey", EspnDocs(SportsFixtures.json("espn/gs_nhl_event.json"), json("""{"plays":[$ppg]}""")))
        assertEquals("the feed's own strength tags it", TimelineKind.PPG, (b as GameBlock.Plays).items.single().kind)
    }
    @Test fun `moments - a power play starting and ending`() {
        fun hd(h: GameBlock.HockeyStrip) = detailOf(h)
        val none = GameBlock.HockeyStrip(SourceId.ESPN); val home = none.copy(powerPlaySide = SideKey.HOME)
        assertEquals(listOf<Moment>(Moment.PowerPlay(SideKey.HOME, true)), GameMomentPolicy.diff(hd(none), hd(home)))
        assertEquals(listOf<Moment>(Moment.PowerPlay(null, false)), GameMomentPolicy.diff(hd(home), hd(none)))
        assertEquals(listOf<Moment>(Moment.PowerPlay(SideKey.AWAY, true)), GameMomentPolicy.diff(hd(home), hd(none.copy(powerPlaySide = SideKey.AWAY))))
        assertTrue("the bar stepping is not a moment", GameMomentPolicy.diff(hd(home.copy(powerPlaySecondsLeft = 60)), hd(home.copy(powerPlaySecondsLeft = 30))).isEmpty())
    }
}
