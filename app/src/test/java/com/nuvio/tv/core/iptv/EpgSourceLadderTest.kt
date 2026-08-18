package com.nuvio.tv.core.iptv

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide's per-channel EPG resolution ladder: manual mapping → provider rows if they pass the
 * sanity gate → mirror → none, with the answering rung remembered per (account, channel).
 *
 * The gate exists because present-but-garbage used to beat absent: wa12's skewed short-EPG rows
 * (nothing bracketing now — every epoch one zone-offset in the future) suppressed the mirror
 * entirely under the old `.ifEmpty` fallback and rendered a fully empty visible guide.
 */
class EpgSourceLadderTest {

    /** 2026-08-15 13:20:00 UTC, in ms — an arbitrary honest "now" for the fixtures. */
    private val now = 1_786_800_000_000L
    private val hour = 3_600_000L

    private fun programme(startMs: Long, endMs: Long, title: String) =
        XtreamProgram(title = title, description = "", startMs = startMs, endMs = endMs, nowPlaying = false)

    /** An honest response: the airing programme brackets now, upcoming rows follow. */
    private fun saneRows() = listOf(
        programme(now - 20 * 60_000L, now + 40 * 60_000L, "airing"),
        programme(now + 40 * 60_000L, now + 100 * 60_000L, "next"),
    )

    /**
     * The pre-Fix-2 wa12 shape: contiguous rows, every one shifted a zone offset into the future,
     * the first "upcoming" row starting +1.43 h out — nothing brackets now.
     */
    private fun wa12Rows(): List<XtreamProgram> {
        val firstStart = now + (143 * hour) / 100
        return listOf(
            programme(firstStart, firstStart + hour, "shifted airing"),
            programme(firstStart + hour, firstStart + 2 * hour, "shifted next"),
            programme(firstStart + 2 * hour, firstStart + 3 * hour, "shifted later"),
        )
    }

    private fun mirrorRows() = listOf(
        programme(now - 30 * 60_000L, now + 30 * 60_000L, "mirror airing"),
        programme(now + 30 * 60_000L, now + 90 * 60_000L, "mirror next"),
    )

    // --- the ladder --------------------------------------------------------------------------

