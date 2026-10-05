package com.nuvio.tv.ui.screens.iptv

import androidx.compose.ui.input.key.Key

/**
 * What a remote key does in the guide's fullscreen live view.
 *
 * Pulled out of the Composable's root key handler so the remote map is testable without Compose
 * focus. While fullscreen the channel row is a hidden, locked focus anchor, so every key arrives
 * here first; the overlays that take focus of their own (the channel list, a hide notice's Undo)
 * get their keys untouched. BACK is never taken here — it belongs to the BackHandlers.
 */
internal object LiveFullscreenKeyPolicy {

    enum class Action {
        /** Not ours: focus search, the overlay that owns focus, the BackHandlers. */
        PASS,

        /** The KeyUp half of a handled press — consumed so the locked row never clicks. */
        CONSUME,
        TOGGLE_PAUSE,
        SHOW_CONTROLS,
        ZAP_PREVIOUS,
        ZAP_NEXT,

        /** F08: jump back to the channel watched before (LAST/RECALL key). */
        ZAP_BACK,

        /** F08: the channel list over the video, without leaving fullscreen. */
        OPEN_CHANNEL_LIST,

        /** MENU: hide the aimed channel (overlay write + notice with Undo). */
        HIDE_CHANNEL,
    }

    private val handled = setOf(
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause,
        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
        Key.ChannelUp, Key.ChannelDown, Key.LastChannel,
        Key.Menu,
    )

    fun actionFor(
        key: Key,
        isKeyDown: Boolean,
        paused: Boolean,
        channelListOpen: Boolean,
        noticeShowing: Boolean,
    ): Action {
        if (channelListOpen || noticeShowing) return Action.PASS
        if (key !in handled) return Action.PASS
        if (!isKeyDown) return Action.CONSUME
        return when (key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> Action.TOGGLE_PAUSE
            Key.MediaPlay -> if (paused) Action.TOGGLE_PAUSE else Action.SHOW_CONTROLS
            Key.MediaPause -> if (!paused) Action.TOGGLE_PAUSE else Action.SHOW_CONTROLS
            // The live-TV remote split: UP/DOWN move up/down the guide's list. The dedicated channel
            // keys follow the number on the remote: CH+ is the next (higher) channel number.
            Key.DirectionUp, Key.ChannelDown -> Action.ZAP_PREVIOUS
            Key.DirectionDown, Key.ChannelUp -> Action.ZAP_NEXT
            Key.LastChannel -> Action.ZAP_BACK
            Key.DirectionLeft -> Action.OPEN_CHANNEL_LIST
            Key.Menu -> Action.HIDE_CHANNEL
            else -> Action.SHOW_CONTROLS
        }
    }
}
