package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.CatchUpDialectWalk.Dialect
import com.nuvio.tv.core.iptv.CatchUpDialectWalk.FailureKind
import com.nuvio.tv.core.iptv.CatchUpDialectWalk.Step
import com.nuvio.tv.core.iptv.CatchUpDialectWalk.StoredWinner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchUpDialectWalkTest {

    private val start = 1_710_000_000_000L   // 2024-03-09 16:00 UTC
    private val end = start + 60 * 60_000L

    private class FakeMemory : CatchUpDialectWalk.WinnerMemory {
        val stored = mutableMapOf<String, StoredWinner>()
        var rememberCalls = 0
        override fun recall(accountId: String): StoredWinner? = stored[accountId]
        override fun remember(accountId: String, winner: StoredWinner) {
            rememberCalls++
            stored[accountId] = winner
        }
    }

    private fun request(
        accountId: String = "acct",
        streamId: Int = 777,
        formats: List<String>? = null,
        preferM3u8: Boolean = false,
        username: String = "user",
        password: String = "pass",
    ) = CatchUpDialectWalk.Request(
        accountId = accountId,
        baseUrl = "https://example.com",
        username = username,
        password = password,
        streamId = streamId,
        startMs = start,
        endMs = end,
        allowedOutputFormats = formats,
        preferM3u8 = preferM3u8,
    )

    private fun next(step: Step): CatchUpDialectWalk.Attempt {
        assertTrue("expected Next but was $step", step is Step.Next)
        return (step as Step.Next).attempt
    }

    /** Drives a fresh walk to its end with transport failures, returning the dialects offered. */
    private fun walkAll(walk: CatchUpDialectWalk, req: CatchUpDialectWalk.Request): List<Dialect> {
        val seen = mutableListOf<Dialect>()
        var step = walk.begin(req)
        while (step is Step.Next) {
            seen += step.attempt.dialect
            step = walk.onFailure(step.attempt.token, FailureKind.TRANSPORT)
        }
        assertEquals(Step.Unavailable, step)
        return seen
    }

    // --- the ladder itself -----------------------------------------------------------------

    /** The first attempt must be the byte-frozen shipped form, or working panels regress. */
    @Test
    fun `the first attempt is the shipped dialect`() {
        val attempt = next(CatchUpDialectWalk(FakeMemory()).begin(request()))
        assertEquals(Dialect.PATH_TS, attempt.dialect)
        assertEquals("https://example.com/timeshift/user/pass/60/2024-03-09:16-00/777.ts", attempt.url)
    }

    /** Default order: the shipped five-URL TS walk first, then the m3u8-containered extras. */
    @Test
    fun `the default walk is ts-first across every dialect`() {
        assertEquals(
            listOf(
                Dialect.PATH_TS, Dialect.PATH_SWAPPED_TS, Dialect.PHP_STREAMING_EXT_TS,
                Dialect.PHP_STREAMING, Dialect.PHP_ROOT,
                Dialect.PATH_M3U8, Dialect.PATH_SWAPPED_M3U8, Dialect.PHP_STREAMING_EXT_M3U8,
            ),
            walkAll(CatchUpDialectWalk(FakeMemory()), request()),
        )
    }

    @Test
    fun `the m3u8 preference reorders candidates m3u8-first with ts retained`() {
        val order = walkAll(CatchUpDialectWalk(FakeMemory()), request(preferM3u8 = true))
        assertEquals(
            listOf(
                Dialect.PATH_M3U8, Dialect.PATH_SWAPPED_M3U8, Dialect.PHP_STREAMING_EXT_M3U8,
                Dialect.PHP_STREAMING, Dialect.PHP_ROOT,
                Dialect.PATH_TS, Dialect.PATH_SWAPPED_TS, Dialect.PHP_STREAMING_EXT_TS,
            ),
            order,
        )
        // A failing m3u8 still walks back to TS: every TS dialect is retained as fallback.
        assertTrue(order.containsAll(listOf(Dialect.PATH_TS, Dialect.PATH_SWAPPED_TS, Dialect.PHP_STREAMING_EXT_TS)))
    }

    @Test
    fun `an m3u8-preferred attempt asks for m3u8`() {
        val attempt = next(CatchUpDialectWalk(FakeMemory()).begin(request(preferM3u8 = true)))
        assertEquals("https://example.com/timeshift/user/pass/60/2024-03-09:16-00/777.m3u8", attempt.url)
    }

    // --- pruning by allowed_output_formats -------------------------------------------------

    @Test
    fun `allowed formats prune the other container`() {
        // Panel says ts only: no m3u8-containered dialects; panel-default php forms survive.
        assertEquals(
            listOf(
                Dialect.PATH_TS, Dialect.PATH_SWAPPED_TS, Dialect.PHP_STREAMING_EXT_TS,
                Dialect.PHP_STREAMING, Dialect.PHP_ROOT,
            ),
            walkAll(CatchUpDialectWalk(FakeMemory()), request(formats = listOf("ts"))),
        )
        // Panel says m3u8 (plus junk we ignore): no ts-containered dialects.
        assertEquals(
            listOf(
                Dialect.PHP_STREAMING, Dialect.PHP_ROOT,
                Dialect.PATH_M3U8, Dialect.PATH_SWAPPED_M3U8, Dialect.PHP_STREAMING_EXT_M3U8,
            ),
            walkAll(CatchUpDialectWalk(FakeMemory()), request(formats = listOf("m3u8", "rtmp"))),
        )
    }

    /** Normalization: trim + lowercase before pruning — panels quote " TS " and "M3U8". */
    @Test
    fun `allowed formats normalize before pruning`() {
        assertEquals(
            listOf(
                Dialect.PATH_TS, Dialect.PATH_SWAPPED_TS, Dialect.PHP_STREAMING_EXT_TS,
                Dialect.PHP_STREAMING, Dialect.PHP_ROOT,
            ),
            walkAll(CatchUpDialectWalk(FakeMemory()), request(formats = listOf(" TS "))),
        )
    }

    /** Unknown or nonsensical lists prune nothing — junk data must not gut the ladder. */
    @Test
    fun `junk allowed formats do not prune`() {
        val full = 8
        assertEquals(full, walkAll(CatchUpDialectWalk(FakeMemory()), request(formats = null)).size)
        assertEquals(full, walkAll(CatchUpDialectWalk(FakeMemory()), request(formats = emptyList())).size)
        assertEquals(full, walkAll(CatchUpDialectWalk(FakeMemory()), request(formats = listOf("", "  "))).size)
        // A list naming neither container is nonsense for catch-up purposes: ignore it.
        assertEquals(full, walkAll(CatchUpDialectWalk(FakeMemory()), request(formats = listOf("rtmp"))).size)
    }

    // --- failure kinds ---------------------------------------------------------------------

    @Test
    fun `the walk advances on transport failures only`() {
        val memory = FakeMemory()
        val walk = CatchUpDialectWalk(memory)
        val first = next(walk.begin(request()))
        // TRANSPORT advances to the next dialect...
        val second = next(walk.onFailure(first.token, FailureKind.TRANSPORT))
        assertEquals(Dialect.PATH_SWAPPED_TS, second.dialect)
        // ...DECODE does not: the URL reached a stream, so the dialect is not the problem.
        assertEquals(Step.Unavailable, walk.onFailure(second.token, FailureKind.DECODE))
        // The walk is over — a fresh begin starts from the top, nothing was pinned.
        assertEquals(Dialect.PATH_TS, next(walk.begin(request())).dialect)
        assertEquals(0, memory.rememberCalls)
    }

    @Test
    fun `the walk terminates after every dialect failed`() {
        val walk = CatchUpDialectWalk(FakeMemory())
        var step = walk.begin(request())
        var attempts = 0
        while (step is Step.Next) {
            attempts++
            assertTrue("the ladder must terminate, not loop", attempts <= 8)
            step = walk.onFailure(step.attempt.token, FailureKind.TRANSPORT)
        }
        assertEquals(Step.Unavailable, step)
        assertEquals(8, attempts)
    }

    // --- winner memory ---------------------------------------------------------------------

    @Test
    fun `a proven winner is remembered per account`() {
        val memory = FakeMemory()
        val walk = CatchUpDialectWalk(memory)
        // Two shapes fail, the third plays.
        val a = next(walk.begin(request()))
        val b = next(walk.onFailure(a.token, FailureKind.TRANSPORT))
        val c = next(walk.onFailure(b.token, FailureKind.TRANSPORT))
        assertEquals(Step.Done, walk.onSuccess(c.token))
        assertEquals(
            StoredWinner(CatchUpDialectWalk.formatsSignature(null), Dialect.PHP_STREAMING_EXT_TS),
            memory.stored["acct"],
        )
        // The next walk for this account leads with the proven winner; the rest still follows.
        val order = walkAll(walk, request(streamId = 555))
        assertEquals(Dialect.PHP_STREAMING_EXT_TS, order.first())
        assertEquals(8, order.size)
        // ...and another account is untouched by it.
        assertEquals(Dialect.PATH_TS, next(walk.begin(request(accountId = "other"))).dialect)
    }

    @Test
    fun `the winner is forgotten when allowed formats change`() {
        val memory = FakeMemory()
        // Proven back when the panel advertised both containers...
        memory.stored["acct"] = StoredWinner(
            CatchUpDialectWalk.formatsSignature(listOf("m3u8", "ts")),
            Dialect.PHP_STREAMING,
        )
        // ...but the panel now says ts only: the proof is void, the walk starts from the top.
        val walk = CatchUpDialectWalk(memory)
        assertEquals(Dialect.PATH_TS, next(walk.begin(request(formats = listOf("ts")))).dialect)
    }

    /** The signature is order-blind and normalized — a reshuffled same list must not void a proof. */
    @Test
    fun `the formats signature ignores order and case`() {
        assertEquals(
            CatchUpDialectWalk.formatsSignature(listOf("ts", "m3u8", "rtmp")),
            CatchUpDialectWalk.formatsSignature(listOf("RTMP ", "m3u8", " ts")),
        )
        assertEquals("unknown", CatchUpDialectWalk.formatsSignature(null))
        assertEquals("unknown", CatchUpDialectWalk.formatsSignature(emptyList()))
        assertNotEquals(
            CatchUpDialectWalk.formatsSignature(listOf("ts")),
            CatchUpDialectWalk.formatsSignature(listOf("ts", "m3u8")),
        )
    }

    /** A winner proven under the SAME signature leads even when the list is reshuffled. */
    @Test
    fun `a reshuffled formats list keeps the winner`() {
        val memory = FakeMemory()
        memory.stored["acct"] = StoredWinner(
            CatchUpDialectWalk.formatsSignature(listOf("ts", "m3u8")),
            Dialect.PHP_ROOT,
        )
        val walk = CatchUpDialectWalk(memory)
        assertEquals(
            Dialect.PHP_ROOT,
            next(walk.begin(request(formats = listOf("M3U8", " ts")))).dialect,
        )
    }

    @Test
    fun `all-failed pins no winner`() {
        val memory = FakeMemory()
        val walk = CatchUpDialectWalk(memory)
        walkAll(walk, request())
        // iptvnator persists its fallback here — a panel down for a minute then pins the wrong
        // variant. Failures stay session-scoped: nothing is remembered.
        assertEquals(0, memory.rememberCalls)
        assertNull(memory.stored["acct"])
    }

    // --- stale guards ----------------------------------------------------------------------

    @Test
    fun `a stale result is discarded when the session changed`() {
        val walk = CatchUpDialectWalk(FakeMemory())
        val first = next(walk.begin(request(streamId = 1)))
        // The viewer zaps: a different programme on the same account supersedes the walk.
        val superseding = next(walk.begin(request(streamId = 2)))
        // The old attempt resolves late — its result must not advance (or end) the new walk.
        assertEquals(Step.Stale, walk.onFailure(first.token, FailureKind.TRANSPORT))
        assertEquals(superseding, next(walk.begin(request(streamId = 2))))
    }

    @Test
    fun `a late success for a superseded walk pins nothing`() {
        val memory = FakeMemory()
        val walk = CatchUpDialectWalk(memory)
        val first = next(walk.begin(request(streamId = 1)))
        walk.begin(request(streamId = 2))
        // The zapped-away URL starts playing somewhere late — it must not be recorded as proof.
        assertEquals(Step.Stale, walk.onSuccess(first.token))
        assertEquals(0, memory.rememberCalls)
    }

    @Test
    fun `a duplicate report for a dead attempt does not double-advance`() {
        val walk = CatchUpDialectWalk(FakeMemory())
        val first = next(walk.begin(request()))
        val second = next(walk.onFailure(first.token, FailureKind.TRANSPORT))
        // The same dead attempt reports again (two error callbacks for one player error).
        assertEquals(Step.Stale, walk.onFailure(first.token, FailureKind.TRANSPORT))
        // The walk still sits on the second attempt.
        assertEquals(second, next(walk.begin(request())))
    }

    // --- single-flight ---------------------------------------------------------------------

    @Test
    fun `concurrent walks for one account share one attempt`() {
        val walk = CatchUpDialectWalk(FakeMemory())
        val a = next(walk.begin(request()))
        val b = next(walk.begin(request()))
        // The identical ask joins the walk in flight instead of stampeding the panel.
        assertEquals(a, b)
        // One failure advances the shared walk exactly once; a re-join lands on the new attempt.
        val advanced = next(walk.onFailure(a.token, FailureKind.TRANSPORT))
        assertEquals(advanced, next(walk.begin(request())))
        // Different accounts never share a walk.
        val other = next(walk.begin(request(accountId = "other")))
        assertNotEquals(a.token, other.token)
    }

    // --- degenerate input ------------------------------------------------------------------

    @Test
    fun `blank credentials yield no walk`() {
        assertEquals(Step.Unavailable, CatchUpDialectWalk(FakeMemory()).begin(request(username = "")))
        assertEquals(Step.Unavailable, CatchUpDialectWalk(FakeMemory()).begin(request(password = "  ")))
    }
}
