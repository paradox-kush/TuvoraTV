package com.nuvio.tv.core.sports.detail

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Polymarket win chance: every response shape the backend produces (research/sports-mapping/fixtures/market-winchance-*.json,
 * copied to test resources), the kill-switch contract, the rules for the extras, and how the resolver composes it with ESPN.
 */
class MarketWinChanceTest {
    private fun fixture(name: String) = SportsFixtures.raw("market/market-winchance-$name.json")
    private val clock = FakeClock()
    private val base = SportsFixtures.mapping
    private fun on(all: Boolean = false, sports: Set<String> = emptySet(), leagues: Set<String> = emptySet()) =
        base.copy(killSwitch = base.killSwitch.copy(market = MarketSwitch(all, sports, leagues)))

    private fun load(body: String, code: Int = 200, ref: GameRef = nbaRef()): Pair<SourceLoad, FakeHttp> {
        val http = FakeHttp { HttpResponse(code, body, null) }
        val l = runBlocking { MarketWinChanceSource(http, { "https://b.invalid/functions/v1" }, clock::read).load(ref, setOf(RefreshPart.SUMMARY), base) }
        return l to http
    }

    private fun wp(l: SourceLoad) = l.blocks[BlockType.WIN_PROBABILITY] as GameBlock.WinProbability

    // ---- fixtures through the real adapter ----

    @Test fun `price two-way`() {
        val w = wp(load(fixture("price-two-way")).first)
        assertEquals(60.0, w.homePercent, 0.0); assertEquals(40.0, w.awayPercent!!, 0.0); assertNull(w.drawPercent)
        assertEquals(SourceId.MARKET, w.source); assertEquals("via Polymarket", w.attribution)
        assertEquals(1794073800000L, w.asOfMs)
        assertTrue(w.storylines.isEmpty()); assertTrue(w.history.isEmpty())
    }

    @Test fun `price three-way carries the draw`() {
        val w = wp(load(fixture("price-three-way")).first)
        assertEquals(Triple(16.0, 22.0, 62.0), Triple(w.homePercent, w.drawPercent, w.awayPercent))
    }

    @Test fun `storylines are read with their side`() {
        val two = wp(load(fixture("price-two-way-storylines")).first)
        assertEquals(listOf("Leading at half-time" to SideKey.HOME, "Goes to overtime" to null), two.storylines.map { it.label to it.side })
        assertEquals(listOf(62, 8), two.storylines.map { it.yes })
        assertEquals("Both teams to score", wp(load(fixture("price-three-way-storylines")).first).storylines.single().label)
    }

    @Test fun `history is read as given`() {
        val w = wp(load(fixture("history-two-way")).first)
        assertEquals(3, w.history.size); assertEquals(1794000000000L, w.history.first().t)
        val three = wp(load(fixture("history-three-way")).first)
        assertEquals(21, three.history.first().home); assertEquals(22, three.history.first().draw)
    }

    @Test fun `disabled, no market and failures are never a number`() {
        assertTrue(load(fixture("disabled")).first.disabled)
        assertEquals(FetchOutcome.EMPTY, load(fixture("no-market"), 404).first.outcome)
        assertEquals(FetchOutcome.EMPTY, load(fixture("no-market"), 200).first.outcome)
        assertEquals(FetchOutcome.HTTP_FAIL, load("{}", 429).first.outcome)
        assertEquals(FetchOutcome.HTTP_FAIL, load("{}", 502).first.outcome)
        assertEquals(FetchOutcome.PARSE_FAIL, load("not json").first.outcome)
        assertEquals(FetchOutcome.PARSE_FAIL, load("""{"away":40}""").first.outcome)
    }

    @Test fun `request carries the game, is slow-cadence only and asks history only near kickoff`() {
        val (_, http) = load(fixture("price-two-way"))
        val url = http.requests.single().first
        assertTrue(url, url.startsWith("https://b.invalid/functions/v1/market-winchance?game_id=tsdb%3A100&league_id=4387&home=Atlanta%20Hawks&away=Boston%20Celtics&starts_at=2027-01-15T08%3A00%3A00Z"))
        assertTrue("history near kickoff: $url", url.endsWith("&history=1"))
        val far = load(fixture("price-two-way"), ref = nbaRef().copy(startsAtMs = clock.now + 3 * 3_600_000L)).second.requests.single().first
        assertFalse("no history for a game hours away: $far", far.contains("history"))
        val src = MarketWinChanceSource(FakeHttp { error("never") }, { null })
        assertTrue(src.wants(setOf(RefreshPart.SUMMARY))); assertFalse("scoreboard tick: no request", src.wants(setOf(RefreshPart.SCOREBOARD)))
    }

