package com.nuvio.tv.core.sports.detail

import com.nuvio.tv.core.sports.detail.screen.GameDetailPoller
import com.nuvio.tv.core.sports.detail.screen.GameMomentPolicy
import com.nuvio.tv.core.sports.detail.screen.HockeyPlay
import com.nuvio.tv.core.sports.detail.screen.Moment
import com.nuvio.tv.core.sports.detail.screen.PowerPlayClock
import com.nuvio.tv.core.sports.detail.screen.detailOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core's review fixes (core-lane-report "Review fixes", items 1-7) hand-ported to TV. */
class GameScreenReviewFixesTest {
    // ---- 1: a LIVE -> FINAL event tick leaves a stale summary: fetch it once ----
    private var now = 1_000_000L
    private val ref = GameRef("espn:1", "1", "A", "B", 1_000_000L)
    private fun load(state: GameState) = DetailLoad.Loaded(GameDetail.skeleton(ref).copy(state = state, availability = Availability.FULL))
    private class Script(val results: MutableList<DetailLoad>, val calls: MutableList<Pair<Boolean, Boolean>> = mutableListOf()) : GameDetailSource {
        override suspend fun tick(fetchEvent: Boolean, fetchSummary: Boolean): DetailLoad {
            calls += fetchEvent to fetchSummary
            return if (results.size > 1) results.removeAt(0) else results[0]
        }
    }
    private fun poller(src: Script, failFinalSummary: Boolean = false) = GameDetailPoller(
        source = src, startsAtMs = ref.startsAtMs, initialState = GameState.LIVE,
        config = GameDetailRefreshPolicy.Config(), clock = { now }, delayFn = { ms -> now += ms },
    )

    @Test fun `fix 1 - going FINAL on an event tick fetches the summary once and then stops`() = runBlocking {
        val src = Script(mutableListOf(load(GameState.LIVE), load(GameState.FINAL)))
        poller(src).run { }
        assertEquals("first full tick, the event tick that flips to FINAL, then ONE summary-only tick", listOf(true to true, true to false, false to true), src.calls)
    }
    @Test fun `fix 1 - a game that is FINAL on its first load needs no extra summary`() = runBlocking {
        val src = Script(mutableListOf(load(GameState.FINAL)))
        poller(src).run { }
        assertEquals(listOf(true to true), src.calls)
    }
    @Test fun `fix 1 - a failed final summary is not retried in a loop`() = runBlocking {
        val src = Script(mutableListOf(load(GameState.LIVE), load(GameState.FINAL), DetailLoad.Failed()))
        poller(src).run { }
        assertEquals("one attempt only", 3, src.calls.size)
    }

    // ---- 2 / 3: play kinds ----
    private val event = Json.parseToJsonElement("""{"id":"1","status":{"type":{"state":"in"}},"competitions":[{"competitors":[
        {"id":"10","homeAway":"home","team":{"id":"10","displayName":"H"}},{"id":"20","homeAway":"away","team":{"id":"20","displayName":"A"}}]}]}""")
    private fun bPlay(id: Int, type: String, scoring: Boolean) =
        """{"id":"$id","type":{"text":"$type"},"text":"$type","period":{"displayValue":"1st Quarter"},"clock":{"displayValue":"1:00"},"wallclock":"2026-11-15T01:10:00Z","scoringPlay":$scoring,"team":{"id":"10"}}"""
    private fun kinds(vararg p: String): Map<String, TimelineKind?> {
        val sum = Json.parseToJsonElement("""{"plays":[${p.joinToString(",")}]}""")
        val b = EspnBlockAssembler(SportsFixtures.mapping).block(BlockType.PLAYS, "basketball", EspnDocs(event, sum)) as GameBlock.Plays
        return b.items.associate { it.text to it.kind }
    }

    @Test fun `fix 2 - Field Goal Good is a score and missed or blocked is no kind`() {
        val k = kinds(bPlay(1, "Field Goal Good", true), bPlay(2, "Field Goal Missed", false), bPlay(3, "Blocked Field Goal", false))
        assertEquals(TimelineKind.SCORE, k["Field Goal Good"]); assertNull(k["Field Goal Missed"]); assertNull(k["Blocked Field Goal"])
    }
    @Test fun `fix 2 - goaltending is not a goal but Goal still is`() {
        val k = kinds(bPlay(1, "Goaltending", false), bPlay(2, "Goal", true))
        assertNull("goaltending", k["Goaltending"]); assertEquals(TimelineKind.GOAL, k["Goal"])
    }
    @Test fun `fix 3 - a made shot is a score and a missed one is a shot`() {
        val k = kinds(bPlay(1, "Jump Shot", true), bPlay(2, "Missed Shot", false))
        assertEquals(TimelineKind.SCORE, k["Jump Shot"]); assertEquals(TimelineKind.SHOT, k["Missed Shot"])
    }

