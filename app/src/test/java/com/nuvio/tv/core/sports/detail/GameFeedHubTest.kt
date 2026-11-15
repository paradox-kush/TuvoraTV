package com.nuvio.tv.core.sports.detail

import com.nuvio.tv.core.sports.detail.screen.detailOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared per-game feed (multiview): collection-tied, one poller per game, replay 1, pause keeps snapshot + fetch times. */
@OptIn(ExperimentalCoroutinesApi::class)
class GameFeedHubTest {
    private class FakeDetailSource(
        private val state: () -> GameState = { GameState.LIVE },
        private val failing: () -> Boolean = { false },
        private val throwing: () -> Boolean = { false },
    ) : GameDetailSource {
        val ticks = mutableListOf<Pair<Boolean, Boolean>>()
        override suspend fun tick(fetchEvent: Boolean, fetchSummary: Boolean): DetailLoad {
            ticks += fetchEvent to fetchSummary
            if (throwing()) error("source blew up")
            if (failing()) return DetailLoad.Failed()
            return DetailLoad.Loaded(detailOf(home = Side("Home", score = "${ticks.size}"), state = state(), id = "espn:1"))
        }
    }

    private val ref = GameRef(
        gameId = "espn:1", leagueId = "1", homeName = "Home", awayName = "Away", startsAtMs = 0, sport = "Basketball", seedState = GameState.LIVE,
    )
    private val cfg = GameDetailRefreshPolicy.Config()

    private fun hub(scope: kotlinx.coroutines.CoroutineScope, clock: () -> Long, sources: MutableList<FakeDetailSource>, make: () -> FakeDetailSource) =
        GameFeedHub(scope, { _ -> make().also { sources += it } }, { cfg }, clock)

    private fun score(l: DetailLoad) = ((l as DetailLoad.Loaded).detail.header as GameHeader.Teams).home.score

