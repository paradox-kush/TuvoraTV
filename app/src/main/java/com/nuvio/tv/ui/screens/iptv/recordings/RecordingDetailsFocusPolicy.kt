package com.nuvio.tv.ui.screens.iptv.recordings

import com.nuvio.tv.core.recordings.api.RecordingAction
import com.nuvio.tv.core.recordings.api.RecordingStatus

/** Where the cursor starts in a recording's details sheet. Pure. */
internal object RecordingDetailsFocusPolicy {

    enum class Target { PLAY, CLOSE }

    /**
     * Play for a saved recording whose file is there; Close for everything else. Cancel recording,
     * Delete and Try again are reached on purpose, never by the first OK.
     */
    fun defaultFocus(status: RecordingStatus, actions: List<RecordingAction>): Target =
        if (status == RecordingStatus.DONE && RecordingAction.PLAY in actions) Target.PLAY else Target.CLOSE
}
