package com.nuvio.tv.playback.media3

import androidx.media3.common.PlaybackException

/**
 * What the Media3 backend does with a player error while it may be playing the live-pause buffer.
 * A time-shifted viewer who falls behind the buffer's window carries on from the window start; the
 * session must never see that as a live failure (it would silently reconnect to live). A buffer that
 * keeps falling behind without playing in between is a real failure and is shown.
 */
internal object Media3BehindWindowPolicy {
    const val MAX_RECOVERIES_WITHOUT_PROGRESS = 3

    enum class Action { SEEK_TO_WINDOW_START, REPORT }

    fun decide(errorCode: Int, isLocalBuffer: Boolean, recoveriesWithoutProgress: Int): Action =
        if (errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
            isLocalBuffer &&
            recoveriesWithoutProgress < MAX_RECOVERIES_WITHOUT_PROGRESS
        ) {
            Action.SEEK_TO_WINDOW_START
        } else {
            Action.REPORT
        }
}