    // ---- the kill switch: ON only for {all:false} ----

    @Test fun `kill switch market is an object and ON only with all false`() {
        fun parsed(ks: String) = (SportsMappingParser.parse(bundledWith(ks)) as MappingParseResult.Parsed).mapping
        assertFalse("bundled copy stays OFF", base.marketEnabledFor("4387"))
        assertTrue(parsed("""{"espn":false,"market":{"all":false}}""").marketEnabledFor("4387"))
        assertFalse(parsed("""{"market":{"all":true}}""").marketEnabledFor("4387"))
        assertFalse("boolean is not the object", parsed("""{"market":false}""").marketEnabledFor("4387"))
        assertFalse("missing all", parsed("""{"market":{}}""").marketEnabledFor("4387"))
        assertFalse("all not a boolean", parsed("""{"market":{"all":"false"}}""").marketEnabledFor("4387"))
        assertFalse("league listed", parsed("""{"market":{"all":false,"leagues":["4387"]}}""").marketEnabledFor("4387"))
        assertFalse("sport listed", parsed("""{"market":{"all":false,"sports":["Basketball"]}}""").marketEnabledFor("4387"))
        assertTrue("other league listed", parsed("""{"market":{"all":false,"leagues":["4328"]}}""").marketEnabledFor("4387"))
    }

    private fun bundledWith(killSwitch: String): String {
        val text = checkNotNull(BundledSportsMapping::class.java.classLoader!!.getResourceAsStream("sports/sports-mapping.v1.json")).bufferedReader().use { it.readText() }
        val old = "\"killSwitch\": { \"espn\": false, \"sports\": [], \"leagues\": [] }"
        assertTrue(text.contains(old))
        return text.replace(old, "\"killSwitch\": $killSwitch")
    }

    // ---- the rules for the extras ----

    private val t0 = 1_794_000_000_000L
    private fun series(vararg homes: Int, stepMs: Long = 60_000L) = homes.mapIndexed { i, h -> WinPoint(t0 + i * stepMs, h) }

    @Test fun `comeback watch needs a live game, a fresh series and a real rise`() {
        val rising = series(30, 36, 41, 47) // trailing home gained 17 over 3 min
        val asOf = rising.last().t + 30_000
        val c = MarketPolicy.comebackWatch(rising, 47, 53, GameState.LIVE, asOf)
        assertEquals(MarketPolicy.Comeback(SideKey.HOME, 17), c)
        assertNull("final", MarketPolicy.comebackWatch(rising, 47, 53, GameState.FINAL, asOf))
        assertNull("upcoming", MarketPolicy.comebackWatch(rising, 47, 53, GameState.UPCOMING, asOf))
        assertNull("series older than 2 min", MarketPolicy.comebackWatch(rising, 47, 53, GameState.LIVE, rising.last().t + 3 * 60_000))
        assertNull("no server time", MarketPolicy.comebackWatch(rising, 47, 53, GameState.LIVE, null))
        assertNull("two points", MarketPolicy.comebackWatch(rising.take(2), 47, 53, GameState.LIVE, asOf))
        assertNull("a spike that came back", MarketPolicy.comebackWatch(series(40, 60, 41), 41, 59, GameState.LIVE, t0 + 3 * 60_000))
        assertNull("rise under 15", MarketPolicy.comebackWatch(series(40, 45, 50), 50, 50, GameState.LIVE, t0 + 3 * 60_000))
    }

