package com.nuvio.tv.core.sports.detail

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EspnSourceTest {
    private val clock = FakeClock(java.time.Instant.parse("2026-11-07T12:40:00Z").toEpochMilli())
    private val eplRef = GameRef(
        gameId = "tsdb:2494097", sport = "Soccer", leagueId = "4328", leagueName = "English Premier League",
        homeName = "Leeds United", awayName = "Tottenham Hotspur", startsAtMs = java.time.Instant.parse("2026-11-07T12:30:00Z").toEpochMilli(),
    )
    private fun http(status: Int = 200, body: (String) -> String? = { u ->
        when {
            u.contains("/scoreboard") -> SportsFixtures.raw("espn/epl_scoreboard.json")
            u.contains("/summary") -> SportsFixtures.raw("espn/epl_summary_post.json")
            else -> null
        }
    }) = FakeHttp { HttpResponse(status, body(it), null) }

    private fun source(h: FakeHttp, map: SportsMapping = SportsFixtures.mapping) = EspnSource(h, clock::read)

    @Test fun `matches the event then loads scoreboard and summary blocks`() = runBlocking {
        val h = http { u ->
            when {
                u.contains("/scoreboard") -> SportsFixtures.raw("espn/epl_scoreboard.json")
                u.contains("/summary") -> SportsFixtures.raw("espn/epl_summary_post.json")
                else -> null
            }
        }
        val l = source(h).load(eplRef, setOf(RefreshPart.SCOREBOARD, RefreshPart.SUMMARY), SportsFixtures.mapping)
        assertEquals("outcome", FetchOutcome.HIT, l.outcome)
        assertTrue("stats from summary", BlockType.STATS in l.blocks)
        assertTrue("form from scoreboard event", BlockType.FORM in l.blocks)
        assertTrue("scoreboard url uses path and yyyymmdd", h.requests.first().first.endsWith("/soccer/eng.1/scoreboard?dates=20261107"))
        assertTrue("summary asks for the matched id", h.requests.any { it.first.endsWith("/soccer/eng.1/summary?event=401878758") })
        assertEquals("live info", "Tottenham Hotspur", ((l.header as GameHeader.Teams).away.name))
    }

    @Test fun `live tick requests only that events summary never the whole-day scoreboard`() = runBlocking {
        val h = http()
        val s = source(h)
        s.load(eplRef, RefreshPart.ALL, SportsFixtures.mapping) // resolves the event id once
        h.requests.clear()
        val tick = s.load(eplRef, setOf(RefreshPart.SCOREBOARD), SportsFixtures.mapping)
        assertEquals("exactly one request", 1, h.requests.size)
        assertTrue("it is the per-game summary", h.requests[0].first.endsWith("/soccer/eng.1/summary?event=401878758"))
        assertTrue("never the scoreboard", h.requests.none { it.first.contains("/scoreboard") })
        assertEquals("score header comes from the summary", "Leeds United", ((tick.header as GameHeader.Teams).home.name))
        assertEquals("state from the summary status", GameState.FINAL, tick.state)
    }

    @Test fun `the first ever load resolves via the scoreboard once and every later tick skips it`() = runBlocking {
        val h = http()
        val s = source(h)
        s.load(eplRef, setOf(RefreshPart.SCOREBOARD), SportsFixtures.mapping)
        assertEquals("scoreboard then summary", listOf(true, false), h.requests.map { it.first.contains("/scoreboard") })
        h.requests.clear()
        repeat(3) { s.load(eplRef, setOf(RefreshPart.SCOREBOARD), SportsFixtures.mapping) }
        assertEquals("3 ticks, 3 summaries, 0 scoreboards", 3, h.requests.count { it.first.contains("/summary?event=") })
        assertEquals("no scoreboard", 0, h.requests.count { it.first.contains("/scoreboard") })
    }

    @Test fun `a tick assembles linescore from the summary header`() = runBlocking {
        val nbaId = SportsFixtures.espnEvent("nba").let { JsonPathReader.firstText(it, "id")!! }
        val m = SportsFixtures.mapping.copy(eventOverrides = mapOf("100" to nbaId))
        val h = FakeHttp { HttpResponse(200, SportsFixtures.raw("espn/nba_summary_post.json"), null) }
        val l = EspnSource(h, clock::read).load(nbaRef("tsdb:100"), setOf(RefreshPart.SCOREBOARD), m)
        assertEquals("one request, a summary", 1, h.requests.size)
        assertTrue("summary url", h.requests[0].first.contains("/basketball/nba/summary?event=$nbaId"))
        val line = l.blocks[BlockType.LINESCORE] as GameBlock.Linescore
        assertEquals("home quarters", listOf("28", "38", "39", "34"), line.rows[0])
        assertEquals("home score", "139", ((l.header as GameHeader.Teams).home.score))
    }

    @Test fun `the matched event id is cached per game so the summary tick skips the scoreboard`() = runBlocking {
        val h = http()
        val s = source(h)
        s.load(eplRef, setOf(RefreshPart.SCOREBOARD), SportsFixtures.mapping)
        h.requests.clear()
        s.load(eplRef, setOf(RefreshPart.SUMMARY), SportsFixtures.mapping)
        assertEquals("one request", 1, h.requests.size)
        assertTrue("summary", h.requests[0].first.contains("/summary?event=401878758"))
    }

    @Test fun `no matching event is a miss`() = runBlocking {
        val l = source(http()).load(eplRef.copy(homeName = "Nobody FC", awayName = "Ghosts"), setOf(RefreshPart.SCOREBOARD), SportsFixtures.mapping)
        assertEquals("miss", FetchOutcome.EMPTY, l.outcome)
    }

    @Test fun `http 403 is http_fail not empty`() = runBlocking {
        val l = source(http(403) { null }).load(eplRef, setOf(RefreshPart.SCOREBOARD), SportsFixtures.mapping)
        assertEquals("http_fail", FetchOutcome.HTTP_FAIL, l.outcome)
    }

    @Test fun `an html body is parse_fail`() = runBlocking {
        val l = source(http { "<html>blocked</html>" }).load(eplRef, setOf(RefreshPart.SCOREBOARD), SportsFixtures.mapping)
        assertEquals("parse_fail", FetchOutcome.PARSE_FAIL, l.outcome)
    }

    @Test fun `a thrown io error is http_fail`() = runBlocking {
        val h = object : HttpFetcher { override suspend fun get(url: String, headers: Map<String, String>, deadlineMs: Long): HttpResponse = throw java.io.IOException("reset") }
        assertEquals("io", FetchOutcome.HTTP_FAIL, EspnSource(h, clock::read).load(eplRef, setOf(RefreshPart.SCOREBOARD), SportsFixtures.mapping).outcome)
    }

    @Test fun `leagues with summary=false never request a summary`() = runBlocking {
        val ref = eplRef.copy(sport = "Motorsport", leagueId = "4370", homeName = "", awayName = "", eventName = "Formula 1 São Paulo Grand Prix 2026", startsAtMs = java.time.Instant.parse("2026-11-06T15:30:00Z").toEpochMilli())
        val f1 = SportsFixtures.raw("espn/f1_event.json")
        val h = http { u -> if (u.contains("/scoreboard")) """{"events":[$f1]}""" else null }
        val l = source(h).load(ref, setOf(RefreshPart.SCOREBOARD, RefreshPart.SUMMARY), SportsFixtures.mapping)
        assertTrue("no summary request", h.requests.none { it.first.contains("/summary") })
        assertEquals("matched by name and results assembled", FetchOutcome.HIT, l.outcome)
        assertTrue("results", l.blocks.containsKey(BlockType.RESULTS))
        assertTrue("event header", l.header is GameHeader.Event)
    }

    @Test fun `eventOverrides skip the scoreboard entirely`() = runBlocking {
        val m = SportsFixtures.mapping.copy(eventOverrides = mapOf("2494097" to "401878758"))
        val h = http()
        EspnSource(h, clock::read).load(eplRef, setOf(RefreshPart.SUMMARY), m)
        assertTrue("summary only", h.requests.size == 1 && h.requests[0].first.contains("/summary?event=401878758"))
    }
}

