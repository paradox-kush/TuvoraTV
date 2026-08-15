package com.nuvio.tv.core.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * When the guide is allowed to ask a panel for a channel's history.
 *
 * This fetch is per-channel and lazy on purpose: `get_simple_data_table` returns a full week for one
 * channel, and prefetching a screenful of them is how 2 MB becomes 40 MB on a box with a 192 MB
 * heap. The gate and the single-flight are the two things standing between the guide and that.
 */
class CatchUpEpgFetcherTest {

    private val now = 1_710_000_000_000L
    private val playlist = "http://panel.example|bob"

    private class Recorder {
        val refills = AtomicInteger()
        val stamps = HashMap<String, Long?>()
        var gate: CompletableDeferred<Unit>? = null

        fun fetcher(): CatchUpEpgFetcher = CatchUpEpgFetcher(
            fetchedAt = { _, channelId -> stamps[channelId] },
            refill = { _, _, _, _ ->
                gate?.await()
                refills.incrementAndGet()
            },
        )
    }

    @Test
    fun `a channel that was never fetched is fetched`() = runTest {
        val recorder = Recorder()
        recorder.fetcher().ensure(playlist, "101", catchUpDays = 7, nowMs = now)
        assertEquals("fetched once", 1, recorder.refills.get())
    }

    @Test
    fun `a channel fetched inside the ttl is not fetched again`() = runTest {
        val recorder = Recorder()
        recorder.stamps["101"] = now - 60_000L
        recorder.fetcher().ensure(playlist, "101", catchUpDays = 7, nowMs = now)
        assertEquals("gated", 0, recorder.refills.get())
    }

    @Test
    fun `a channel fetched beyond the ttl is fetched again`() = runTest {
        val recorder = Recorder()
        recorder.stamps["101"] = now - CatchUpEpgWindow.FETCH_TTL_MS - 1
        recorder.fetcher().ensure(playlist, "101", catchUpDays = 7, nowMs = now)
        assertEquals("refetched", 1, recorder.refills.get())
    }

    /**
     * Focus lands on a channel, the debounce fires, the viewer steps into the timeline and the
     * strip asks again — all before the first response. One request, not three: these panels
     * rate-limit, and two of the user's three accounts are max_connections=1.
     */
    @Test
    fun `concurrent asks for one channel share a single fetch`() = runTest {
        val recorder = Recorder()
        recorder.gate = CompletableDeferred()
        val fetcher = recorder.fetcher()

        val a = async { fetcher.ensure(playlist, "101", 7, now) }
        val b = async { fetcher.ensure(playlist, "101", 7, now) }
        val c = async { fetcher.ensure(playlist, "101", 7, now) }
        // Let all three reach the fetcher before any of them can finish — otherwise the first
        // completes outright and the test proves sequencing rather than single-flighting.
        testScheduler.runCurrent()
        recorder.gate!!.complete(Unit)
        a.await(); b.await(); c.await()

        assertEquals("one fetch served three asks", 1, recorder.refills.get())
    }

    /** Two different channels are two different fetches — the single-flight is per channel. */
    @Test
    fun `different channels fetch independently`() = runTest {
        val recorder = Recorder()
        val fetcher = recorder.fetcher()
        fetcher.ensure(playlist, "101", 7, now)
        fetcher.ensure(playlist, "102", 7, now)
        assertEquals("one each", 2, recorder.refills.get())
    }

    /**
     * The in-flight entry has to be released when the fetch ends, or a channel is fetched once and
     * then never again for the life of the process — the failure mode that looks like a guide
     * frozen at whatever the panel said an hour ago.
     */
    @Test
    fun `a finished fetch releases its slot`() = runTest {
        val recorder = Recorder()
        val fetcher = recorder.fetcher()
        fetcher.ensure(playlist, "101", 7, now)
        // The stamp would normally be written by the refill; simulate the panel having no guide.
        fetcher.ensure(playlist, "101", 7, now)
        assertEquals("asked again once the first finished", 2, recorder.refills.get())
    }

    /**
     * A failing fetch must not poison the channel: the next open tries again rather than treating
     * one transient panel error as "this channel has no history".
     */
    @Test
    fun `a failed fetch does not pin the channel`() = runTest {
        val attempts = AtomicInteger()
        val fetcher = CatchUpEpgFetcher(
            fetchedAt = { _, _ -> null },
            refill = { _, _, _, _ ->
                attempts.incrementAndGet()
                error("panel said 500")
            },
        )
        runCatching { fetcher.ensure(playlist, "101", 7, now) }
        runCatching { fetcher.ensure(playlist, "101", 7, now) }
        assertEquals("tried both times", 2, attempts.get())
    }
}