    @Test fun `comeback watch names the trailing side from the headline, not the series`() {
        // Away is the underdog in the headline (home 70) and gains: the series is home's share, so away's rise is home's fall.
        val falling = series(80, 74, 70, 64)
        assertEquals(MarketPolicy.Comeback(SideKey.AWAY, 16), MarketPolicy.comebackWatch(falling, 64, 36, GameState.LIVE, falling.last().t))
        // Soccer: a 3-way headline (home 16, draw 22, away 62): home trails, "vs 50" would name the wrong side.
        val homeRising = series(20, 28, 36)
        assertEquals(SideKey.HOME, MarketPolicy.comebackWatch(homeRising, 16, 62, GameState.LIVE, homeRising.last().t)?.side)
    }

    @Test fun `storylines vanish by state`() {
        val s = listOf(Storyline("first_half_moneyline", "Leading at half-time", 62, SideKey.HOME), Storyline("basketball_game_overtime", "Goes to overtime", 8), Storyline("both_teams_to_score", "BTTS", 58))
        fun keys(state: GameState, label: String?, sport: String) = MarketPolicy.visibleStorylines(s, state, label?.let { LiveInfo(periodLabel = it) }, sport).map { it.key }
        assertEquals("upcoming shows all", 3, keys(GameState.UPCOMING, null, "Basketball").size)
        assertEquals("final shows none", emptyList<String>(), keys(GameState.FINAL, null, "Basketball"))
        assertEquals("postponed shows none", emptyList<String>(), keys(GameState.POSTPONED, null, "Basketball"))
        assertEquals("Q2 still first half", 3, keys(GameState.LIVE, "Q2", "Basketball").size)
        assertFalse("Q3: first-half gone", "first_half_moneyline" in keys(GameState.LIVE, "Q3", "Basketball"))
        assertFalse("OT: overtime gone", "basketball_game_overtime" in keys(GameState.LIVE, "OT", "Basketball"))
        assertFalse("5th basketball period is overtime", "basketball_game_overtime" in keys(GameState.LIVE, "Q5", "Basketball"))
        assertTrue("hockey P3 is not overtime", "basketball_game_overtime" in keys(GameState.LIVE, "P3", "Ice Hockey"))
        assertFalse("soccer 2H: first-half gone", "first_half_moneyline" in keys(GameState.LIVE, "2H", "Soccer"))
    }

    @Test fun `storyline text carries the team tag once`() {
        assertEquals("Leading at half-time: DEN · 62%", MarketPolicy.storylineText(Storyline("k", "Leading at half-time", 62, SideKey.HOME), "DEN", "IND"))
        assertEquals("DEN to win · 62%", MarketPolicy.storylineText(Storyline("k", "DEN to win", 62, SideKey.HOME), "DEN", "IND"))
        assertEquals("Goes to overtime · 8%", MarketPolicy.storylineText(Storyline("k", "Goes to overtime", 8), "DEN", "IND"))
    }

    @Test fun `the card has its own staleness from the server time and only for a live market headline`() {
        val b = GameBlock.WinProbability(SourceId.MARKET, 60.0, asOfMs = 1_000_000)
        assertNull(MarketPolicy.staleLabel(b, GameState.LIVE, 1_000_000 + 119_000))
        assertEquals("Updated 3 min ago", MarketPolicy.staleLabel(b, GameState.LIVE, 1_000_000 + 3 * 60_000 + 1))
        assertNull("not live", MarketPolicy.staleLabel(b, GameState.UPCOMING, 1_000_000 + 10 * 60_000))
        assertNull("ESPN headline is not marked stale for stale extras", MarketPolicy.staleLabel(b.copy(source = SourceId.ESPN), GameState.LIVE, 1_000_000 + 10 * 60_000))
    }

    // ---- composition in the resolver ----

    private fun resolver(m: SportsMapping, market: FakeSource?, espn: FakeSource?, tsdb: FakeSource? = null) = GameDetailResolver(
        mappingProvider = { m },
        sources = listOfNotNull(tsdb, espn, market).associateBy { it.id },
        clock = clock::read,
    )

