package com.nuvio.tv.core.sports.detail

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the review of the TV core (2026-11). */
class ReviewFixesTest {
    private val clock = FakeClock()
    private val telemetry = RecordingTelemetry()
    private var mapping = SportsFixtures.mapping

    private fun resolver(tsdb: FakeSource?, espn: FakeSource?) = GameDetailResolver(
        { mapping }, listOfNotNull(tsdb, espn).associateBy { it.id }, telemetry, clock::read,
    )
    private fun teams(hs: String?, aws: String?) = GameHeader.Teams(Side("Atlanta Hawks", score = hs), Side("Boston Celtics", score = aws))
    private fun load(header: GameHeader? = null, state: GameState? = null, live: LiveInfo? = null, vararg b: GameBlock) =
        SourceLoad.ok(b.associateBy { it.type }, header, state, live)
    private val line = GameBlock.Linescore(SourceId.ESPN, listOf("Q1"), listOf(listOf("1"), listOf("2")))
    private val sit = GameBlock.Situation(SourceId.ESPN, "2nd & 7")
    private val win = GameBlock.WinProbability(SourceId.ESPN, 60.0)
    private val upcomingRef = nbaRef().copy(seedState = GameState.UPCOMING)

    // ---- 2: header / state survive a primary-source outage; ESPN takes over when the primary is switched off ----

    @Test fun `tsdb failing while espn has blocks keeps the older header state and start`() = runBlocking {
        val good = resolver(
            FakeSource(SourceId.TSDB) { _, _ -> load(teams("54", "50"), GameState.LIVE, LiveInfo(clock = "Q2")) },
            FakeSource(SourceId.ESPN) { _, _ -> load(null, null, null, line) },
        ).resolve(nbaRef())
        val tick = resolver(
            FakeSource(SourceId.TSDB) { _, _ -> SourceLoad.failed(FetchOutcome.HTTP_FAIL) },
            FakeSource(SourceId.ESPN) { _, _ -> load(null, null, null, line) },
        ).resolve(upcomingRef.copy(startsAtMs = 5))
        assertFalse("the seed stood in", tick.headerFromPrimary)
        val merged = tick.mergeOver(good)
        assertEquals("score kept", "54", (merged.header as GameHeader.Teams).home.score)
        assertEquals("still live so the poller keeps polling", GameState.LIVE, merged.state)
        assertEquals("start kept", good.startsAtMs, merged.startsAtMs)
    }

    @Test fun `primary switched off by the server makes espn the header authority`() = runBlocking {
        val d = resolver(
            FakeSource(SourceId.TSDB) { _, _ -> SourceLoad.disabled() },
            FakeSource(SourceId.ESPN) { _, _ -> load(teams("70", "65"), GameState.LIVE, LiveInfo(clock = "Q4 1:00"), line) },
        ).resolve(upcomingRef)
        assertEquals("espn header", "70", (d.header as GameHeader.Teams).home.score)
        assertEquals("espn state", GameState.LIVE, d.state)
        assertEquals("espn clock", "Q4 1:00", d.live?.clock)
        assertTrue("authoritative", d.headerFromPrimary)
        assertEquals("status", SourceStatus.DISABLED, d.sourceStatus[SourceId.TSDB])
    }

    @Test fun `the mapping kill switch for game detail does the same without a request`() = runBlocking {
        mapping = mapping.copy(killSwitch = KillSwitch(gameDetailAll = true))
        val tsdb = FakeSource(SourceId.TSDB) { _, _ -> error("must not be called") }
        val d = resolver(tsdb, FakeSource(SourceId.ESPN) { _, _ -> load(teams("1", "0"), GameState.LIVE, null, line) }).resolve(upcomingRef)
        assertEquals("not called", 0, tsdb.calls)
        assertEquals("espn header", "1", (d.header as GameHeader.Teams).home.score)
    }

