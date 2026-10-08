package com.nuvio.tv.ui.screens.iptv

/** Decisions at the guide boundary, independent of the player and Compose. */
internal object GuidePlaybackInteractionPolicy {
    fun focus(state: LiveGuideUiState, channelId: String): LiveGuideUiState =
        state.copy(focusedChannelId = channelId, actionError = null)

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