class TheSportsDbSourceTest {
    private val ref = nbaRef("tsdb:2494097").copy(sport = "Soccer", leagueId = "4328", homeName = "Leeds United", awayName = "Tottenham Hotspur")
    private fun source(h: HttpFetcher, base: String? = "https://backend.example/functions/v1") = TheSportsDbSource(h, { base }, FakeClock()::read)
    private fun ok() = FakeHttp { HttpResponse(200, SportsFixtures.gameDetail("game_detail_soccer_live"), null) }

    @Test fun `decodes the backends normalised game detail`() = runBlocking {
        val l = source(ok()).load(ref, RefreshPart.ALL, SportsFixtures.mapping)
        assertEquals("hit", FetchOutcome.HIT, l.outcome)
        val h = l.header as GameHeader.Teams
        assertEquals("home", "Leeds United", h.home.name)
        assertEquals("score", "1", h.home.score)
        assertTrue("badge", h.home.badgeUrl!!.startsWith("https://"))
        assertEquals("state", GameState.LIVE, l.state)
        assertEquals("live detail", "2nd half", l.live?.detail)
        assertEquals("all known block types, unknown dropped", setOf(BlockType.STATS, BlockType.TIMELINE, BlockType.BROADCAST, BlockType.LINEUP, BlockType.RESULTS, BlockType.STANDINGS, BlockType.FORM, BlockType.HIGHLIGHTS), l.blocks.keys)
    }