    @Test fun `a first load with the primary down still shows the espn blocks`() = runBlocking {
        val d = resolver(
            FakeSource(SourceId.TSDB) { _, _ -> SourceLoad.failed(FetchOutcome.HTTP_FAIL) },
            FakeSource(SourceId.ESPN) { _, _ -> load(teams("1", "0"), GameState.LIVE, null, line) },
        ).resolve(upcomingRef)
        assertEquals("espn state used so live blocks are not hidden", GameState.LIVE, d.state)
        assertTrue("linescore shown", d.block(BlockType.LINESCORE) != null)
        assertFalse("but the header is still the seed", d.headerFromPrimary)
    }

    // ---- 3: live-only blocks vanish after the whistle ----

    @Test fun `visibility rules per state`() {
        assertTrue(BlockVisibility.visible(BlockType.SITUATION, GameState.LIVE))
        assertFalse(BlockVisibility.visible(BlockType.SITUATION, GameState.FINAL))
        assertFalse(BlockVisibility.visible(BlockType.WIN_PROBABILITY, GameState.FINAL))
        assertTrue(BlockVisibility.visible(BlockType.LINESCORE, GameState.FINAL))
        assertTrue(BlockVisibility.visible(BlockType.BROADCAST, GameState.UPCOMING))
        assertFalse(BlockVisibility.visible(BlockType.STATS, GameState.UPCOMING))
        assertTrue(BlockVisibility.visible(BlockType.BROADCAST, GameState.POSTPONED))
        assertFalse(BlockVisibility.visible(BlockType.LINESCORE, GameState.POSTPONED))
    }

    @Test fun `going FINAL drops situation and win probability when merged`() = runBlocking {
        val live = resolver(
            FakeSource(SourceId.TSDB) { _, _ -> load(teams("54", "50"), GameState.LIVE) },
            FakeSource(SourceId.ESPN) { _, _ -> load(null, null, null, line, sit, win) },
        ).resolve(nbaRef())
        assertTrue("shown while live", live.block(BlockType.SITUATION) != null && live.block(BlockType.WIN_PROBABILITY) != null)
        val fin = resolver(
            FakeSource(SourceId.TSDB) { _, _ -> load(teams("100", "90"), GameState.FINAL) },
            FakeSource(SourceId.ESPN) { _, _ -> load(null, null, null, line) },
        ).resolve(nbaRef())
        val merged = fin.mergeOver(live)
        assertNull("situation gone", merged.block(BlockType.SITUATION))
        assertNull("win probability gone", merged.block(BlockType.WIN_PROBABILITY))
        assertTrue("linescore stays", merged.block(BlockType.LINESCORE) != null)
        assertFalse("sources map pruned too", BlockType.SITUATION in merged.sources)
    }

    // ---- 12: ESPN's clock only across an agreeing score ----

    @Test fun `espn clock is used when the score agrees`() = runBlocking {
        val d = resolver(
            FakeSource(SourceId.TSDB) { _, _ -> load(teams("54", "50"), GameState.LIVE, LiveInfo(clock = "Q2")) },
            FakeSource(SourceId.ESPN) { _, _ -> load(teams("54", "50"), GameState.LIVE, LiveInfo(clock = "Q2 5:12"), line) },
        ).resolve(nbaRef())
        assertEquals("richer clock", "Q2 5:12", d.live?.clock)
    }

    @Test fun `espn clock is ignored when the score disagrees`() = runBlocking {
        val d = resolver(
            FakeSource(SourceId.TSDB) { _, _ -> load(teams("54", "50"), GameState.LIVE, LiveInfo(clock = "Q2")) },
            FakeSource(SourceId.ESPN) { _, _ -> load(teams("60", "50"), GameState.LIVE, LiveInfo(clock = "Q3 1:00"), line) },
        ).resolve(nbaRef())
        assertEquals("primary clock", "Q2", d.live?.clock)
    }