    @Test
    fun `the ladder prefers sane provider rows`() = runTest {
        var mirrorAsked = false
        val resolution = EpgSourceLadder.resolve(
            nowMs = now,
            provider = { saneRows() },
            mirror = { mirrorAsked = true; mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.PROVIDER, resolution.source)
        assertEquals(saneRows(), resolution.programmes)
        assertFalse("sane provider rows must not cost a mirror read", mirrorAsked)
    }

    @Test
    fun `garbage provider rows fall to the mirror`() = runTest {
        val resolution = EpgSourceLadder.resolve(
            nowMs = now,
            provider = { wa12Rows() },
            mirror = { mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.MIRROR, resolution.source)
        assertEquals(mirrorRows(), resolution.programmes)
    }

    /** Regression pin of today's `.ifEmpty { mirror }` behavior: empty fails the gate trivially. */
    @Test
    fun `an empty provider response falls to the mirror`() = runTest {
        val resolution = EpgSourceLadder.resolve(
            nowMs = now,
            provider = { emptyList() },
            mirror = { mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.MIRROR, resolution.source)
        assertEquals(mirrorRows(), resolution.programmes)
    }

    @Test
    fun `the mirror answering nothing leaves the channel empty`() = runTest {
        val resolution = EpgSourceLadder.resolve(
            nowMs = now,
            provider = { wa12Rows() },
            mirror = { emptyList() },
        )
        assertEquals(EpgSourceLadder.Source.NONE, resolution.source)
        assertEquals(emptyList<XtreamProgram>(), resolution.programmes)
    }

    @Test
    fun `a manual resolver answer wins every rung`() = runTest {
        var providerAsked = false
        val manualRows = listOf(programme(now - hour, now + hour, "user mapped"))
        val resolution = EpgSourceLadder.resolveAndRemember(
            memory = EpgSourceLadder.Memory(),
            accountId = "acc",
            streamId = 7,
            nowMs = now,
            manual = { _, _, _ -> manualRows },
            provider = { providerAsked = true; saneRows() },
            mirror = { mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.MANUAL, resolution.source)
        assertEquals(manualRows, resolution.programmes)
        assertFalse("a manual mapping must answer before the panel is contacted", providerAsked)
    }

    /** The seam's fall-through contract: an unmapped channel (null) takes the automatic rungs. */
    @Test
    fun `an unmapped manual channel falls through to the automatic rungs`() = runTest {
        val resolution = EpgSourceLadder.resolve(
            nowMs = now,
            manual = { null },
            provider = { saneRows() },
            mirror = { mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.PROVIDER, resolution.source)
    }

    // --- the per-channel source memory ---------------------------------------------------------

    @Test
    fun `the chosen source is remembered per channel`() = runTest {
        val memory = EpgSourceLadder.Memory()
        // Channel 1's panel rows are garbage: it falls to the mirror and that is remembered.
        EpgSourceLadder.resolveAndRemember(
            memory = memory, accountId = "acc", streamId = 1, nowMs = now,
            provider = { wa12Rows() }, mirror = { mirrorRows() },
        )
        // Channel 2's panel is honest: it stays on the provider.
        EpgSourceLadder.resolveAndRemember(
            memory = memory, accountId = "acc", streamId = 2, nowMs = now,
            provider = { saneRows() }, mirror = { mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.MIRROR, memory.rememberedFor("acc", 1))
        assertEquals(EpgSourceLadder.Source.PROVIDER, memory.rememberedFor("acc", 2))

        // The next focus on channel 1 goes straight to the mirror — no panel round-trip.
        var providerAsked = false
        val again = EpgSourceLadder.resolveAndRemember(
            memory = memory, accountId = "acc", streamId = 1, nowMs = now,
            provider = { providerAsked = true; wa12Rows() }, mirror = { mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.MIRROR, again.source)
        assertFalse("a channel remembered as mirror-fed must not re-ask the panel", providerAsked)
    }

    /** The memory is a hint, not a cage: a mirror that stops answering falls back to the ladder. */
    @Test
    fun `a remembered mirror that dries up falls back through the full ladder`() = runTest {
        val memory = EpgSourceLadder.Memory()
        memory.remember("acc", 1, EpgSourceLadder.Source.MIRROR)
        val resolution = EpgSourceLadder.resolveAndRemember(
            memory = memory, accountId = "acc", streamId = 1, nowMs = now,
            provider = { saneRows() }, mirror = { emptyList() },
        )
        assertEquals(EpgSourceLadder.Source.PROVIDER, resolution.source)
        assertEquals(EpgSourceLadder.Source.PROVIDER, memory.rememberedFor("acc", 1))
    }

    /** A transient panel failure must not pin a channel empty for the whole session. */
    @Test
    fun `a channel that resolved to nothing is retried on the next focus`() = runTest {
        val memory = EpgSourceLadder.Memory()
        EpgSourceLadder.resolveAndRemember(
            memory = memory, accountId = "acc", streamId = 1, nowMs = now,
            provider = { emptyList() }, mirror = { emptyList() },
        )
        assertEquals(EpgSourceLadder.Source.NONE, memory.rememberedFor("acc", 1))
        // The panel recovered: the retry reaches it and the channel comes back.
        val recovered = EpgSourceLadder.resolveAndRemember(
            memory = memory, accountId = "acc", streamId = 1, nowMs = now,
            provider = { saneRows() }, mirror = { emptyList() },
        )
        assertEquals(EpgSourceLadder.Source.PROVIDER, recovered.source)
    }

    @Test
    fun `the memory stays bounded and forgets per account`() {
        val memory = EpgSourceLadder.Memory(cap = 3)
        for (id in 1..4) memory.remember("acc", id, EpgSourceLadder.Source.PROVIDER)
        assertNull("the oldest entry is evicted at the cap", memory.rememberedFor("acc", 1))
        assertEquals(EpgSourceLadder.Source.PROVIDER, memory.rememberedFor("acc", 4))

        memory.remember("other", 9, EpgSourceLadder.Source.MIRROR)
        memory.forgetAccount("acc")
        assertNull(memory.rememberedFor("acc", 4))
        assertEquals(EpgSourceLadder.Source.MIRROR, memory.rememberedFor("other", 9))
    }

    /** What the settings coverage line reads: per-account counts of which rung answers. */
    @Test
    fun `the tally counts sources for one account only`() {
        val memory = EpgSourceLadder.Memory()
        memory.remember("acc", 1, EpgSourceLadder.Source.PROVIDER)
        memory.remember("acc", 2, EpgSourceLadder.Source.MIRROR)
        memory.remember("acc", 3, EpgSourceLadder.Source.MIRROR)
        memory.remember("acc", 4, EpgSourceLadder.Source.NONE)
        memory.remember("other", 5, EpgSourceLadder.Source.PROVIDER)
        val tally = memory.tally("acc")
        assertEquals(EpgSourceLadder.Tally(manual = 0, provider = 1, mirror = 2, none = 1), tally)
        assertEquals(4, tally.total)
    }

    // --- the sanity gate ------------------------------------------------------------------------

    @Test
    fun `the gate tolerates a schedule boundary but not a zone skew`() {
        // A programme that ended moments ago (the guide asked mid-handover): still sane.
        val justEnded = listOf(programme(now - hour, now - 60_000L, "just ended"))
        assertTrue(EpgSourceLadder.providerPassesGate(justEnded, now))
        // The smallest real zone step (15 min) must already fail — slack far below it.
        val quarterOff = listOf(programme(now + 15 * 60_000L, now + 75 * 60_000L, "shifted"))
        assertFalse(EpgSourceLadder.providerPassesGate(quarterOff, now))
        assertFalse(EpgSourceLadder.providerPassesGate(emptyList(), now))
    }

    // --- reporting the coverage split -----------------------------------------------------------
    //
    // epg_mapping counts what the MIRROR could match and says nothing about the panel's own EPG.
    // epg_resolve reports what each channel actually resolved to. The floor was 50 on a guess and
    // the event never fired in the field; these pin it to a measured session.
    //
    // NOTE assertion order: JUnit is assertTrue(message, condition); the commonTest twin puts the
    // message LAST. Never regex-port between them.

    private fun coverage(manual: Int = 0, provider: Int = 0, mirror: Int = 0, none: Int = 0) =
        EpgSourceLadder.Tally(manual, provider, mirror, none)

    @Test
    fun `a real browsing session reports`() {
        // The session that caught the bad threshold: an S24 resolved 19 channels and stopped.
        val realSession = coverage(provider = 6, mirror = 3, none = 10)
        assertEquals(19, realSession.total)
        assertTrue(
            "a normal browse must produce a report, or the event is decorative",
            EpgSourceLadder.shouldReport(realSession, 0),
        )
    }

    @Test
    fun `a degenerate sample is still refused`() {
        assertFalse(EpgSourceLadder.shouldReport(coverage(mirror = 3), 0))
    }

    @Test
    fun `the floor counts every source - not just the hits`() {
        assertTrue(
            "a playlist where nothing resolves is exactly the case worth reporting",
            EpgSourceLadder.shouldReport(coverage(none = EpgSourceLadder.MIN_REPORT_SAMPLE), 0),
        )
    }

    @Test
    fun `a report waits for the sample to double`() {
        assertFalse("19 is not yet double 10", EpgSourceLadder.shouldReport(coverage(none = 19), 10))
        assertTrue("20 is", EpgSourceLadder.shouldReport(coverage(none = 20), 10))
    }

    @Test
    fun `a long browse reports a handful of times - not hundreds`() {
        var last = 0
        var reports = 0
        for (total in 1..1_000) {
            val t = coverage(none = total)
            if (EpgSourceLadder.shouldReport(t, last)) { reports++; last = t.total }
        }
        assertTrue("a thousand channels should yield a few samples, was $reports", reports in 2..12)
    }

    @Test
    fun `the memory tracks the sample size per account`() {
        val memory = EpgSourceLadder.Memory()
        assertEquals(0, memory.lastReportedTotal("acc"))
        memory.markReported("acc", 19)
        assertEquals(19, memory.lastReportedTotal("acc"))
        assertEquals("one account must not silence another", 0, memory.lastReportedTotal("other"))
    }

    @Test
    fun `forgetting an account lets its new split be reported`() {
        val memory = EpgSourceLadder.Memory()
        memory.remember("acc", 1, EpgSourceLadder.Source.MIRROR)
        memory.markReported("acc", 40)
        memory.forgetAccount("acc")
        assertEquals(0, memory.lastReportedTotal("acc"))
    }

    // ---- A failed panel ask is not a coverage fact (2026-08-18 field regression) ----------------
    //
    // Caught on this exact device. An Onn 4K browsing the guide DURING a 76s mirror sync reported
    // provider=0, none=68/80. An S24 on the same account an hour later resolved 37% from that same
    // panel. The panel was fine — the box was saturated, every shortEpg call failed, and the call
    // site collapsed Result.failure into emptyList(), so the ladder could not tell a timeout from
    // an honest empty answer and booked each failure as "this channel has no guide".

    @Test
    fun `a failed panel ask with no mirror is unavailable - not none`() = runTest {
        val r = EpgSourceLadder.resolve(
            nowMs = now,
            provider = { null },          // the ask FAILED
            mirror = { emptyList() },
        )
        assertEquals("a timeout is not a coverage fact", EpgSourceLadder.Source.UNAVAILABLE, r.source)
        assertTrue(r.programmes.isEmpty())
    }

    @Test
    fun `a panel that answers with nothing is still none`() = runTest {
        val r = EpgSourceLadder.resolve(
            nowMs = now,
            provider = { emptyList() },   // the panel SPOKE, and had nothing
            mirror = { emptyList() },
        )
        assertEquals("an honest empty answer is coverage", EpgSourceLadder.Source.NONE, r.source)
    }

    @Test
    fun `a failed panel ask still falls to the mirror`() = runTest {
        val r = EpgSourceLadder.resolve(
            nowMs = now,
            provider = { null },
            mirror = { saneRows() },
        )
        assertEquals("failure must not suppress the mirror", EpgSourceLadder.Source.MIRROR, r.source)
    }

    @Test
    fun `a dried-up mirror plus a failed panel ask is unavailable`() = runTest {
        val r = EpgSourceLadder.resolve(
            nowMs = now,
            remembered = EpgSourceLadder.Source.MIRROR,
            provider = { null },
            mirror = { emptyList() },
        )
        assertEquals("the remembered branch counts too", EpgSourceLadder.Source.UNAVAILABLE, r.source)
    }

    @Test
    fun `the tally keeps failures out of the none count`() {
        val memory = EpgSourceLadder.Memory()
        memory.remember("acct", 1, EpgSourceLadder.Source.NONE)
        memory.remember("acct", 2, EpgSourceLadder.Source.UNAVAILABLE)
        memory.remember("acct", 3, EpgSourceLadder.Source.UNAVAILABLE)
        memory.remember("acct", 4, EpgSourceLadder.Source.PROVIDER)
        val tally = memory.tally("acct")
        assertEquals("only the honest empty answer is none", 1, tally.none)
        assertEquals("the two failures are counted apart", 2, tally.unavailable)
        assertEquals("failures still count toward the sample size", 4, tally.total)
    }

    @Test
    fun `a failure is retried rather than pinned for the session`() = runTest {
        val memory = EpgSourceLadder.Memory()
        var asks = 0
        repeat(2) {
            EpgSourceLadder.resolveAndRemember(
                memory = memory,
                accountId = "acct",
                streamId = 7,
                nowMs = now,
                provider = { asks++; if (asks == 1) null else saneRows() },
                mirror = { emptyList() },
            )
        }
        assertEquals("a transient failure must not cool the channel for the session", 2, asks)
        assertEquals(
            "the retry's honest answer replaces the failure",
            EpgSourceLadder.Source.PROVIDER,
            memory.rememberedFor("acct", 7),
        )
    }
}
