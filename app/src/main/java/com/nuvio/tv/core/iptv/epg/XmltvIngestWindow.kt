package com.nuvio.tv.core.iptv.epg

/**
 * How much of an XMLTV guide is worth putting on disk.
 *
 * The ingest used to keep **everything** the feed contained. That was survivable only because it
 * ran for M3U playlists alone: one guide, replaced wholesale on each refresh. It is not survivable
 * as a general rule — a provider publishing a week of schedule for 10,000 channels is rows a 1 GB
 * box cannot afford, and the whole-guide lane for Xtream panels (which every panel serves at
 * `xmltv.php`) would multiply exactly that across the user base.
 *
 * So the bound is stated here rather than left implicit. The numbers match the EPG mirror's own
 * window, which has been carrying the canonical guide in production since 2026-07-11:
 *
 *  - **6 hours back** — the docked guide opens one hour in the past ([com.nuvio.tv.ui.screens.iptv.GuideTimeTravel] anchors at
 *    `now − 1h`) so recent history is visible; six gives that room and no more. Deep history is
 *    *not* this lane's job: replay archives come from the per-channel table fetch, which keeps its
 *    own eight-day window ([com.nuvio.tv.core.iptv.CatchUpEpgWindow]).
 *  - **48 hours forward** — far more than the UI can reach. The guide renders a five-hour window
 *    and time travel refuses to move the anchor past live, so the furthest a viewer can see is
 *    about `now + 4h`. Two days leaves room for a panel whose clock is a day out.
 *
 * Pure so the bound is pinned by tests: getting it wrong is invisible until a budget device fills
 * up, and by then the rows are already written.
 */
object XmltvIngestWindow {

    const val BACK_MS = 6L * 60 * 60 * 1000
    const val AHEAD_MS = 48L * 60 * 60 * 1000

    /** The longest archive worth mirroring (the longest window seen in the field, see CatchUpEpgWindow). */
    const val MAX_CATCHUP_BACK_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * How far back to keep a channel that replays [catchUpDays] days (an M3U playlist's `catchup-days`):
     * the whole archive, so the guide has something to replay, never more than [MAX_CATCHUP_BACK_MS] and
     * never less than the default [BACK_MS]. Channels without an archive keep the default.
     */
    fun backMsForCatchUpDays(catchUpDays: Int): Long =
        (catchUpDays.coerceAtLeast(0) * 24L * 60 * 60 * 1000).coerceIn(BACK_MS, MAX_CATCHUP_BACK_MS)

    /**
     * Per-guide-id back window for a playlist whose channels carry a catch-up depth. [assigned] are the
     * matcher's (stream id, normalized guide id) pairs; [manual] are the user's hand-picked ones. A guide
     * id shared by several channels keeps the longest depth. [catchUpDaysBySid] holds only channels that replay.
     */
    fun backMsByGuide(
        assigned: List<Pair<Int, String>>,
        manual: List<Pair<Int, String>>,
        catchUpDaysBySid: Map<Int, Int>,
    ): Map<String, Long> {
        if (catchUpDaysBySid.isEmpty()) return emptyMap()
        val out = HashMap<String, Long>()
        for ((sid, guideId) in assigned + manual) {
            val days = catchUpDaysBySid[sid] ?: continue
            out.merge(guideId, backMsForCatchUpDays(days), ::maxOf)
        }
        return out
    }

    /** Oldest instant worth keeping. */
    fun windowStartMs(nowMs: Long, backMs: Long = BACK_MS): Long = nowMs - backMs

    /** Newest instant worth keeping. */
    fun windowEndMs(nowMs: Long): Long = nowMs + AHEAD_MS

    /**
     * Whether one parsed programme survives to the database: it must overlap the window, and it
     * must be a real span.
     *
     * Degenerate rows are refused here for the same reason [com.nuvio.tv.core.iptv.CatchUpEpgWindow] refuses them — a
     * zero-length programme stored at epoch is returned by every window read that spans it, and a
     * feed with a broken date format produces thousands of them.
     */
    fun keeps(programmeStartMs: Long, programmeEndMs: Long, nowMs: Long, backMs: Long = BACK_MS): Boolean {
        if (programmeEndMs <= programmeStartMs) return false
        if (programmeStartMs <= 0L) return false
        return programmeEndMs > windowStartMs(nowMs, backMs) && programmeStartMs < windowEndMs(nowMs)
    }
}