    @Test fun `block details survive decoding`() = runBlocking {
        val b = source(ok()).load(ref, RefreshPart.ALL, SportsFixtures.mapping).blocks
        val stats = b[BlockType.STATS] as GameBlock.Stats
        assertEquals("unit kept", "%", stats.rows.first { it.label == "Possession" }.unit)
        val card = (b[BlockType.TIMELINE] as GameBlock.Timeline).items.first { it.kind == TimelineKind.CARD }
        assertEquals("away card", SideKey.AWAY, card.side)
        assertEquals("time", "15'", card.time)
        assertTrue("broadcast region", (b[BlockType.BROADCAST] as GameBlock.Broadcast).channels.any { it.region == "Iceland" })
        val lu = b[BlockType.LINEUP] as GameBlock.Lineup
        assertEquals("formation", "4-3-3", lu.sides[0].formation)
        assertEquals("sub", false, lu.sides[0].players[1].starter)
        assertEquals("form", listOf("W", "L", "D", "D", "L"), (b[BlockType.FORM] as GameBlock.Form).home)
        assertEquals("standing", "Arsenal", (b[BlockType.STANDINGS] as GameBlock.Standings).rows[0].name)
        assertEquals("highlights", "https://www.youtube.com/watch?v=abc", (b[BlockType.HIGHLIGHTS] as GameBlock.Highlights).url)
    }

    @Test fun `calls the game-detail constant with ids on a summary cadence load`() = runBlocking {
        val h = ok()
        source(h).load(ref, RefreshPart.ALL, SportsFixtures.mapping)
        assertEquals("full", "https://backend.example/functions/v1/${SportsDetailPaths.GAME_DETAIL}?event_id=2494097&league_id=4328", h.requests[0].first)
        assertEquals("function names are the backend's", "game-detail" to "sports-mapping", SportsDetailPaths.GAME_DETAIL to SportsDetailPaths.SPORTS_MAPPING)
    }

    @Test fun `a live event tick never reaches the backend`() = runBlocking {
        val h = ok()
        val s = source(h)
        assertEquals("not wanted", false, s.wants(setOf(RefreshPart.SCOREBOARD)))
        assertEquals("wanted on the slow cadence", true, s.wants(setOf(RefreshPart.SUMMARY)))
        val r = GameDetailResolver({ SportsFixtures.mapping }, mapOf(SourceId.TSDB to s), clock = FakeClock()::read)
        r.resolve(ref, setOf(RefreshPart.SCOREBOARD))
        assertTrue("no request", h.requests.isEmpty())
    }

