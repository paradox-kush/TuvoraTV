package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamProgram

/**
 * What each guide row shows for the visible window, and which rows follow the window when it moves.
 *
 * Two loaders write one row: the source ladder's now/next (network, the live window's first paint)
 * and the windowed read of what is stored on disk (the channel's own catch-up table, or the
 * playlist's whole-guide store). They finish in whatever order the network allows, so the rule is
 * an invariant rather than an ordering: once a row carries stored rows for a window, a now/next
 * result refreshes only now/next and never replaces the cells — otherwise a late now/next wiped the
 * past off the focused row (B11: "cannot select a past programme"). Mobile's GuideWindowSource
 * states the same rule.
 *
 * And when the window travels, every row on screen follows it from disk, not only the focused one
 * (UX54: a past window used to show programmes for the focused channel alone, which read as the
 * guide having no data for anything else).
 */
internal object GuideWindowRows {

    /**
     * The stored rows to draw for a window. The channel's catch-up table wins — it carries the
     * panel's per-programme archive marks; the playlist's guide store fills rows that were never
     * focused. Null = nothing stored here: keep what the row already has rather than blanking it.
     */
    fun <T> pick(catchUpTable: List<T>, guideStore: List<T>): List<T>? =
        catchUpTable.ifEmpty { guideStore }.ifEmpty { null }

    /** A now/next ladder result arriving for a row. */
    fun mergeNowNext(
        existing: GuideEpg?,
        now: XtreamProgram?,
        next: XtreamProgram?,
        programmes: List<XtreamProgram>,
    ): GuideEpg =
        if (existing?.storedWindow == true) existing.copy(now = now, next = next)
        else GuideEpg(now, next, programmes)

    /** A windowed read from disk arriving for a row. */
    fun mergeStoredWindow(existing: GuideEpg?, programmes: List<XtreamProgram>): GuideEpg =
        GuideEpg(
            now = existing?.now ?: programmes.firstOrNull { it.nowPlaying },
            next = existing?.next,
            programmes = programmes,
            storedWindow = true,
        )

    /**
     * The span one windowed read covers: a window either side of the visible one, so a travel step
     * (one full window) or the minute tick's slot roll still has its cells on screen while the next
     * read is in flight.
     */
    fun readFromMs(windowStartMs: Long): Long = windowStartMs - GuideTimeTravel.WINDOW_MS

    fun readToMs(windowStartMs: Long): Long = windowStartMs + 2 * GuideTimeTravel.WINDOW_MS

    /**
     * Whether rows read for [fromMs, toMs) may still be painted now that the window starts at
     * [windowStartMs]: only if they cover all of it. A read for a window the viewer has travelled
     * past would otherwise repaint the rows with a stretch of time no longer on screen.
     */
    fun readCovers(fromMs: Long, toMs: Long, windowStartMs: Long): Boolean =
        windowStartMs >= fromMs && windowStartMs + GuideTimeTravel.WINDOW_MS <= toMs

    /**
     * Rows that republish when the window moves: the focused row and a screenful around it,
     * nearest first — the same neighbourhood the now/next prefetch covers. Disk reads only; no
     * row here costs a request.
     */
    fun rowsFollowingWindow(focusedIndex: Int, rowCount: Int): List<Int> =
        GuideEpgPrefetchPolicy.indexesAround(focusedIndex, rowCount)
}
