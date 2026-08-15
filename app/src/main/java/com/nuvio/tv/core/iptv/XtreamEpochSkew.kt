package com.nuvio.tv.core.iptv

/**
 * Detects panels whose EPG epochs are their own wall clock rather than UTC.
 *
 * Measured live (2026-08-15): on wa12 every `start_timestamp` equals its own `start` STRING read
 * as UTC, to the second — the panel builds epochs from its local clock, so every epoch is shifted
 * by exactly the panel's UTC offset and no row brackets now (the guide looks empty while the
 * now-panel names the airing programme). On onnipsite the same equality fails by the whole zone
 * offset and the epochs bracket now correctly. The equality is therefore the per-panel detector:
 * a blanket subtraction of the clock-pair offset would repair wa12 and break onnipsite by −1h.
 *
 * Voted per RESPONSE, majority over the rows, because single rows are junk in every way panels
 * know how (absent fields, garbage strings, millisecond-scale numbers). A row that cannot vote
 * abstains; too few votes proves nothing and nothing gets corrected — an uncorrected liar shows
 * exactly what it showed yesterday, while a wrongly-corrected honest panel is a regression.
 *
 * Pure on purpose (no clock, no I/O): both EPG lanes — the short-EPG mapping and the streamed
 * `get_simple_data_table` parse — apply the SAME rules at their parse boundary, so the guide
 * timeline, `XtreamCatchUp.actionFor` and the replay start math all agree about when a programme
 * aired.
 */
object XtreamEpochSkew {

    enum class Verdict {
        /** The response's epochs are panel-local wall clock: subtract the clock-pair offset. */
        LIAR,

        /** The epochs are true UTC: touch nothing. */
        HONEST,

        /** Too few parseable pairs (or a split vote): treat as honest — never correct on a hunch. */
        UNKNOWN,
    }

    /**
     * Fewest votes that may decide. `get_short_epg` commonly answers four rows and junk eats some,
     * so this is two: a single row's accidental equality must not shift a whole guide, and two
     * agreeing rows on the same response are no longer an accident.
     */
    const val MIN_VOTES = 2

    /** Votes are counted from the front of a response, capped — a week-long table is a sample, not a census. */
    const val SAMPLE_VOTE_CAP = 32

    /**
     * Rows a streaming parser may hold back while its vote is still open. Past this the verdict is
     * forced from whatever votes exist — the bound that keeps a string-less panel's whole table
     * from being buffered (the XMLTV OOM lesson, applied to the detector).
     */
    const val PENDING_ROW_CAP = 256

    /**
     * Equality slack. The measured panel matches to the second, but a beat of render skew must not
     * flip a vote — and a minute stays two orders of magnitude below 15 minutes, the smallest real
     * zone step, so it can never blur a liar into an honest panel (or the reverse).
     */
    const val EQUALITY_TOLERANCE_SECONDS = 60L

    /** At or above this a "seconds" value is milliseconds (or garbage) — it abstains. */
    private const val PLAUSIBLE_EPOCH_CEILING = 100_000_000_000L

    /**
     * One row's vote: true = the pair satisfies the liar equality, false = it does not, null = the
     * row cannot vote (absent/garbage string, absent/implausible epoch).
     */
    fun vote(startText: String?, epochSeconds: Long?): Boolean? {
        val epoch = epochSeconds ?: return null
        if (epoch <= 0L || epoch >= PLAUSIBLE_EPOCH_CEILING) return null
        val text = startText?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val asUtc = ServerClockOffset.parseAsUtcSeconds(text) ?: return null
        val diff = asUtc - epoch
        return diff in -EQUALITY_TOLERANCE_SECONDS..EQUALITY_TOLERANCE_SECONDS
    }

    /** Majority over counted votes; below [MIN_VOTES] or tied, nothing is proven. */
    fun verdict(liarVotes: Int, honestVotes: Int): Verdict {
        if (liarVotes + honestVotes < MIN_VOTES) return Verdict.UNKNOWN
        return when {
            liarVotes > honestVotes -> Verdict.LIAR
            honestVotes > liarVotes -> Verdict.HONEST
            else -> Verdict.UNKNOWN
        }
    }

    /** [verdict] over one response's (start string, epoch seconds) pairs, in row order. */
    fun verdictOf(rows: Iterable<Pair<String?, Long?>>): Verdict {
        var liar = 0
        var honest = 0
        for ((text, epoch) in rows) {
            when (vote(text, epoch)) {
                true -> liar++
                false -> honest++
                null -> Unit
            }
            if (liar + honest >= SAMPLE_VOTE_CAP) break
        }
        return verdict(liar, honest)
    }

    /**
     * The correction to ADD to this response's epochs. The manual per-playlist offset (non-null =
     * the user set it) overrides the vote in every direction — it exists for the residue auto
     * misses. Otherwise a proven liar gets minus the measured clock-pair offset
     * ([ServerClockOffset]; null = the panel's own clocks were junk, so there is nothing to
     * subtract), and everything else gets zero — the byte-identical path every honest panel has
     * always taken.
     */
    fun effectiveOffsetMs(manualOffsetMs: Long?, verdict: Verdict, clockPairOffsetMs: Long?): Long =
        manualOffsetMs
            ?: if (verdict == Verdict.LIAR && clockPairOffsetMs != null) -clockPairOffsetMs else 0L
}
