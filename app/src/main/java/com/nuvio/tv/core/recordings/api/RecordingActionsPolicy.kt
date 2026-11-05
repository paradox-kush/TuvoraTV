package com.nuvio.tv.core.recordings.api

enum class RecordingAction { PLAY, CANCEL, RETRY, DELETE }

/** Which buttons a row (and its details sheet) offers. Pure. */
object RecordingActionsPolicy {
    /** [fileAvailable]: the file for a DONE row is on disk and can be opened. */
    fun actionsFor(row: RecordingRow, fileAvailable: Boolean): List<RecordingAction> = TODO("wave 3")
}
