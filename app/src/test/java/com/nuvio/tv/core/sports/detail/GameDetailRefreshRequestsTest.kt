package com.nuvio.tv.core.sports.detail

import com.nuvio.tv.core.sports.detail.screen.GameDetailPoller
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * Spec 02 A1: the LIVE refresh is one single-event call per 15 s tick and ONE summary per minute.
 * Everything is driven through the real poller + source + resolver with a fake clock and fake HTTP.
 */
class GameDetailRefreshRequestsTest {
    private val eventTemplate = "https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard/{id}"
    private val withEvent = SportsFixtures.mapping.copy(espnEventUrl = eventTemplate)
    private val withoutEvent = SportsFixtures.mapping.copy(espnEventUrl = null)

    private val start = java.time.Instant.parse("2026-11-07T00:00:00Z").toEpochMilli()
    private val rawEvent = SportsFixtures.raw("espn/nba_event.json")
    private val rawSummary = SportsFixtures.raw("espn/nba_summary_post.json")

    private fun live(raw: String) = raw.replace("\"state\":\"post\"", "\"state\":\"in\"")
    private fun score(raw: String, home: String, away: String) =
        raw.replaceFirst("\"score\":\"139\"", "\"score\":\"$home\"").replaceFirst("\"score\":\"132\"", "\"score\":\"$away\"")

    private val tsdbRef = GameRef(
        gameId = "tsdb:100", sport = "Basketball", leagueId = "4387", leagueName = "NBA",
        homeName = "Atlanta Hawks", awayName = "Washington Wizards", startsAtMs = start, seedState = GameState.LIVE,
    )
    private val espnRef = tsdbRef.copy(gameId = "espn:401909287")

    private class Net(val clock: FakeClock, private val event: () -> String, private val summary: () -> String, private val failEvent: () -> Boolean = { false }) {
        val log = mutableListOf<String>()
        val http = FakeHttp { url ->
            when {
                url.contains("/scoreboard?dates=") -> { log += "board"; HttpResponse(200, """{"events":[${event()}]}""", null) }
                url.contains("/scoreboard/") -> { log += "event"; if (failEvent()) HttpResponse(500, null, null) else HttpResponse(200, event(), null) }
                url.contains("/summary") -> { log += "summary"; HttpResponse(200, summary(), null) }
                else -> HttpResponse(404, null, null)
            }
        }
    }

    private fun source(net: Net, mapping: SportsMapping, ref: GameRef, primaryTsdb: Boolean): GameDetailSource {
        val sources = buildList<BlockSource> {
            add(EspnSource(net.http, net.clock::read))
            // TheSportsDB stand-in: a miss, so the ESPN header decides the score only on an espn: game.
            if (primaryTsdb) add(FakeSource(SourceId.TSDB) { _, _ -> SourceLoad.miss() })
        }.associateBy { it.id }
        return ResolvingGameDetailSource(GameDetailResolver({ mapping }, sources, clock = net.clock::read), ref)
    }

    /** Runs the real poller for [seconds] of fake time (ticks at 0, 15, 30 ...). Returns the loads it published. */
    private fun pollFor(seconds: Int, net: Net, src: GameDetailSource, state: GameState = GameState.LIVE, mapping: SportsMapping = withEvent): List<DetailLoad> {
        val t0 = net.clock.now
        val loads = mutableListOf<DetailLoad>()
        val poller = GameDetailPoller(
            source = src, startsAtMs = start, initialState = state,
            config = GameDetailRefreshPolicy.Config.from(mapping.refresh), clock = net.clock::read,
            delayFn = { d -> net.clock.now += d; if (net.clock.now - t0 > seconds * 1000L) throw CancellationException("window over") },
        )
        runBlocking { try { poller.run { loads += it } } catch (_: CancellationException) { } }
        return loads
    }

    @Test fun `sixty seconds of LIVE ticks cost one whole-day scoreboard two summaries and four single-event calls`() {
        val net = Net(FakeClock(start + 3_600_000), { live(rawEvent) }, { rawSummary })
        pollFor(60, net, source(net, withEvent, tsdbRef, primaryTsdb = true))
        assertEquals("whole-day scoreboard once (event resolve)", 1, net.log.count { it == "board" })
        assertEquals("summaries at 0 s and 60 s", 2, net.log.count { it == "summary" })
        assertEquals("single-event calls at 15 30 45 60", 4, net.log.count { it == "event" })
    }

    @Test fun `a FINAL game costs its first load and nothing afterwards`() {
        val net = Net(FakeClock(start + 3_600_000), { rawEvent }, { rawSummary })
        val src = source(net, withEvent, tsdbRef, primaryTsdb = true)
        pollFor(120, net, src, state = GameState.FINAL)
        assertEquals("one board + one summary, no later ticks", listOf("board", "summary"), net.log)
    }

