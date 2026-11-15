package com.nuvio.tv.ui.screens.iptv

/**
 * Change 10 of the IPTV redesign (2026-11-15, 02 "Programme cells"), first part: schedule mode you can see. While the
 * cursor is in a row's programme cells or the window sits away from now, a "Schedule · Today 21:30 · Back = now"
 * pill replaces the group name above the rows (UX212, UX261: the mode was invisible and Back's meaning with it).
 * Pure.
 */
internal object GuideSchedulePillPolicy {
    fun shows(inCells: Boolean, awayFromNow: Boolean): Boolean = inCells || awayFromNow

    /** The time the pill names: the programme under the cursor, else the start of the window on screen. */
    fun timeMs(inspectedStartMs: Long?, windowStartMs: Long, nowMs: Long, awayFromNow: Boolean): Long =
        inspectedStartMs ?: if (awayFromNow) windowStartMs else nowMs
}
