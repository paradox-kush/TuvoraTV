package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.Availability
import com.nuvio.tv.core.sports.detail.DetailLoad
import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.GameDetail
import com.nuvio.tv.core.sports.detail.GameDetailRefreshPolicy
import com.nuvio.tv.core.sports.detail.GameDetailSource
import com.nuvio.tv.core.sports.detail.GameRef
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.SourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDetailPollerTest {
    private var now = 1_000_000L
    private val ref = GameRef("tsdb:1", "4387", "A", "B", 1_000_000L)
    private fun detail(state: GameState) = GameDetail.skeleton(ref).copy(
        state = state, blocks = listOf(GameBlock.WinProbability(SourceId.ESPN, 50.0)), availability = Availability.FULL,
    )

    private class Script(val results: MutableList<DetailLoad>, val calls: MutableList<Pair<Boolean, Boolean>> = mutableListOf()) : GameDetailSource {
        override suspend fun tick(fetchEvent: Boolean, fetchSummary: Boolean): DetailLoad {
            calls += fetchEvent to fetchSummary
            return if (results.size > 1) results.removeAt(0) else results[0]
        }
    }

    private fun poller(src: Script, stopAfterDelays: Int, delays: MutableList<Long> = mutableListOf()) = GameDetailPoller(
        source = src, startsAtMs = ref.startsAtMs, initialState = GameState.UPCOMING,
        config = GameDetailRefreshPolicy.Config(), clock = { now },
        delayFn = { ms -> delays += ms; now += ms; if (delays.size >= stopAfterDelays) throw CancellationException("screen paused") },
    )

    @Test fun `a final game loads once then makes zero further requests and stops`() = runBlocking {
        val src = Script(mutableListOf(DetailLoad.Loaded(detail(GameState.FINAL))))
        val loads = mutableListOf<DetailLoad>()
        poller(src, 5).run { loads += it }
        assertEquals("one tick only", listOf(true to true), src.calls)
        assertEquals("one load published", 1, loads.size)
    }

    @Test fun `a live game ticks the event every 15s and the summary every 60s`() = runBlocking {
        val src = Script(mutableListOf(DetailLoad.Loaded(detail(GameState.LIVE))))
        runCatching { poller(src, 6).run { } }
        assertEquals("first tick both", true to true, src.calls[0])
        assertEquals("then events only", true to false, src.calls[1])
        assertEquals("events only", true to false, src.calls[2])
        assertEquals("events only", true to false, src.calls[3])
        assertEquals("minute: both", true to true, src.calls[4])
    }

    @Test fun `failures back off instead of hammering`() = runBlocking {
        val src = Script(mutableListOf(DetailLoad.Loaded(detail(GameState.LIVE)), DetailLoad.Failed(), DetailLoad.Failed(), DetailLoad.Failed()))
        val delays = mutableListOf<Long>()
        runCatching { poller(src, 4, delays).run { } }
        assertTrue("gaps grow: $delays", delays[1] > delays[0] || delays[2] > delays[1])
    }

    @Test fun `cancelling the loop stops it so a paused screen does not poll`() = runBlocking {
        val src = Script(mutableListOf(DetailLoad.Loaded(detail(GameState.LIVE))))
        val before = src.calls.size
        val r = runCatching { poller(src, 1).run { } }
        assertTrue("cancelled", r.exceptionOrNull() is CancellationException)
        assertTrue("no unbounded ticking", src.calls.size - before <= 2)
    }

    @Test fun `re-entering after a pause waits for its slot instead of refetching at once`() = runBlocking {
        val src = Script(mutableListOf(DetailLoad.Loaded(detail(GameState.LIVE))))
        val p = GameDetailPoller(src, ref.startsAtMs, GameState.UPCOMING, GameDetailRefreshPolicy.Config(), { now }, { throw CancellationException("paused") })
        runCatching { p.run { } }
        val n = src.calls.size
        runCatching { p.run { } }
        assertEquals("no extra tick on resume", n, src.calls.size)
    }

    @Test fun `retry forces a full tick`() = runBlocking {
        val src = Script(mutableListOf(DetailLoad.Failed(), DetailLoad.Loaded(detail(GameState.FINAL))))
        val p = poller(src, 1)
        val loads = mutableListOf<DetailLoad>()
        runCatching { p.run { loads += it } }
        p.retry { loads += it }
        assertEquals("two ticks both full", listOf(true to true, true to true), src.calls)
        assertTrue("recovered", loads.last() is DetailLoad.Loaded)
    }
}