    @Test fun `a live tick never fetches the summary when the mapping has the event template`() {
        val net = Net(FakeClock(start + 3_600_000), { live(rawEvent) }, { rawSummary })
        val src = source(net, withEvent, espnRef, primaryTsdb = false)
        runBlocking { src.tick(fetchEvent = true, fetchSummary = true) }
        net.log.clear()
        runBlocking { src.tick(fetchEvent = true, fetchSummary = false) }
        assertEquals("only the single event", listOf("event"), net.log)
    }

    @Test fun `a mapping without the event template keeps today's request pattern`() {
        val net = Net(FakeClock(start + 3_600_000), { live(rawEvent) }, { rawSummary })
        pollFor(60, net, source(net, withoutEvent, tsdbRef, primaryTsdb = true), mapping = withoutEvent)
        assertEquals("one board", 1, net.log.count { it == "board" })
        assertEquals("no single-event call", 0, net.log.count { it == "event" })
        // today: the summary rides every live tick (0 15 30 45 60)
        assertEquals("summary on every tick", 5, net.log.count { it == "summary" })
    }

    @Test fun `the event tick's newer score wins over an older summary`() {
        val net = Net(FakeClock(start + 3_600_000), { live(score(rawEvent, "141", "133")) }, { rawSummary })
        val src = source(net, withEvent, espnRef, primaryTsdb = false)
        val load = runBlocking { src.tick(fetchEvent = true, fetchSummary = true) } as DetailLoad.Loaded
        val h = load.detail.header as GameHeader.Teams
        assertEquals("home score from the event, not the summary's 139", "141", h.home.score)
        assertEquals("away score from the event", "133", h.away.score)
    }

    @Test fun `a later event-only tick moves the score while the summary blocks stay`() {
        var ev = live(rawEvent)
        val net = Net(FakeClock(start + 3_600_000), { ev }, { rawSummary })
        val src = source(net, withEvent, espnRef, primaryTsdb = false)
        val first = runBlocking { src.tick(true, true) } as DetailLoad.Loaded
        ev = live(score(rawEvent, "150", "140"))
        val second = runBlocking { src.tick(true, false) } as DetailLoad.Loaded
        assertEquals("score moved", "150", (second.detail.header as GameHeader.Teams).home.score)
        assertEquals("stats from the earlier summary are kept", first.detail.block(BlockType.STATS), second.detail.block(BlockType.STATS))
        assertTrue("stats exist at all", second.detail.block(BlockType.STATS) != null)
    }

    @Test fun `a failed event tick keeps the last good data and is counted as a failure`() {
        var fail = false
        val net = Net(FakeClock(start + 3_600_000), { live(rawEvent) }, { rawSummary }, failEvent = { fail })
        val src = source(net, withEvent, espnRef, primaryTsdb = false)
        val first = runBlocking { src.tick(true, true) } as DetailLoad.Loaded
        fail = true
        val second = runBlocking { src.tick(true, false) }
        // the screen is never failed: either the kept detail or a Failed the poller counts without clearing the UI
        val kept = (second as? DetailLoad.Loaded)?.detail ?: first.detail
        assertEquals("score still on screen", (first.detail.header as GameHeader.Teams).home.score, (kept.header as GameHeader.Teams).home.score)
        assertTrue("outcome is a failure signal for backoff", second is DetailLoad.Failed)
    }

    @Test fun `UPCOMING soccer in the lead window refreshes the summary on the slow cadence only when asked`() {
        val now = 1_000_000L
        val soon = now + 10 * 60_000
        val cfg = GameDetailRefreshPolicy.Config()
        val plain = GameDetailRefreshPolicy.plan(GameState.UPCOMING, soon, now, null, null, config = cfg)
        assertEquals("non-lineup sport: no summary", false, plain.fetchSummary)
        val soccer = GameDetailRefreshPolicy.plan(GameState.UPCOMING, soon, now, now - 30_000, now - 61_000, config = cfg, wantsUpcomingSummary = true)
        assertEquals("lineup sport: summary due after 60 s", true, soccer.fetchSummary)
        val notDue = GameDetailRefreshPolicy.plan(GameState.UPCOMING, soon, now, now - 30_000, now - 20_000, config = cfg, wantsUpcomingSummary = true)
        assertEquals("not due yet", false, notDue.fetchSummary)
        assertEquals("next check is the earlier of the two", 30_000L, notDue.nextCheckInMs)
    }
}
