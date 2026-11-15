package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.GuideLayoutPreset

/**
 * Change 15 of the IPTV redesign (2026-11-15, A9 + A4): Guide options, a right-side panel of its own. The guide's
 * size left the channel's options for it, and the opt-in "resume the last channel on open" lives here, off by
 * default. Pure.
 */
internal object GuideOptionsPolicy {
    /** A4 default: opening the guide never tunes by itself (a Stalker single-use link would be spent per visit). */
    const val RESUME_ON_OPEN_DEFAULT = false

    sealed interface Row {
        data class Size(val preset: GuideLayoutPreset) : Row
        data object ResumeOnOpen : Row
    }

    fun rows(): List<Row> = GuideLayoutPreset.entries.map { Row.Size(it) } + Row.ResumeOnOpen

    /** Entry focus: the size in use (01 "Guide options: the size in use"). */
    fun entryRow(current: GuideLayoutPreset): Row = Row.Size(current)

    /**
     * A4: whether opening the guide tunes the last-watched channel. Only with the switch on, only on the first entry,
     * only when there is such a channel in the list, and never when something already plays.
     */
    fun resumeOnEntry(resumeOn: Boolean, firstEntry: Boolean, lastWatchedListed: Boolean, somethingPlaying: Boolean): Boolean =
        resumeOn && firstEntry && lastWatchedListed && !somethingPlaying

    /** Device pass 2, D3: the size in use says "Current", focused or not (the soft fill alone vanished under the ring). */
    fun isCurrent(row: Row, current: GuideLayoutPreset): Boolean = row is Row.Size && row.preset == current
}
