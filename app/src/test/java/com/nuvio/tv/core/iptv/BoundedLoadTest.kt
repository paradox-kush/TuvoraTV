package com.nuvio.tv.core.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The TV twin of NuvioMobile's BoundedLoadTest: every load ends, a failure is never empty, progress keeps
 * a healthy import alive, the deadline holds even against work that ignores cancellation, and `iptv_load`
 * never carries a message, host or credential.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoundedLoadTest {
    private val events = mutableListOf<Pair<String, Map<String, Any>>>()
    private val realSink = BoundedLoad.sink

    @Before
    fun setUp() {
        BoundedLoad.resetReportsForTests()
        BoundedLoad.sink = { name, props -> synchronized(events) { events += name to props } }
    }

    @After
    fun tearDown() {
        BoundedLoad.stallOverrideMsForTests = null
        BoundedLoad.sink = realSink
    }

    @Test
    fun `a provider that never answers ends as a timed-out failure at the surface's real deadline`() = runTest {
        val outcome = BoundedLoad.run<List<String>>(LoadSurface.HUB_CATEGORIES) { awaitCancellation() }

        assertTrue("never-answering work must end as Failed, was $outcome", outcome is LoadOutcome.Failed)
        assertTrue("and as a timeout", (outcome as LoadOutcome.Failed).timedOut)
        assertEquals("the page waits its 25 s stall deadline, no longer", 25_000L, currentTime)
    }

    @Test
    fun `every surface has the deadline the rule names`() {
        assertEquals("hub categories", 25_000L, LoadSurface.HUB_CATEGORIES.stallMs)
        assertEquals("hub row", 20_000L, LoadSurface.HUB_ROW.stallMs)
        assertEquals("live resolve", 20_000L, LoadSurface.LIVE_RESOLVE.stallMs)
        assertEquals("settings", 25_000L, LoadSurface.SETTINGS.stallMs)
    }

    @Test
    fun `a failure is never reported as empty`() = runTest {
        val outcome = BoundedLoad.run<List<String>>(LoadSurface.HUB_ROW, isEmpty = { it.isEmpty() }) {
            throw IllegalStateException("HTTP 503")
        }

        assertTrue("a thrown fetch is Failed, never Empty (was $outcome)", outcome is LoadOutcome.Failed)
        assertEquals("an outright failure is not a timeout", false, (outcome as LoadOutcome.Failed).timedOut)
    }

    @Test
    fun `an empty answer is empty and a full one is loaded`() = runTest {
        val empty = BoundedLoad.run(LoadSurface.HUB_ROW, isEmpty = { it: List<Int> -> it.isEmpty() }) { emptyList() }
        val full = BoundedLoad.run(LoadSurface.HUB_ROW, isEmpty = { it: List<Int> -> it.isEmpty() }) { listOf(1) }

        assertEquals("empty answer", LoadStatus.Empty, empty.status)
        assertEquals("full answer", LoadStatus.Loaded, full.status)
    }

    @Test
    fun `progress keeps a slow but healthy import alive past the stall deadline`() = runTest {
        // 6 x 10 s = 60 s in total, more than twice the 25 s stall, but never 25 s without progress.
        val ticks = flow { repeat(6) { delay(10_000); emit(it) } }
        var extended = 0
        val outcome = BoundedLoad.run(LoadSurface.HUB_CATEGORIES, progress = ticks, onProgress = { extended++ }) {
            delay(60_000); "imported"
        }

        assertEquals("a progressing import is never cut off", LoadOutcome.Loaded("imported"), outcome)
        assertTrue("each tick pushes the deadline out (got $extended)", extended >= 5)
    }

    @Test
    fun `an import that stops progressing still ends`() = runTest {
        val ticks = flow { repeat(2) { delay(10_000); emit(it) } }   // then silence
        val outcome = BoundedLoad.run<String>(LoadSurface.HUB_CATEGORIES, progress = ticks) { awaitCancellation() }

        assertTrue("a stalled import ends as a timeout", (outcome as? LoadOutcome.Failed)?.timedOut == true)
        assertTrue("within two windows of the last tick (ended at $currentTime)", currentTime <= 20_000L + 2 * 25_000L)
    }

    @Test
    fun `an import the deadline gave up on keeps running when asked to`() = runTest {
        val finished = CompletableDeferred<Unit>()
        val outcome = BoundedLoad.run(LoadSurface.HUB_CATEGORIES, cancelOnTimeout = false) {
            delay(40_000); finished.complete(Unit); "late"
        }

        assertTrue("the page stopped waiting", outcome is LoadOutcome.Failed)
        finished.await() // completes: the import was not cancelled by the deadline
        assertTrue("the import ran to its end", finished.isCompleted)
    }

    @Test
    fun `a timed-out fetch is cancelled by default`() = runTest {
        var cancelled = false
        BoundedLoad.run(LoadSurface.HUB_ROW) {
            try { awaitCancellation() } finally { cancelled = true }
        }
        testScheduler.advanceUntilIdle()
        assertTrue("nobody reads a timed-out row fetch, so it must stop", cancelled)
    }

    @Test
    fun `the wait ends at the deadline even when the work ignores cancellation`(): Unit = runBlocking(Dispatchers.Default) {
        // Real time on a multi-threaded dispatcher, as in the app: the busy work holds one thread, the
        // deadline another. 150 ms stall; the work spins 1 s without ever checking for cancellation.
        BoundedLoad.stallOverrideMsForTests = 150L
        val started = System.currentTimeMillis()
        val outcome = BoundedLoad.run(LoadSurface.HUB_ROW) {
            val end = System.currentTimeMillis() + 1_000
            while (System.currentTimeMillis() < end) { /* spin */ }
            "too late"
        }
        val waited = System.currentTimeMillis() - started

        assertTrue("non-cancellable work still ends as Failed", outcome is LoadOutcome.Failed)
        assertTrue("released at the deadline, not when the work returns (waited ${waited}ms)", waited < 900)
    }

    @Test
    fun `a loading status past its deadline reads as a timed-out failure`() {
        val loading = BoundedLoad.begin(LoadSurface.HUB_ROW, nowMs = 1_000)

        assertEquals("before the deadline it is still loading", loading, BoundedLoad.effectiveAt(loading, nowMs = 20_999))
        assertEquals("at the deadline the UI ends the wait", LoadStatus.Failed(timedOut = true), BoundedLoad.effectiveAt(loading, nowMs = 21_000))
        assertEquals("a terminal status is never rewritten", LoadStatus.Loaded, BoundedLoad.effectiveAt(LoadStatus.Loaded, nowMs = 99_999))
    }

    @Test
    fun `progress pushes the deadline out`() {
        val loading = BoundedLoad.begin(LoadSurface.HUB_CATEGORIES, nowMs = 1_000)
        val later = BoundedLoad.progressed(loading, LoadSurface.HUB_CATEGORIES, nowMs = 5_000)

        assertEquals("deadline restarts from the progress", 30_000L, (later as LoadStatus.Loading).deadlineAtMs)
        assertEquals("start is kept", 1_000L, later.startedAtMs)
        assertEquals("non-loading is unchanged", LoadStatus.Empty, BoundedLoad.progressed(LoadStatus.Empty, LoadSurface.HUB_CATEGORIES))
    }

    @Test
    fun `page loads report every outcome and rows report only failures without the error message`() = runTest {
        BoundedLoad.run(LoadSurface.HUB_CATEGORIES, report = mapOf("source_type" to "xtream")) { "ok" }
        BoundedLoad.run(LoadSurface.HUB_ROW) { "ok" }
        BoundedLoad.run<String>(LoadSurface.HUB_ROW) { throw IllegalStateException("http://user:pass@host") }
        BoundedLoad.run<String>(LoadSurface.LIVE_RESOLVE) { awaitCancellation() }

        val loads = events.filter { it.first == "iptv_load" }.map { it.second }
        assertEquals(
            "page outcome always, row/resolve only on failure",
            listOf("hub_categories" to "loaded", "hub_row" to "failed", "live_resolve" to "timeout"),
            loads.map { it["surface"] to it["outcome"] },
        )
        assertEquals("closed-vocabulary extras pass through", "xtream", loads.first()["source_type"])
        assertEquals("the exception class only", "IllegalStateException", loads[1]["error_type"])
        assertTrue("a duration is always reported", loads.all { it["duration_ms"] is Long })
        assertTrue("no message, host or credential", loads.none { props -> props.values.any { it.toString().contains("pass") } })
    }

    @Test
    fun `failure reports are capped per surface per process`() = runTest {
        repeat(15) { BoundedLoad.run<String>(LoadSurface.HUB_ROW) { error("x") } }
        assertEquals("at most 10 row failures per process", 10, events.count { it.second["surface"] == "hub_row" })
    }
}
