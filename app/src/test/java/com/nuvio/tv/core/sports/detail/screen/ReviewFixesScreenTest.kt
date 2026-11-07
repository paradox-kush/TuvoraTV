package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.DetailLoad
import com.nuvio.tv.core.sports.detail.GameDetailRefreshPolicy
import com.nuvio.tv.core.sports.detail.GameDetailSource
import com.nuvio.tv.core.sports.detail.GameState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ReviewFixesScreenTest {
    private class Hanging : GameDetailSource {
        val calls = mutableListOf<Pair<Boolean, Boolean>>()
        var hang = true
        override suspend fun tick(fetchEvent: Boolean, fetchSummary: Boolean): DetailLoad {
            calls += fetchEvent to fetchSummary
            if (hang) awaitCancellation()
            return DetailLoad.Failed()
        }
    }

    private fun poller(src: GameDetailSource, delayFn: suspend (Long) -> Unit = { throw kotlinx.coroutines.CancellationException("stop") }) =
        GameDetailPoller(src, 0L, GameState.LIVE, GameDetailRefreshPolicy.Config(), { 1_000_000L }, delayFn)

    @Test fun `a pause during the first load leaves the poller unloaded so the next run starts the full tick again`() = runBlocking {
        val src = Hanging()
        val p = poller(src)
        val job = launch { p.run { } }
        while (src.calls.isEmpty()) yield()
        job.cancelAndJoin()
        src.hang = false
        runCatching { p.run { } }
        assertEquals("full tick both times", listOf(true to true, true to true), src.calls.take(2))
    }

    @Test fun `ticks never overlap when retry races the polling loop`() = runBlocking {
        val inFlight = AtomicInteger(); var max = 0
        val src = object : GameDetailSource {
            override suspend fun tick(fetchEvent: Boolean, fetchSummary: Boolean): DetailLoad {
                max = maxOf(max, inFlight.incrementAndGet()); delay(30); inFlight.decrementAndGet()
                return DetailLoad.Failed()
            }
        }
        val p = poller(src)
        listOf(async { p.retry { } }, async { p.retry { } }, async { runCatching { p.run { } } }).awaitAll()
        assertEquals("serialised", 1, max)
    }

    @Test fun `primary takes focus when it appears and the viewer has not moved`() {
        assertEquals("appeared late", FocusKey.PRIMARY, GameDetailFocus.target(FocusKey.CHANNELS, userMoved = false, available = setOf(FocusKey.PRIMARY, FocusKey.CHANNELS)))
    }
    @Test fun `once the viewer moved their choice is kept`() {
        assertEquals("kept", FocusKey.CHANNELS, GameDetailFocus.target(FocusKey.CHANNELS, userMoved = true, available = setOf(FocusKey.PRIMARY, FocusKey.CHANNELS)))
    }
    @Test fun `target falls back like repair when primary is absent`() {
        assertEquals("channels", FocusKey.CHANNELS, GameDetailFocus.target(null, userMoved = false, available = setOf(FocusKey.CHANNELS)))
        assertEquals("none", null, GameDetailFocus.target(FocusKey.PRIMARY, userMoved = false, available = emptySet()))
    }
}

class FocusRestoreAndBadgeTest {
    @Test fun `back restores focus to the card that opened the game exactly once`() {
        GameFocusRestore.arm("g1", 1_000)
        assertEquals("other card", false, GameFocusRestore.take("g2", 1_100))
        assertEquals("opened card", true, GameFocusRestore.take("g1", 1_200))
        assertEquals("only once", false, GameFocusRestore.take("g1", 1_300))
    }
    @Test fun `a stale restore never steals focus`() {
        GameFocusRestore.arm("g1", 0)
        assertEquals("expired", false, GameFocusRestore.take("g1", GameFocusRestore.TTL_MS + 1))
    }
    @Test fun `null ids never match`() { GameFocusRestore.arm("g1", 0); assertEquals(false, GameFocusRestore.take(null, 1)) }

    @Test fun `period sports drop the bare minute that contradicts the detail clock`() {
        assertEquals(null, LiveBadgeProgress.of("Ice Hockey", "9"))
        assertEquals(null, LiveBadgeProgress.of("Basketball", " 12 "))
        assertEquals("Q3 5:00", LiveBadgeProgress.of("Basketball", "Q3 5:00"))
    }
    @Test fun `running time sports keep their minute`() {
        assertEquals("78", LiveBadgeProgress.of("Soccer", "78"))
        assertEquals(null, LiveBadgeProgress.of("Soccer", null))
        assertEquals(null, LiveBadgeProgress.of("Soccer", " "))
    }
}
