package com.nuvio.tv.core.iptv.match

import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Collections
import java.util.concurrent.CountDownLatch

/** PosterEnricher's politeness contract: once per item ever, newest window wins, live excluded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PosterEnricherTest {

    private val acc = XtreamAccount(id = "acct-1", name = "t", baseUrl = "http://x", username = "u", password = "p")
    private val index = XtreamMatchIndex(RuntimeEnvironment.getApplication())
    private val client = mockk<XtreamClient>()
    private val factory = mockk<IptvClientFactory> { every { clientFor(any()) } returns client }
    private val enricher = PosterEnricher(index, factory)
    private val collectScope = CoroutineScope(Dispatchers.IO)

    private val fetched = Collections.synchronizedList(mutableListOf<Int>())

    /**
     * Ask order, recorded as each request leaves the queue.
     *
     * [fetched] records *completion*, which says nothing about priority: CONCURRENCY workers run
     * fetches in parallel, so two items pulled in the same round finish in whichever order the
     * scheduler picks. Queue-jumping is a statement about what gets taken off the queue next, so
     * that is what the priority test asserts on.
     */
    private val started = Collections.synchronizedList(mutableListOf<Int>())

    @After
    fun tearDown() {
        collectScope.cancel()
    }

    /**
     * [gate], when given, holds every worker inside the fetch until the test opens it — which is how
     * the priority test pins down exactly how much of the first window is already in flight before
     * the second window is enqueued. Without it that depends on wall-clock timing and the test is
     * only asserting how fast the machine is.
     */
    private fun answerArtwork(url: (Int) -> String?, delayMs: Long = 0, gate: CountDownLatch? = null) {
        coEvery { client.vodArtwork(any(), any()) } coAnswers {
            val sid = secondArg<Int>()
            started.add(sid)
            gate?.await()
            if (delayMs > 0) delay(delayMs)
            fetched.add(sid)
            Result.success(url(sid))
        }
    }

    private fun await(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        fail("timed out waiting for $what")
    }

    @Test
    fun `asks once per item ever and writes the answer through`() = runBlocking {
        index.rebuild(acc.id, MatchKind.MOVIE, listOf(IndexedItem(1, "Movie 1", null, null, "mp4")))
        val updates = Collections.synchronizedList(mutableListOf<PosterEnricher.PosterUpdate>())
        collectScope.launch { enricher.updates.collect { updates.add(it) } }
        // SharedFlow with replay 0: give the collector a beat to subscribe before emitting.
        Thread.sleep(100)
        answerArtwork({ "https://img/$it.jpg" })

        enricher.enqueue(acc, MatchKind.MOVIE, listOf(1))
        await("first fetch + update") { updates.size == 1 }
        assertEquals("https://img/1.jpg", index.item(acc.id, MatchKind.MOVIE, 1)?.poster)

        enricher.enqueue(acc, MatchKind.MOVIE, listOf(1))
        Thread.sleep(300)
        assertEquals(listOf(1), fetched.toList()) // attempted-set: no second ask
    }

    @Test
    fun `panel with no artwork is asked once and left alone`() = runBlocking {
        index.rebuild(acc.id, MatchKind.MOVIE, listOf(IndexedItem(5, "Movie 5", null, null, "mp4")))
        answerArtwork({ null })
        enricher.enqueue(acc, MatchKind.MOVIE, listOf(5))
        await("the one ask") { fetched.size == 1 }
        enricher.enqueue(acc, MatchKind.MOVIE, listOf(5))
        Thread.sleep(300)
        assertEquals(1, fetched.size)
        assertEquals(null, index.item(acc.id, MatchKind.MOVIE, 5)?.poster)
    }

    @Test
    fun `the newest window drains ahead of older pending rows`() = runBlocking {
        // Hold the first window's workers mid-fetch, so the second enqueue lands with a known
        // amount in flight: CONCURRENCY items taken, the rest still queued behind them.
        val gate = CountDownLatch(1)
        answerArtwork({ "https://img/$it.jpg" }, gate = gate)
        enricher.enqueue(acc, MatchKind.MOVIE, listOf(1, 2, 3, 4, 5, 6))
        await("both workers busy") { started.size == PosterEnricher.CONCURRENCY }

        enricher.enqueue(acc, MatchKind.MOVIE, listOf(9))
        gate.countDown()
        await("everything drains") { fetched.containsAll(listOf(1, 2, 3, 4, 5, 6, 9)) }

        // The just-served window jumps the queue (visible cards fill first); the older window's
        // tail still completes behind it instead of being clobbered by prefetch enqueues. Items
        // already in flight cannot be reordered, so the guarantee is against the pending tail.
        assertTrue(
            "9 should be asked before the old tail: $started",
            started.indexOf(9) < started.indexOf(4)
        )
    }

    @Test
    fun `live is never enriched`() = runBlocking {
        enricher.enqueue(acc, MatchKind.LIVE, listOf(1, 2, 3))
        Thread.sleep(300)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `prefetch windows append behind visible work`() = runBlocking {
        answerArtwork({ "https://img/$it.jpg" }, delayMs = 200)
        enricher.enqueue(acc, MatchKind.MOVIE, listOf(1, 2, 3, 4))
        enricher.enqueue(acc, MatchKind.MOVIE, listOf(50, 51), prioritize = false)
        enricher.enqueue(acc, MatchKind.MOVIE, listOf(9))
        await("everything drains") { fetched.containsAll(listOf(1, 2, 3, 4, 9, 50, 51)) }
        // The section-load burst: prefetch categories must not starve what's on screen.
        assertTrue("visible 9 before prefetch 50: $fetched", fetched.indexOf(9) < fetched.indexOf(50))
        assertTrue("visible tail 3 before prefetch 50: $fetched", fetched.indexOf(3) < fetched.indexOf(50))
    }

    @Test
    fun `transport failure is retryable, not burned`() = runBlocking {
        index.rebuild(acc.id, MatchKind.MOVIE, listOf(IndexedItem(7, "Movie 7", null, null, "mp4")))
        var failFirst = true
        coEvery { client.vodArtwork(any(), any()) } coAnswers {
            val sid = secondArg<Int>()
            fetched.add(sid)
            if (failFirst) {
                failFirst = false
                Result.failure(RuntimeException("connection reset"))
            } else Result.success("https://img/$sid.jpg")
        }
        enricher.enqueue(acc, MatchKind.MOVIE, listOf(7))
        await("the failing ask") { fetched.size == 1 }
        // The mock records the call before returning failure — give onFailure a beat to
        // un-mark the item (in production the retry rides a LATER window serve anyway).
        Thread.sleep(300)
        // A transport failure must not read as "panel has no art" — the next window retries.
        enricher.enqueue(acc, MatchKind.MOVIE, listOf(7))
        await("retry writes through") {
            runBlocking { index.item(acc.id, MatchKind.MOVIE, 7)?.poster != null }
        }
        assertEquals(listOf(7, 7), fetched.toList())
    }
}
