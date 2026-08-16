package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.CatchUpEpgWindow

/**
 * Where the guide's visible two hours sit on the timeline, and how far the D-pad may move them.
 *
 * The clamps carry more weight than the stepping. Travelling past the provider's retention shows a
 * wall of cells nothing can play, which reads as a broken guide rather than as the end of the
 * archive — and catch-up is rare enough (44 archive channels in 26,430 on a real panel) that the
 * guide must never imply more of it than exists.
 */
internal object GuideTimeTravel {

    /** The header's tick, and the grid's cell granularity. */
    const val SLOT_MS = 30 * 60 * 1000L

    /** How much of the timeline is on screen. */
    const val WINDOW_MS = 2 * 60 * 60 * 1000L

    /**
     * How far one press of LEFT/RIGHT at the window's edge travels: one FULL window, not one slot.
     *
     * Field-reported on the Onn 4K: wired to a single slot, an evening viewer was ~40 presses from
     * yesterday — the day label never left "Today" for any number of presses a person would
     * actually make, and time travel read as broken. A page is two hours (the whole window), the
     * same distance mobile's Earlier/Later buttons move, so a day is a dozen presses (or a held
     * key) instead of forty. Fine positioning inside the window is the D-pad's normal cell walk.
     */
    const val EDGE_TRAVEL_SLOTS = (WINDOW_MS / SLOT_MS).toInt()

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** How much past the LIVE view shows by default — one slot, so recent history is visible
     *  and replay is discoverable without travelling (the approved mockups draw past cells). */
    const val LIVE_LOOKBACK_MS = SLOT_MS

    fun liveWindowStartMs(nowMs: Long): Long = slotFloor(nowMs - LIVE_LOOKBACK_MS)

    /**
     * The furthest back the viewer may travel: the provider's own window when it states one, else
     * the storage floor. Stopping at the stated window is the honest edge — a panel that says it
     * keeps three days is not hiding a fourth.
     */
    fun earliestWindowStartMs(nowMs: Long, catchUpDays: Int): Long {
        val days = if (catchUpDays > 0) catchUpDays else CatchUpEpgWindow.MIN_BACK_DAYS
        return slotFloor(nowMs - days * DAY_MS)
    }

    /**
     * The furthest forward: the point where the window's right edge reaches the horizon a fetch
     * actually stores. Past that every cell is empty because nothing was ever kept for it.
     */
    fun latestWindowStartMs(nowMs: Long): Long =
        maxOf(slotFloor(nowMs + CatchUpEpgWindow.FORWARD_MS - WINDOW_MS), liveWindowStartMs(nowMs))

    /** Moves the window [slots] half-hours (negative = back), clamped to the travelable range. */
    fun shift(currentStartMs: Long, slots: Int, nowMs: Long, catchUpDays: Int): Long =
        (currentStartMs + slots * SLOT_MS).coerceIn(
            earliestWindowStartMs(nowMs, catchUpDays),
            latestWindowStartMs(nowMs),
        )

    /**
     * Whether the window is still anchored at live. Only then may the minute tick roll it forward —
     * a viewer reading yesterday's schedule must not have it yanked to now under them.
     */
    fun isAtLiveEdge(windowStartMs: Long, nowMs: Long): Boolean =
        windowStartMs == liveWindowStartMs(nowMs)

    /** Whether the now-line belongs on screen: the visible window actually contains this instant. */
    fun containsNow(windowStartMs: Long, nowMs: Long): Boolean =
        nowMs >= windowStartMs && nowMs < windowStartMs + WINDOW_MS

    /** Where the now-line sits across the window, 0..1. Meaningless unless [containsNow]. */
    fun nowFraction(windowStartMs: Long, nowMs: Long): Float =
        ((nowMs - windowStartMs).toFloat() / WINDOW_MS).coerceIn(0f, 1f)

    private fun slotFloor(ms: Long): Long = Math.floorDiv(ms, SLOT_MS) * SLOT_MS
}