    @Test fun `with no primary live info a disagreeing espn clock is still not borrowed`() = runBlocking {
        val d = resolver(
            FakeSource(SourceId.TSDB) { _, _ -> load(teams("54", "50"), GameState.LIVE) },
            FakeSource(SourceId.ESPN) { _, _ -> load(teams("0", "0"), GameState.LIVE, LiveInfo(clock = "Q1 12:00"), line) },
        ).resolve(nbaRef())
        assertNull("no clock", d.live)
    }

    // ---- 8: telemetry volume ----

    @Test fun `telemetry is reported once per sport block source outcome per session`() = runBlocking {
        val r = resolver(
            FakeSource(SourceId.TSDB) { _, _ -> SourceLoad.failed(FetchOutcome.HTTP_FAIL) },
            FakeSource(SourceId.ESPN) { _, _ -> load(null, null, null, line) },
        )
        r.resolve(nbaRef("tsdb:1")); val first = telemetry.events.size
        repeat(5) { r.resolve(nbaRef("tsdb:1")); r.resolve(nbaRef("tsdb:2")) }
        assertEquals("no repeats on later ticks", first, telemetry.events.size)
        assertEquals("keys unique", telemetry.events.size, telemetry.events.map { listOf(it.sport, it.block, it.source, it.outcome) }.toSet().size)
    }

    @Test fun `blocks a source was not asked for are not reported as empty`() = runBlocking {
        val tsdb = FakeSource(SourceId.TSDB) { _, _ -> load(teams("1", "0"), GameState.LIVE).copy(asked = setOf(BlockType.STATS)) }
        resolver(tsdb, null).resolve(nbaRef())
        val tsdbEvents = telemetry.events.filter { it.source == SourceId.TSDB }
        assertTrue("only the asked block", tsdbEvents.all { it.block == BlockType.STATS })
    }

    // ---- 14: parallel sources ----

    @Test fun `sources are asked in parallel so the worst case is one deadline not the sum`() = runBlocking {
        val tsdb = FakeSource(SourceId.TSDB) { _, _ -> kotlinx.coroutines.delay(400); load(teams("1", "0"), GameState.LIVE) }
        val espn = FakeSource(SourceId.ESPN) { _, _ -> kotlinx.coroutines.delay(400); load(null, null, null, line) }
        val t0 = System.currentTimeMillis()
        resolver(tsdb, espn).resolve(nbaRef())
        val took = System.currentTimeMillis() - t0
        assertTrue("took $took ms, sequential would be 800+", took < 700)
    }

    @Test fun `the worst resolve time fits inside the screens load deadline`() {
        assertTrue(
            "per-call deadline ${SportsFixtures.mapping.deadlineMs} < ui deadline",
            SportsFixtures.mapping.deadlineMs < com.nuvio.tv.core.sports.detail.screen.GameDetailUiReducer.LOAD_DEADLINE_MS,
        )
    }

    // ---- 9: mapping validation ----

    private val bundledText = SportsFixtures::class.java.classLoader!!.getResourceAsStream("sports/sports-mapping.v1.json")!!.bufferedReader().readText()
    private fun offer(edit: (String) -> String): OfferResult =
        SportsMappingStore(BundledSportsMapping.load(), InMemoryMappingPersistence(), SmokeFixture.bundled()).offer(edit(bundledText).replace("2026-11-07.1", "2026-12-01.1"))
    private fun assertRejected(why: String, edit: (String) -> String) {
        val r = offer(edit)
        assertTrue("$why -> $r", r is OfferResult.Rejected)
    }

