package com.nuvio.tv.core.iptv

/**
 * How much history the guide keeps, and when it is allowed to ask the panel for more.
 *
 * Pure policy: the historical-EPG fetch is the one part of catch-up that can OOM a 1 GB box, so
 * every number that bounds it lives here where a test can pin it rather than inside the fetcher.
 */
object CatchUpEpgWindow {

    /** How long a channel's stored guide stays fresh before another fetch is allowed. */
    const val FETCH_TTL_MS = 6 * 60 * 60 * 1000L

    /**
     * The least history worth keeping regardless of the panel's stated window. Panels understate
     * `tv_archive_duration` (and often omit it), and per-programme `has_archive` can mark rows
     * older than the stated window — so the floor has to clear the longest window we have seen in
     * the field (7 days) with a day to spare.
     */
    const val MIN_BACK_DAYS = 8

    /** How far ahead a fetch keeps rows: tonight plus tomorrow, never a panel's whole week. */
    const val FORWARD_MS = 36 * 60 * 60 * 1000L

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * Oldest instant worth storing: `now - max(window, 8 days)`. A row older than this can never
     * be offered (the panel dropped it long ago), so it is discarded AT PARSE rather than written
     * and pruned later — the difference between streaming a body and materializing one.
     */
    fun parseFromMs(nowMs: Long, catchUpDays: Int): Long =
        nowMs - maxOf(catchUpDays, MIN_BACK_DAYS) * DAY_MS

    /** Newest instant worth storing. */
    fun parseToMs(nowMs: Long): Long = nowMs + FORWARD_MS

    /**
     * Whether a parsed row is worth keeping: it has to describe a real broadcast (a positive start
     * and a positive length — the degenerate rows [XtreamCatchUp.actionFor] would refuse anyway)
     * and it has to overlap the window.
     */
    fun keeps(startMs: Long, endMs: Long, nowMs: Long, catchUpDays: Int): Boolean {
        if (startMs <= 0L || endMs <= startMs) return false
        return endMs > parseFromMs(nowMs, catchUpDays) && startMs < parseToMs(nowMs)
    }

    /** Rows ending before this are dropped on every refill — the guide never reads that far back. */
    fun pruneCutoffMs(nowMs: Long, catchUpDays: Int): Long = parseFromMs(nowMs, catchUpDays)

    /**
     * Whether the focused channel's guide may be fetched. Never fetched = yes; fetched within
     * [FETCH_TTL_MS] = no. A stamp in the FUTURE (the device clock moved back, or a panel's own
     * clock wrote it) is treated as stale rather than as fresh forever.
     */
    fun shouldFetch(fetchedAtMs: Long?, nowMs: Long): Boolean {
        if (fetchedAtMs == null) return true
        val age = nowMs - fetchedAtMs
        return age !in 0 until FETCH_TTL_MS
    }
}