    private val espnWin = GameBlock.WinProbability(SourceId.ESPN, 55.0)
    private fun espn(state: GameState = GameState.LIVE) = FakeSource(SourceId.ESPN) { _, _ -> SourceLoad.ok(mapOf(BlockType.WIN_PROBABILITY to espnWin), state = state) }
    private fun market(block: GameBlock.WinProbability? = marketWin, load: SourceLoad? = null) =
        FakeSource(SourceId.MARKET, { RefreshPart.SUMMARY in it }) { _, _ -> load ?: SourceLoad.ok(mapOf(BlockType.WIN_PROBABILITY to block!!)) }
    private val marketWin = GameBlock.WinProbability(
        SourceId.MARKET, 60.0, awayPercent = 40.0, attribution = "via Polymarket", asOfMs = 777,
        storylines = listOf(Storyline("basketball_game_overtime", "Goes to overtime", 8)),
        history = series(50, 52, 55),
    )

    @Test fun `ESPN stays the headline and the market adds storylines and series`() = runBlocking {
        val d = resolver(on(), market(), espn()).resolve(nbaRef())
        val w = d.block(BlockType.WIN_PROBABILITY) as GameBlock.WinProbability
        assertEquals(SourceId.ESPN, w.source); assertEquals(55.0, w.homePercent, 0.0)
        assertEquals("Goes to overtime", w.storylines.single().label)
        assertEquals("via Polymarket", w.extrasAttribution); assertEquals(777L, w.asOfMs); assertEquals(3, w.history.size)
    }

    @Test fun `with no ESPN win chance the market is the headline, also before kickoff`() = runBlocking {
        val none = FakeSource(SourceId.ESPN) { _, _ -> SourceLoad.miss() }
        val live = resolver(on(), market(), none).resolve(nbaRef())
        assertEquals(SourceId.MARKET, (live.block(BlockType.WIN_PROBABILITY) as GameBlock.WinProbability).source)
        val up = resolver(on(), market(), none).resolve(nbaRef().copy(seedState = GameState.UPCOMING))
        assertEquals(SourceId.MARKET, (up.block(BlockType.WIN_PROBABILITY) as GameBlock.WinProbability).source)
    }

    @Test fun `ESPN win chance is live only, so before kickoff only the market shows`() = runBlocking {
        val e = FakeSource(SourceId.ESPN) { _, _ -> SourceLoad.ok(mapOf(BlockType.WIN_PROBABILITY to espnWin), state = GameState.UPCOMING) }
        val d = resolver(on(), market(), e).resolve(nbaRef().copy(seedState = GameState.UPCOMING))
        assertEquals(SourceId.MARKET, (d.block(BlockType.WIN_PROBABILITY) as GameBlock.WinProbability).source)
        val noMarket = resolver(base, null, e).resolve(nbaRef().copy(seedState = GameState.UPCOMING))
        assertNull("no ESPN number before the game", noMarket.block(BlockType.WIN_PROBABILITY))
    }

    @Test fun `switched off means no request and nothing shown`() = runBlocking {
        val m = market()
        val d = resolver(base, m, FakeSource(SourceId.ESPN) { _, _ -> SourceLoad.miss() }).resolve(nbaRef())
        assertEquals(0, m.calls); assertNull(d.block(BlockType.WIN_PROBABILITY))
        listOf(on(all = true), on(leagues = setOf("4387")), on(sports = setOf("basketball"))).forEach { off ->
            val mm = market()
            resolver(off, mm, null).resolve(nbaRef()); assertEquals("still off: ${off.killSwitch.market}", 0, mm.calls)
        }
    }

    @Test fun `a final or postponed game is never asked`() = runBlocking {
        listOf(GameState.FINAL, GameState.POSTPONED).forEach { s ->
            val m = market()
            val d = resolver(on(), m, null).resolve(nbaRef().copy(seedState = s))
            assertEquals(0, m.calls); assertNull(d.block(BlockType.WIN_PROBABILITY))
        }
    }

    @Test fun `only on the slow cadence, never on a scoreboard tick`() = runBlocking {
        val m = market()
        val r = resolver(on(), m, espn())
        r.resolve(nbaRef(), setOf(RefreshPart.SCOREBOARD))
        assertEquals(0, m.calls)
        r.resolve(nbaRef(), setOf(RefreshPart.SCOREBOARD, RefreshPart.SUMMARY))
        assertEquals(1, m.calls)
    }