    @Test fun `a pushed deadline of zero or negative is rejected`() {
        assertRejected("0") { it.replace(""""deadlineMs": 8000""", """"deadlineMs": 0""") }
        assertRejected("negative") { it.replace(""""deadlineMs": 8000""", """"deadlineMs": -5""") }
        assertRejected("huge") { it.replace(""""deadlineMs": 8000""", """"deadlineMs": 600000""") }
    }
    @Test fun `espn templates must be https on the espn host with their placeholders`() {
        assertRejected("http") { it.replace("https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard", "http://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard") }
        assertRejected("other host") { it.replace("https://site.api.espn.com/apis/site/v2/sports/{path}/summary", "https://evil.example/apis/site/v2/sports/{path}/summary") }
        assertRejected("no placeholder") { it.replace("scoreboard?dates={yyyymmdd}", "scoreboard?dates=today") }
        assertRejected("summary id") { it.replace("summary?event={id}", "summary?event=1") }
    }
    @Test fun `leagues must point at an existing profile`() =
        assertRejected("dangling profile") { it.replace(""""profile": "basketball" }""", """"profile": "nonexistent" }""") }
    @Test fun `league chains must name known sources`() {
        assertRejected("unknown source") { it.replaceFirst(""""profile": "basketball" }""", """"profile": "basketball", "chain": ["tsdb", "bogus"] }""") }
        assertRejected("empty chain") { it.replaceFirst(""""profile": "basketball" }""", """"profile": "basketball", "chain": [] }""") }
    }
    @Test fun `refresh cadence cannot be pushed under a second`() =
        assertRejected("liveMs 500") { it.replace(""""liveMs": 15000""", """"liveMs": 500""") }
    @Test fun `a sane edit is still adopted`() {
        assertTrue("adopted", offer { it.replace(""""liveMs": 15000""", """"liveMs": 20000""") } is OfferResult.Adopted)
    }

    // ---- 10: cadence ----

    @Test fun `pre kickoff the event ticks on the 60 second cadence not the live one`() {
        val now = 1_000_000L
        val soon = now + 10 * 60_000
        assertFalse("20s since last: not due", GameDetailRefreshPolicy.plan(GameState.UPCOMING, soon, now, now - 20_000, null).fetchEvent)
        assertFalse("45s since last: not due", GameDetailRefreshPolicy.plan(GameState.UPCOMING, soon, now, now - 45_000, null).fetchEvent)
        assertTrue("61s: due", GameDetailRefreshPolicy.plan(GameState.UPCOMING, soon, now, now - 61_000, null).fetchEvent)
    }

    @Test fun `a league without a summary reuses the scoreboard between slow reads`() = runBlocking {
        val f1 = SportsFixtures.raw("espn/f1_event.json")
        val h = FakeHttp { u -> if (u.contains("/scoreboard")) HttpResponse(200, """{"events":[$f1]}""", null) else HttpResponse(404, null, null) }
        val ref = nbaRef("tsdb:9").copy(
            sport = "Motorsport", leagueId = "4370", homeName = "", awayName = "", eventName = "Formula 1 São Paulo Grand Prix 2026",
            startsAtMs = java.time.Instant.parse("2026-11-06T15:30:00Z").toEpochMilli(),
        )
        val src = EspnSource(h, clock::read)
        repeat(4) { clock.now += 15_000; src.load(ref, setOf(RefreshPart.SCOREBOARD), SportsFixtures.mapping) }
        assertEquals("one scoreboard read inside the minute", 1, h.requests.size)
        clock.now += 61_000
        src.load(ref, setOf(RefreshPart.SCOREBOARD), SportsFixtures.mapping)
        assertEquals("refreshed after the slow cadence", 2, h.requests.size)
    }

    // ---- 1: mapping sync is throttled so it is safe on every screen open ----

    @Test fun `syncMapping asks the network at most once per interval`() = runBlocking {
        val h = FakeHttp { HttpResponse(304, null, null) }
        val svc = SportsDetail.create(h, { "https://b/functions/v1" }, clock = clock::read)
        svc.syncMapping(); svc.syncMapping(); svc.syncMapping()
        assertEquals("one request", 1, h.requests.size)
        assertTrue("it is the mapping function", h.requests[0].first.endsWith("/sports-mapping"))
        clock.now += SportsDetail.SYNC_MIN_INTERVAL_MS + 1
        svc.syncMapping()
        assertEquals("again after the interval", 2, h.requests.size)
    }
}
