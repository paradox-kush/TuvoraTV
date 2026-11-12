package com.nuvio.tv.ui.screens.iptv

/** Decisions at the guide boundary, independent of the player and Compose. */
internal object GuidePlaybackInteractionPolicy {
    /**
     * A row that has already left the list cannot take the aim: its focus callback can land a frame
     * after a hide re-aimed the guide at the neighbour (UX168), which left the aim on nothing and sent
     * the notice-expiry restore to row one.
     */
    fun focus(state: LiveGuideUiState, channelId: String): LiveGuideUiState =
        if (state.channels.none { it.contentId == channelId }) state
        else state.copy(focusedChannelId = channelId, actionError = null, replayRetry = null)

    enum class Click { TUNE, FULLSCREEN, RETRY }

    fun click(channelId: String, playingId: String?, pendingId: String?, fullscreenEnabled: Boolean): Click = when {
        channelId == pendingId -> Click.FULLSCREEN
        channelId != playingId -> Click.TUNE
        fullscreenEnabled -> Click.FULLSCREEN
        else -> Click.RETRY
    }

    fun showFocusedError(focusedId: String?, errorChannelId: String?): Boolean =
        focusedId != null && focusedId == errorChannelId

    fun coverFailedFrame(hasPlaybackError: Boolean): Boolean = hasPlaybackError
}
