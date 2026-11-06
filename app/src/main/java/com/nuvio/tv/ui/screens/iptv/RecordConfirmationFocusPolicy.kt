package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.recordings.api.ConfirmationKind

/**
 * The persistent confirmation sheet shown after Record / Record series / Cancel: which rows it has and
 * where the cursor starts. Pure. A confirmation must never be a place where the viewer's next OK does
 * something destructive, so the default focus is never Undo.
 */
internal object RecordConfirmationFocusPolicy {

    enum class Row { DONE, VIEW_RECORDINGS, ALLOW_EXACT, UNDO }

    /** Only a single-programme schedule can be undone; a series rule would re-add its airings. */
    fun undoable(kind: ConfirmationKind, isSeries: Boolean): Boolean = !isSeries && when (kind) {
        ConfirmationKind.SET, ConfirmationKind.MAY_START_LATE, ConfirmationKind.SAVES_ON_NEXT_OPEN -> true
        ConfirmationKind.ALREADY_SCHEDULED, ConfirmationKind.CANNOT_RECORD, ConfirmationKind.SERIES_NOTHING_YET -> false
    }

    /** Rows in display order, safest first. Destructive Undo is last, reached only on purpose. */
    fun rows(undoable: Boolean, offerExact: Boolean, canViewRecordings: Boolean): List<Row> = buildList {
        add(Row.DONE)
        if (canViewRecordings) add(Row.VIEW_RECORDINGS)
        if (offerExact) add(Row.ALLOW_EXACT)
        if (undoable) add(Row.UNDO)
    }

    /**
     * Where the cursor starts: always Done. The toast this sheet replaced put the cursor on its one
     * button (Undo), so the viewer's next OK cancelled the recording they had just made.
     */
    @Suppress("UNUSED_PARAMETER")
    fun defaultFocus(rows: List<Row>): Row = Row.DONE
}