    @Test fun `two collectors on one game share one set of requests`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource() }
        val seenA = mutableListOf<DetailLoad>(); val seenB = mutableListOf<DetailLoad>()
        backgroundScope.launch { h.feed(ref).collect { seenA += it } }
        backgroundScope.launch { h.feed(ref).collect { seenB += it } }
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals("one source opened for two collectors", 1, sources.size)
        assertEquals("ticks at 0 15 30 45 60 s, not doubled", 5, sources.single().ticks.size)
        assertEquals("both collectors see the same values", seenA.map(::score), seenB.map(::score))
        assertEquals("one polled game", 1, h.activeGames())
    }

    @Test fun `the last collector leaving stops polling and no request follows it`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource() }
        val a = backgroundScope.launch { h.feed(ref).collect { } }
        val b = backgroundScope.launch { h.feed(ref).collect { } }
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        a.cancel()
        advanceTimeBy(30_000); runCurrent()
        val whileOneHeld = sources.single().ticks.size
        assertTrue("still polling while one collector remains", whileOneHeld >= 3)
        b.cancel()
        advanceTimeBy(300_000); runCurrent()
        assertEquals("no request after the last collector left", whileOneHeld, sources.single().ticks.size)
        assertEquals("nothing polled", 0, h.activeGames())
    }

    @Test fun `a cancelled or failing collector can never leak the poller`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource() }
        // A collector that throws on its first value: the feed's finally still releases.
        val boom = backgroundScope.launch { runCatching { h.feed(ref).collect { error("consumer failed") } } }
        runCurrent()
        assertTrue(boom.isCompleted)
        assertEquals("released after the consumer threw", 0, h.activeGames())
        val n = sources.single().ticks.size
        advanceTimeBy(120_000); runCurrent()
        assertEquals("no request after the failing consumer", n, sources.single().ticks.size)
        // A collector cancelled before it ever received a value.
        val early = backgroundScope.launch { h.feed(ref).collect { } }
        early.cancel(); runCurrent()
        assertEquals("cancelled early: released", 0, h.activeGames())
    }

    @Test fun `a final game stops itself after the first load and the feed holds its value`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource(state = { GameState.FINAL }) }
        val a = backgroundScope.launch { h.feed(ref).collect { } }
        runCurrent(); advanceTimeBy(600_000); runCurrent()
        assertEquals("one tick, then idle", 1, sources.single().ticks.size)
        assertEquals("a late collector still gets the held value", "1", score(h.feed(ref).first()))
        assertEquals("still one source", 1, sources.size)
        a.cancel()
    }

    @Test fun `a late collector gets the latest snapshot immediately`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource() }
        backgroundScope.launch { h.feed(ref).collect { } }
        runCurrent(); advanceTimeBy(45_000); runCurrent()
        val n = sources.single().ticks.size
        assertEquals("replay 1: the newest tick, no waiting", "$n", score(h.feed(ref).first()))
    }

    @Test fun `resume without refetch - a short pause keeps the snapshot and the fetch times`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource() }
        val a = backgroundScope.launch { h.feed(ref).collect { } }
        runCurrent(); advanceTimeBy(20_000); runCurrent()          // ticks at 0 (full) and 15 (event only)
        assertEquals(2, sources.single().ticks.size)
        a.cancel(); runCurrent()
        assertEquals("paused, snapshot kept", 1, h.pausedGames())
        advanceTimeBy(5_000); runCurrent()                           // t = 25
        val seen = mutableListOf<DetailLoad>()
        val b = backgroundScope.launch { h.feed(ref).collect { seen += it } }
        runCurrent()
        assertEquals("the held snapshot replays at once", "2", score(seen.first()))
        assertEquals("same source", 1, sources.size)
        assertEquals("resume makes NO request before its slot", 2, sources.single().ticks.size)
        advanceTimeBy(5_000); runCurrent()                           // t = 30: the event slot
        assertEquals(3, sources.single().ticks.size)
        assertEquals("event only", true to false, sources.single().ticks.last())
        b.cancel()
    }

    @Test fun `a game paused longer than the keep window starts fresh with a full tick`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource() }
        val a = backgroundScope.launch { h.feed(ref).collect { } }
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        a.cancel(); runCurrent()
        advanceTimeBy(GameFeedHub.PAUSE_KEEP_MS + 1_000); runCurrent()
        val b = backgroundScope.launch { h.feed(ref).collect { } }
        runCurrent()
        assertEquals("a new source", 2, sources.size)
        assertEquals("first tick is full (event + summary)", true to true, sources[1].ticks.first())
        assertEquals("the stale entry was dropped", 0, h.pausedGames())
        b.cancel()
    }

    @Test fun `failures back off exactly as the poller does`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource(failing = { true }) }
        val a = backgroundScope.launch { h.feed(ref).collect { } }
        runCurrent(); advanceTimeBy(100_000); runCurrent()
        assertEquals("ticks at 0 30 90 s (2x then 4x), not every 15 s", 3, sources.single().ticks.size)
        a.cancel()
    }

    @Test fun `a source that throws ends that run with Failed and Retry restarts it once, never a loop`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        var blow = true
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource(throwing = { blow }) }
        val seen = mutableListOf<DetailLoad>()
        val a = backgroundScope.launch { h.feed(ref).collect { seen += it } }
        runCurrent()
        assertTrue("the feed shows Failed", seen.last() is DetailLoad.Failed)
        advanceTimeBy(300_000); runCurrent()
        assertEquals("the run ended: no hot retry loop", 1, sources.single().ticks.size)
        blow = false
        h.retry(ref.gameId); runCurrent()
        assertEquals("Retry is one full tick", 2, sources.single().ticks.size)
        assertEquals(true to true, sources.single().ticks.last())
        assertTrue("and the feed recovers", seen.last() is DetailLoad.Loaded)
        advanceTimeBy(16_000); runCurrent()
        assertEquals("then the normal cadence resumes", 3, sources.single().ticks.size)
        a.cancel()
    }

    @Test fun `retry on a game nobody collects does nothing`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource() }
        h.retry(ref.gameId); runCurrent()
        assertEquals(0, sources.size)
    }
}
