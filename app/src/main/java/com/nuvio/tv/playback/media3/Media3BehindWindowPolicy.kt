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

/**
 * The backend's per-player state around [Media3BehindWindowPolicy]: counts recoveries since the buffer
 * last played, so the wiring (count on recover, reset on READY) is testable without a Media3 player.
 */
internal class Media3BehindWindowTracker {
    private var recoveries = 0

    /** A player error arrived; the returned action is final and the recovery is counted. */
    fun onError(errorCode: Int, isLocalBuffer: Boolean): Media3BehindWindowPolicy.Action {
        val action = Media3BehindWindowPolicy.decide(errorCode, isLocalBuffer, recoveries)
        if (action == Media3BehindWindowPolicy.Action.SEEK_TO_WINDOW_START) recoveries++
        return action
    }

    /** The player reached READY: it played, so earlier recoveries no longer count against it. */
    fun onReady() {
        recoveries = 0
    }
}