    @Test fun `disabled true from the server is honoured`() = runBlocking {
        val l = source(FakeHttp { HttpResponse(200, SportsFixtures.gameDetail("game_detail_disabled"), null) }).load(ref, RefreshPart.ALL, SportsFixtures.mapping)
        assertTrue("disabled", l.disabled)
        assertTrue("no blocks", l.blocks.isEmpty())
    }

    @Test fun `no backend configured is a miss not a crash`() = runBlocking {
        assertEquals("miss", FetchOutcome.EMPTY, source(FakeHttp { error("unused") }, base = null).load(ref, RefreshPart.ALL, SportsFixtures.mapping).outcome)
    }
    @Test fun `espn-only game ids are not asked of the backend`() = runBlocking {
        val h = FakeHttp { error("unused") }
        assertEquals("miss", FetchOutcome.EMPTY, source(h).load(ref.copy(gameId = "espn:5"), RefreshPart.ALL, SportsFixtures.mapping).outcome)
        assertTrue("no request", h.requests.isEmpty())
    }
    @Test fun `status codes map to outcomes`() = runBlocking {
        fun out(code: Int, body: String? = "{}") = runBlocking { source(FakeHttp { HttpResponse(code, body, null) }).load(ref, RefreshPart.ALL, SportsFixtures.mapping).outcome }
        assertEquals("404 unknown event", FetchOutcome.EMPTY, out(404))
        assertEquals("502 upstream", FetchOutcome.HTTP_FAIL, out(502))
        assertEquals("400", FetchOutcome.HTTP_FAIL, out(400))
        assertEquals("html", FetchOutcome.PARSE_FAIL, out(200, "<html>"))
    }
    @Test fun `server kill switch in the mapping skips the backend call entirely`() = runBlocking {
        val tsdb = source(FakeHttp { error("must not be called") })
        val r = GameDetailResolver({ SportsFixtures.mapping.copy(killSwitch = KillSwitch(gameDetailAll = true)) }, mapOf(SourceId.TSDB to tsdb), clock = FakeClock()::read)
        assertEquals("disabled", SourceStatus.DISABLED, r.resolve(ref).sourceStatus[SourceId.TSDB])
    }
}

class SportsDetailFacadeTest {
    private val epl = nbaRef("tsdb:2494097").copy(sport = "Soccer", leagueId = "4328", homeName = "Leeds United", awayName = "Tottenham Hotspur", startsAtMs = java.time.Instant.parse("2026-11-07T12:30:00Z").toEpochMilli())
    private fun http() = FakeHttp { u ->
        when {
            u.contains("/game-detail") -> HttpResponse(200, SportsFixtures.gameDetail("game_detail_soccer_live"), null)
            u.contains("/scoreboard") -> HttpResponse(200, SportsFixtures.raw("espn/epl_scoreboard.json"), null)
            u.contains("/summary") -> HttpResponse(200, SportsFixtures.raw("espn/epl_summary_post.json"), null)
            else -> HttpResponse(404, null, null)
        }
    }

    @Test fun `tick merges espn and backend blocks with tsdb first`() = runBlocking {
        val src = SportsDetail.create(http(), { "https://b/functions/v1" }).open(epl)
        val d = (src.tick() as DetailLoad.Loaded).detail
        assertEquals("stats from tsdb", SourceId.TSDB, d.sources[BlockType.STATS])
        assertEquals("header primary tsdb", "1", (d.header as GameHeader.Teams).home.score)
        assertEquals("espn probed and answered; tsdb already filled every soccer block", SourceStatus.OK, d.sourceStatus[SourceId.ESPN])
    }

    private val http = http()

    @Test fun `an event-only tick keeps the summary blocks from the previous tick`() = runBlocking {
        val src = SportsDetail.create(http, { "https://b/functions/v1" }).open(epl)
        val first = (src.tick() as DetailLoad.Loaded).detail
        val h2 = http
        h2.requests.clear()
        val second = (src.tick(fetchEvent = true, fetchSummary = false) as DetailLoad.Loaded).detail
        assertEquals("live tick = exactly one ESPN summary, no backend, no scoreboard", 1, h2.requests.size)
        assertTrue("it is the per-game summary", h2.requests[0].first.contains("/summary?event="))
        assertEquals("nothing lost", first.blocks.map { it.type }.toSet(), second.blocks.map { it.type }.toSet())
    }

