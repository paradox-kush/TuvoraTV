package com.nuvio.tv.core.sports.detail

import com.nuvio.tv.core.sports.detail.screen.detailOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared per-game feed (multiview): ref-counted, one poller per game, replay 1, stops on last release. */
@OptIn(ExperimentalCoroutinesApi::class)
class GameFeedHubTest {
    private class FakeDetailSource(
        private val state: () -> GameState = { GameState.LIVE },
        private val failing: () -> Boolean = { false },
    ) : GameDetailSource {
        val ticks = mutableListOf<Pair<Boolean, Boolean>>()
        override suspend fun tick(fetchEvent: Boolean, fetchSummary: Boolean): DetailLoad {
            ticks += fetchEvent to fetchSummary
            if (failing()) return DetailLoad.Failed()
            return DetailLoad.Loaded(detailOf(home = Side("Home", score = "${ticks.size}"), state = state(), id = "espn:1"))
        }
    }

    private val ref = GameRef(
        gameId = "espn:1", leagueId = "1", homeName = "Home", awayName = "Away", startsAtMs = 0, sport = "Basketball", seedState = GameState.LIVE,
    )
    private val cfg = GameDetailRefreshPolicy.Config()

    private fun hub(scope: kotlinx.coroutines.CoroutineScope, clock: () -> Long, sources: MutableList<FakeDetailSource>, make: () -> FakeDetailSource) =
        GameFeedHub(scope, { r -> make().also { sources += it } }, { cfg }, clock)

    private fun score(l: DetailLoad) = ((l as DetailLoad.Loaded).detail.header as GameHeader.Teams).home.score

    @Test fun `two consumers on one game share one set of requests`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource() }
        val a = h.acquire(ref); val b = h.acquire(ref)
        val seenA = mutableListOf<DetailLoad>(); val seenB = mutableListOf<DetailLoad>()
        backgroundScope.launch { a.feed.collect { seenA += it } }
        backgroundScope.launch { b.feed.collect { seenB += it } }
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals("one source opened for two consumers", 1, sources.size)
        assertEquals("ticks at 0 15 30 45 60 s, not doubled", 5, sources.single().ticks.size)
        assertEquals("both consumers see the same values", seenA.map(::score), seenB.map(::score))
        assertEquals("one polled game", 1, h.activeGames())
    }

    @Test fun `the last release stops polling and no request follows it`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource() }
        val a = h.acquire(ref); val b = h.acquire(ref)
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        a.release()
        advanceTimeBy(30_000); runCurrent()
        val whileOneHeld = sources.single().ticks.size
        assertTrue("still polling while one consumer remains", whileOneHeld >= 3)
        b.release()
        advanceTimeBy(300_000); runCurrent()
        assertEquals("no request after the last release", whileOneHeld, sources.single().ticks.size)
        assertEquals("nothing polled", 0, h.activeGames())
        b.release() // idempotent
        assertEquals("double release is harmless", 0, h.activeGames())
    }

    @Test fun `a final game stops itself after the first load and the feed holds its value`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource(state = { GameState.FINAL }) }
        val a = h.acquire(ref)
        runCurrent(); advanceTimeBy(600_000); runCurrent()
        assertEquals("one tick, then idle", 1, sources.single().ticks.size)
        val late = h.acquire(ref)
        assertEquals("a late subscriber still gets the held value", "1", score(late.feed.first()))
        assertEquals("still one source", 1, sources.size)
        a.release(); late.release()
    }

    @Test fun `a late subscriber gets the latest snapshot immediately`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource() }
        val a = h.acquire(ref)
        backgroundScope.launch { a.feed.collect { } }
        runCurrent(); advanceTimeBy(45_000); runCurrent()
        val n = sources.single().ticks.size
        val late = h.acquire(ref)
        assertEquals("replay 1: the newest tick, no waiting", "$n", score(late.feed.first()))
    }

    @Test fun `release then reacquire restarts with a full tick`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource() }
        val a = h.acquire(ref)
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        a.release()
        advanceTimeBy(100_000); runCurrent()
        val again = h.acquire(ref)
        runCurrent()
        assertEquals("a new source", 2, sources.size)
        assertEquals("first tick is full (event + summary)", true to true, sources[1].ticks.first())
        again.release()
    }

    @Test fun `failures back off exactly as the poller does`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource(failing = { true }) }
        val a = h.acquire(ref)
        runCurrent(); advanceTimeBy(100_000); runCurrent()
        assertEquals("ticks at 0 30 90 s (2x then 4x), not every 15 s", 3, sources.single().ticks.size)
        a.release()
    }

    @Test fun `a released handle's feed completes`() = runTest {
        val sources = mutableListOf<FakeDetailSource>()
        val h = hub(backgroundScope, { currentTime }, sources) { FakeDetailSource() }
        val a = h.acquire(ref)
        var completed = false
        backgroundScope.launch { a.feed.collect { }; completed = true }
        runCurrent()
        a.release(); runCurrent()
        assertTrue("collector ended after release", completed)
    }
}
