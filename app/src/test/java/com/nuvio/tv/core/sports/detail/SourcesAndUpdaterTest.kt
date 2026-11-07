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

    @Test fun `live tick asks only the scoreboard`() = runBlocking {
        val h = http()
        source(h).load(eplRef, setOf(RefreshPart.SCOREBOARD), SportsFixtures.mapping)
        assertTrue("no summary call", h.requests.none { it.first.contains("/summary") })
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
    private fun bundle(): String {
        fun w(name: String) = SportsFixtures.raw("tsdb/$name.json")
        return """{"event":${SportsFixtures.raw("tsdb/epl_event.json")},"stats":${w("ev_lookup_event_stats_2494097")},"timeline":${w("ev_lookup_event_timeline_2494097")},"tv":${w("ev_lookup_event_tv_2494097")},"highlights":${w("ev_lookup_event_highlights_2494097")}}"""
    }
    private val ref = nbaRef("tsdb:2494097").copy(sport = "Soccer", leagueId = "4328")

    @Test fun `builds blocks and header from the backend bundle`() = runBlocking {
        val h = FakeHttp { HttpResponse(200, bundle(), null) }
        val l = TheSportsDbSource(h, { "https://backend.example/functions/v1" }, FakeClock()::read).load(ref, setOf(RefreshPart.SCOREBOARD, RefreshPart.SUMMARY), SportsFixtures.mapping)
        assertEquals("hit", FetchOutcome.HIT, l.outcome)
        assertTrue("header", l.header is GameHeader.Teams)
        assertTrue("stats+timeline+broadcast", l.blocks.keys.containsAll(setOf(BlockType.STATS, BlockType.TIMELINE, BlockType.BROADCAST)))
        assertTrue("event id sent", h.requests[0].first.contains("event_id=2494097"))
    }
    @Test fun `no backend configured is a miss not a crash`() = runBlocking {
        assertEquals("miss", FetchOutcome.EMPTY, TheSportsDbSource(FakeHttp { error("unused") }, { null }, FakeClock()::read).load(ref, setOf(RefreshPart.SUMMARY), SportsFixtures.mapping).outcome)
    }
    @Test fun `espn-only game ids are not asked of tsdb`() = runBlocking {
        val h = FakeHttp { error("unused") }
        assertEquals("miss", FetchOutcome.EMPTY, TheSportsDbSource(h, { "https://b" }, FakeClock()::read).load(ref.copy(gameId = "espn:5"), setOf(RefreshPart.SUMMARY), SportsFixtures.mapping).outcome)
        assertTrue("no request", h.requests.isEmpty())
    }
    @Test fun `server error is http_fail`() = runBlocking {
        assertEquals("fail", FetchOutcome.HTTP_FAIL, TheSportsDbSource(FakeHttp { HttpResponse(502, null, null) }, { "https://b" }, FakeClock()::read).load(ref, setOf(RefreshPart.SUMMARY), SportsFixtures.mapping).outcome)
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
        val r = HttpMappingRemote(h)
        assertEquals("200", RemoteMapping.Updated("{}", "\"e1\""), r.fetch("\"old\""))
        assertEquals("header", "\"old\"", h.requests[0].second["If-None-Match"])
        assertEquals("single url constant", SportsMappingRemote.URL, h.requests[0].first)
        status = 304; assertEquals("304", RemoteMapping.NotModified, r.fetch("\"e1\""))
        status = 500; assertEquals("500", RemoteMapping.Failed, r.fetch(null))
    }
}