    @Test fun `all sources down is Failed not Empty`() = runBlocking {
        val src = SportsDetail.create(FakeHttp { HttpResponse(503, null, null) }, { "https://b/functions/v1" }).open(epl)
        assertTrue("failed", src.tick() is DetailLoad.Failed)
    }

    @Test fun `a failed tick after a good one keeps showing the good detail`() = runBlocking {
        var down = false
        val inner = http()
        val src = SportsDetail.create(FakeHttp { u -> if (down) HttpResponse(503, null, null) else runBlocking { inner.get(u, deadlineMs = 1) } }, { "https://b/functions/v1" }).open(epl)
        assertTrue("good", src.tick() is DetailLoad.Loaded)
        down = true
        assertTrue("still loaded", src.tick() is DetailLoad.Loaded)
    }
}

class MappingUpdaterTest {
    private val bundledText = SportsFixtures::class.java.classLoader!!.getResourceAsStream("sports/sports-mapping.v1.json")!!.bufferedReader().readText()
    private val p = InMemoryMappingPersistence()
    private val store = SportsMappingStore(BundledSportsMapping.load(), p, SmokeFixture.bundled())

    private class FakeRemote(var answer: RemoteMapping) : MappingRemote {
        val etags = mutableListOf<String?>()
        override suspend fun fetch(etag: String?): RemoteMapping { etags += etag; return answer }
    }

    @Test fun `adopts a valid update and remembers its etag`() = runBlocking {
        val remote = FakeRemote(RemoteMapping.Updated(bundledText.replace("2026-11-07.1", "2026-12-01.1"), "\"abc\""))
        val u = SportsMappingUpdater(store, remote, p)
        assertTrue("adopted", u.refresh() is OfferResult.Adopted)
        assertEquals("version", "2026-12-01.1", store.current.version)
        u.refresh()
        assertEquals("etag sent on 2nd call", listOf<String?>(null, "\"abc\""), remote.etags)
    }
    @Test fun `304 keeps the current mapping`() = runBlocking {
        val u = SportsMappingUpdater(store, FakeRemote(RemoteMapping.NotModified), p)
        assertNull("nothing offered", u.refresh())
        assertEquals("kept", "2026-11-07.1", store.current.version)
    }
    @Test fun `a bad body keeps last good and does not throw`() = runBlocking {
        val u = SportsMappingUpdater(store, FakeRemote(RemoteMapping.Updated("<html>", "\"x\"")), p)
        assertTrue("rejected", u.refresh() is OfferResult.Rejected)
        assertEquals("kept", "2026-11-07.1", store.current.version)
    }
    @Test fun `network failure is silent`() = runBlocking {
        assertNull("failed", SportsMappingUpdater(store, FakeRemote(RemoteMapping.Failed), p).refresh())
    }
    @Test fun `http remote sends If-None-Match and maps statuses`() = runBlocking {
        var status = 200
        val h = FakeHttp { HttpResponse(status, if (status == 200) "{}" else null, "\"e1\"") }
        val r = HttpMappingRemote(h, functionsBase = { "https://b/functions/v1/" })
        assertEquals("200", RemoteMapping.Updated("{}", "\"e1\""), r.fetch("\"old\""))
        assertEquals("header", "\"old\"", h.requests[0].second["If-None-Match"])
        assertEquals("function path constant", "https://b/functions/v1/sports-mapping", h.requests[0].first)
        assertEquals("no backend", RemoteMapping.Failed, HttpMappingRemote(h, functionsBase = { null }).fetch(null))
        status = 304; assertEquals("304", RemoteMapping.NotModified, r.fetch("\"e1\""))
        status = 500; assertEquals("500", RemoteMapping.Failed, r.fetch(null))
    }
}
