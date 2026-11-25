package com.nuvio.tv.core.recommendations

/**
 * How a Watch Next row is written, given who is writing and whether a row already exists.
 *
 * The Google TV launcher hides a removed item by setting COLUMN_BROWSABLE=0 on our row; apps cannot
 * write that column, and the "browsable disabled" broadcast may never reach us. So the hidden row
 * itself is what keeps a removed item off the home screen:
 * - RECONCILE (planner, periodic job, app start) UPDATEs in place, so a hidden row stays hidden.
 * - PLAYED (real playback of this item) REPLACEs the row (delete + insert), which makes a hidden
 *   row visible again: "a removed item comes back only after it is played again in the app".
 *
 * Pure: no Android types.
 */
object WatchNextWritePolicy {

    enum class WriteSource { PLAYED, RECONCILE }

    enum class WriteMode { INSERT, UPDATE, REPLACE }

    fun modeFor(source: WriteSource, rowExists: Boolean): WriteMode = when {
        !rowExists -> WriteMode.INSERT
        source == WriteSource.PLAYED -> WriteMode.REPLACE
        else -> WriteMode.UPDATE
    }
}
