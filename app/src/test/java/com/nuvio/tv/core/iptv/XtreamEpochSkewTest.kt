package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Some panels build their EPG epochs from their own wall clock instead of UTC (measured live on
 * wa12: `epoch == parse(start string AS UTC)` to the second, so every epoch is shifted by the
 * panel's UTC offset and no row brackets now). Honest panels (onnipsite) fail that equality by
 * exactly their timezone offset. The equality IS the per-panel detector — a blanket subtraction
 * would break every honest panel, which is why this is voted per response instead of configured.
 */
class XtreamEpochSkewTest {

    /** 2026-08-15 00:00:00 UTC — the day of the field probes both fixtures are shaped after. */
    private val day = 1_786_752_000L
    private fun at(h: Int, m: Int) = day + h * 3600L + m * 60L
    private fun s(d: Int, h: Int, m: Int) = "2026-08-%02d %02d:%02d:00".format(d, h, m)

    // wa12 shape: the epoch equals its own start string read as UTC (the panel's local wall
    // clock, +2h) — the lie, row by row.
    private val wa12 = listOf(
        s(15, 22, 20) to at(22, 20),
        s(15, 23, 20) to at(23, 20),
        s(16, 0, 20) to (at(0, 20) + 86_400L),
    )

    // onnipsite shape: strings are panel-local (+1h) but the epochs are true UTC, so the
    // equality misses by the whole zone offset.
    private val onnipsite = listOf(
        s(15, 21, 40) to at(20, 40),
        s(15, 22, 40) to at(21, 40),
    )

    @Test
    fun `the wa12 capture shape reads as a liar`() {
        assertEquals(XtreamEpochSkew.Verdict.LIAR, XtreamEpochSkew.verdictOf(wa12))
    }

    @Test
    fun `the onnipsite capture shape reads as honest`() {
        assertEquals(XtreamEpochSkew.Verdict.HONEST, XtreamEpochSkew.verdictOf(onnipsite))
    }

    /**
     * Junk rows are the panel's normal output — absent strings, absent epochs, garbage text,
     * millisecond-scale numbers. None of them get a vote, and the parseable majority still decides.
     */
    @Test
    fun `junk rows do not vote and the majority still decides`() {
        val noisy = listOf(
            null to at(22, 20),                    // no string
            s(15, 23, 20) to null,                 // no epoch
            "not a date" to at(23, 20),            // garbage string
            s(15, 23, 20) to at(23, 20) * 1000L,   // millisecond-scale epoch
            "" to 0L,                              // nothing at all
        ) + wa12
        assertEquals(XtreamEpochSkew.Verdict.LIAR, XtreamEpochSkew.verdictOf(noisy))
    }

    /** Below [XtreamEpochSkew.MIN_VOTES] parseable pairs nothing is proven — never correct on it. */
    @Test
    fun `a single vote is not enough`() {
        assertEquals(XtreamEpochSkew.Verdict.UNKNOWN, XtreamEpochSkew.verdictOf(wa12.take(1)))
        assertEquals(XtreamEpochSkew.Verdict.UNKNOWN, XtreamEpochSkew.verdictOf(emptyList()))
        assertEquals(
            "junk-only rows prove nothing",
            XtreamEpochSkew.Verdict.UNKNOWN,
            XtreamEpochSkew.verdictOf(listOf(null to at(22, 20), "garbage" to at(23, 20))),
        )
    }

    /** A split vote is no proof either — an even panel must stay uncorrected. */
    @Test
    fun `a tie is unknown`() {
        assertEquals(
            XtreamEpochSkew.Verdict.UNKNOWN,
            XtreamEpochSkew.verdictOf(wa12.take(1) + onnipsite.take(1)),
        )
    }

    @Test
    fun `two agreeing votes decide`() {
        assertEquals(XtreamEpochSkew.Verdict.LIAR, XtreamEpochSkew.verdictOf(wa12.take(2)))
        assertEquals(XtreamEpochSkew.Verdict.HONEST, XtreamEpochSkew.verdictOf(onnipsite))
    }

    /**
     * The equality is to the second on the measured panel, but a beat of render skew must not flip
     * a vote. The tolerance stays far below 15 minutes — the smallest real zone step — so it can
     * never blur a liar into an honest panel.
     */
    @Test
    fun `seconds of skew still match, a real zone offset never does`() {
        val jittered = wa12.map { (text, epoch) -> text to epoch + 30 }
        assertEquals(XtreamEpochSkew.Verdict.LIAR, XtreamEpochSkew.verdictOf(jittered))
        val halfHourZone = wa12.map { (text, epoch) -> text to epoch - 1800 }
        assertEquals(XtreamEpochSkew.Verdict.HONEST, XtreamEpochSkew.verdictOf(halfHourZone))
    }

    /** Votes are counted from the front of the response, capped — a week's table is not re-voted whole. */
    @Test
    fun `the vote stops at the sample cap`() {
        // SAMPLE_VOTE_CAP liar rows followed by a long honest tail: the tail is past the cap.
        val capped = List(XtreamEpochSkew.SAMPLE_VOTE_CAP) { wa12[it % wa12.size] } +
            List(XtreamEpochSkew.SAMPLE_VOTE_CAP * 2) { onnipsite[it % onnipsite.size] }
        assertEquals(XtreamEpochSkew.Verdict.LIAR, XtreamEpochSkew.verdictOf(capped))
    }

    // --- the correction the verdict buys -----------------------------------------------------

    @Test
    fun `a liar panel is corrected by minus the clock pair offset`() {
        assertEquals(
            -7_200_000L,
            XtreamEpochSkew.effectiveOffsetMs(null, XtreamEpochSkew.Verdict.LIAR, 7_200_000L),
        )
    }

    @Test
    fun `honest and unknown panels are untouched`() {
        assertEquals(0L, XtreamEpochSkew.effectiveOffsetMs(null, XtreamEpochSkew.Verdict.HONEST, 7_200_000L))
        assertEquals(0L, XtreamEpochSkew.effectiveOffsetMs(null, XtreamEpochSkew.Verdict.UNKNOWN, 7_200_000L))
    }

    /** A liar whose clock pair could not be measured has nothing to subtract — leave it alone. */
    @Test
    fun `a liar without a measured clock pair is untouched`() {
        assertEquals(0L, XtreamEpochSkew.effectiveOffsetMs(null, XtreamEpochSkew.Verdict.LIAR, null))
    }

    /** The manual per-playlist offset overrides auto in every direction, including "wrong" ones. */
    @Test
    fun `a manual offset overrides the auto verdict`() {
        assertEquals(
            1_800_000L,
            XtreamEpochSkew.effectiveOffsetMs(1_800_000L, XtreamEpochSkew.Verdict.LIAR, 7_200_000L),
        )
        assertEquals(
            -3_600_000L,
            XtreamEpochSkew.effectiveOffsetMs(-3_600_000L, XtreamEpochSkew.Verdict.HONEST, null),
        )
    }
}