    // ---- 6: dividers from period starts only; soccer "Start 2nd Half" is a period start in the chart list ----
    @Test fun `fix 6 - soccer Start 2nd Half is a period start and reaches the chart items`() {
        val sum = Json.parseToJsonElement("""{"commentary":[
            {"play":{"id":"0","period":{"number":1},"type":{"text":"Goal"},"wallclock":"2026-11-15T01:20:00Z"},"text":"Goal!","time":{"displayValue":"20'"}},
            {"play":{"id":"1","period":{"number":2},"type":{"text":"Start 2nd Half"},"wallclock":"2026-11-15T01:50:00Z"},"text":"Second Half begins","time":{"displayValue":"45'"}}]}""")
        val b = EspnBlockAssembler(SportsFixtures.mapping).block(BlockType.PLAYS, "soccer", EspnDocs(event, sum)) as GameBlock.Plays
        assertTrue("the half start reaches the chart items", TimelineKind.PERIOD_START in b.chartItems.map { it.kind })
    }

    // ---- 4: first down on 1st -> 1st with a gain >= the distance ----
    private fun fd(yard: Int, down: Int?, distance: Int? = 10, poss: SideKey? = SideKey.HOME) = detailOf(GameBlock.Field(SourceId.ESPN, yard, poss, down, distance, null))
    @Test fun `fix 4 - 1st and 10 to a new 1st and 10 after a gain of 10 or more is a first down`() {
        assertTrue(GameMomentPolicy.diff(fd(25, 1), fd(40, 1)).filterIsInstance<Moment.FieldMove>().single().firstDown)
        assertEquals("a short gain is not", false, GameMomentPolicy.diff(fd(25, 1), fd(28, 1)).filterIsInstance<Moment.FieldMove>().single().firstDown)
        assertEquals("a LOSS is never a first down", false, GameMomentPolicy.diff(fd(40, 1), fd(25, 1)).filterIsInstance<Moment.FieldMove>().single().firstDown)
    }

    // ---- 5: only a penalty that could still be running makes the clock ambiguous ----
    @Test fun `fix 5 - an old unreadable penalty does not hide a clear current power play`() {
        val old = HockeyPlay(period = 1, elapsedSec = 100, side = "home", penaltyMinutes = null, penaltyType = "Minor")
        val current = HockeyPlay(period = 3, elapsedSec = 300, side = "away", penaltyMinutes = 2, penaltyType = "Minor")
        assertEquals(20, PowerPlayClock.secondsLeft(listOf(old, current), 3, PowerPlayClock.REGULATION_PERIOD_SEC - 400, "home"))
    }
    @Test fun `fix 5 - an unreadable penalty that could still be running stays ambiguous`() {
        val recent = HockeyPlay(period = 3, elapsedSec = 350, side = "home", penaltyMinutes = null, penaltyType = "Minor")
        val current = HockeyPlay(period = 3, elapsedSec = 300, side = "away", penaltyMinutes = 2, penaltyType = "Minor")
        assertNull(PowerPlayClock.secondsLeft(listOf(recent, current), 3, PowerPlayClock.REGULATION_PERIOD_SEC - 400, "home"))
    }

    // ---- 7: a summary behind the cached event score never replaces it ----
    private fun comp(h: Int, a: Int) = """{"competitors":[{"homeAway":"home","score":"$h"},{"homeAway":"away","score":"$a"}]}"""
    private fun scoreOf(r: kotlinx.serialization.json.JsonElement?, ha: String) =
        (r!!.jsonObject["competitions"] as JsonArray)[0].jsonObject["competitors"].let { it as JsonArray }.map { it.jsonObject }.first { it["homeAway"]!!.jsonPrimitive.content == ha }["score"]!!.jsonPrimitive.content
    @Test fun `fix 7 - an older summary score never replaces the newer event score`() {
        val base = Json.parseToJsonElement("""{"id":"1","status":{"type":{"state":"in"}},"competitions":[${comp(21, 14)}]}""")
        val summary = Json.parseToJsonElement("""{"header":{"id":"1","competitions":[${comp(14, 14)}]}}""")
        val r = EspnLiveOverlay.apply(base, summary, baseIsFresher = false)
        assertEquals("21", scoreOf(r, "home"))
    }
    @Test fun `fix 7 - a summary that is level or ahead still wins`() {
        val base = Json.parseToJsonElement("""{"id":"1","status":{"type":{"state":"in"}},"competitions":[${comp(14, 14)}]}""")
        val summary = Json.parseToJsonElement("""{"header":{"id":"1","competitions":[${comp(21, 14)}]}}""")
        assertEquals("21", scoreOf(EspnLiveOverlay.apply(base, summary, baseIsFresher = false), "home"))
    }
}