    @Test fun `a miss or disabled answer is remembered per game, a failure cools down briefly`() = runBlocking {
        val miss = market(load = SourceLoad.miss())
        val r = resolver(on(), miss, espn())
        r.resolve(nbaRef()); r.resolve(nbaRef())
        assertEquals("miss asked once", 1, miss.calls)
        val disabled = market(load = SourceLoad.disabled())
        val r2 = resolver(on(), disabled, espn()); r2.resolve(nbaRef()); r2.resolve(nbaRef())
        assertEquals(1, disabled.calls)
        val failing = market(load = SourceLoad.failed(FetchOutcome.HTTP_FAIL))
        val r3 = resolver(on(), failing, espn()); r3.resolve(nbaRef()); r3.resolve(nbaRef())
        assertEquals("failure backs off", 1, failing.calls)
        clock.now += NegativeSourceCache.FAILURE_TTL_MS + 1
        r3.resolve(nbaRef()); assertEquals("asked again after the back-off", 2, failing.calls)
    }

    @Test fun `a failed market keeps the extras already on screen`() = runBlocking {
        val good = resolver(on(), market(), espn()).resolve(nbaRef())
        val failing = market(load = SourceLoad.failed(FetchOutcome.HTTP_FAIL))
        val next = resolver(on(), failing, espn()).resolve(nbaRef(), previous = good)
        val w = next.block(BlockType.WIN_PROBABILITY) as GameBlock.WinProbability
        assertEquals("Goes to overtime", w.storylines.single().label); assertEquals(777L, w.asOfMs)
        assertEquals(SourceStatus.FAILED, next.sourceStatus[SourceId.MARKET])
    }

    @Test fun `a market chain entry is accepted and never walked as a chain source`() {
        assertEquals(SourceId.MARKET, SourceId.fromWire("market")); assertEquals(SourceId.MARKET, SourceId.fromWire("polymarket"))
    }

    // ---- the TV card ----

    private fun model(w: GameBlock.WinProbability, state: GameState = GameState.LIVE, live: LiveInfo? = LiveInfo(periodLabel = "Q2")): com.nuvio.tv.core.sports.detail.screen.GameDetailTvModel {
        val d = GameDetail.skeleton(nbaRef()).copy(
            state = state, live = live, blocks = listOf(w), sport = "Basketball",
            header = GameHeader.Teams(Side("Denver Nuggets", short = "DEN"), Side("Indiana Pacers", short = "IND")),
        )
        return com.nuvio.tv.core.sports.detail.screen.GameDetailTvModel.from(d, nowMs = clock.now)
    }

    @Test fun `TV card shows the bar, the caption and exactly one extra line`() {
        val w = model(marketWin.copy(asOfMs = clock.now, storylines = listOf(Storyline("a", "Goes to overtime", 8), Storyline("b", "Both score", 50)))).winChance!!
        assertEquals(Triple(60, 40, null), Triple(w.homePercent, w.awayPercent, w.drawPercent))
        assertEquals("via Polymarket", w.caption); assertEquals("Goes to overtime · 8%", w.line)
        assertNull(w.staleLabel)
    }

    @Test fun `TV card prefers the comeback watch to a storyline`() {
        val rising = series(30, 36, 41, 47)
        val w = model(marketWin.copy(homePercent = 47.0, awayPercent = 53.0, history = rising, asOfMs = rising.last().t)).winChance!!
        assertEquals("Comeback watch · DEN +17 in 10 min", w.line)
    }

    @Test fun `TV card credits ESPN's headline and the market's storyline separately`() {
        val w = model(espnWin.copy(storylines = marketWin.storylines, extrasAttribution = "via Polymarket")).winChance!!
        assertEquals("via ESPN · storyline via Polymarket", w.caption)
        assertEquals(Pair(55, 45), w.homePercent to w.awayPercent)
    }

    @Test fun `TV card three-way shows the draw, and no storyline once the state rules say so`() {
        val w = model(marketWin.copy(homePercent = 16.0, drawPercent = 22.0, awayPercent = 62.0, storylines = listOf(Storyline("basketball_game_overtime", "Goes to overtime", 8))),
            live = LiveInfo(periodLabel = "OT")).winChance!!
        assertEquals(22, w.drawPercent); assertNull("overtime started", w.line)
        assertNotNull(model(marketWin).winChance)
    }
}
