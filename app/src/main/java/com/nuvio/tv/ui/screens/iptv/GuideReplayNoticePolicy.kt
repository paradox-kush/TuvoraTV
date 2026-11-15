package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamProgram

/** The replay the guide's notice re-runs on Retry: the same channel and programme. */
data class GuideReplayRetry(val channel: GuideChannel, val programme: XtreamProgram)

/**
 * How the guide's replay notice is raised and cleared. Pure, so it tests without Compose.
 * A replay that failed to open (a portal refusal, a request failure) carries Retry; a programme the
 * provider simply has no recording of does not - there is nothing a second try would change.
 */
internal object GuideReplayNoticePolicy {
    /**
     * How long the Retry notice waits for the viewer: until they dismiss it (BACK or any arrow). It used to let go
     * after 20 s, so a later OK fell through to the channel row and started an unwanted live preview (UX278). A
     * dismissal puts focus back in the guide; the notice never hands its keys to the row on its own.
     */
    const val RETRY_NOTICE_MS = Long.MAX_VALUE

    fun refused(state: LiveGuideUiState, message: String, channel: GuideChannel, programme: XtreamProgram): LiveGuideUiState =
        state.copy(actionError = message, replayRetry = GuideReplayRetry(channel, programme))

    fun noRecording(state: LiveGuideUiState, message: String): LiveGuideUiState =
        state.copy(actionError = message, replayRetry = null)

    fun dismiss(state: LiveGuideUiState): LiveGuideUiState = state.copy(actionError = null, replayRetry = null)
}
