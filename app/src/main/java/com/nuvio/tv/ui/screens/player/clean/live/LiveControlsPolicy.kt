package com.nuvio.tv.ui.screens.player.clean.live

import androidx.compose.ui.input.key.Key

/**
 * F28: every decision of the TV live overlay, in one place, without Compose.
 *
 * One overlay serves the guide's fullscreen and the clean live player, built from the VOD player's
 * own pieces. The owner's rules (2026-10-03) are encoded here and nowhere else:
 *  - OK with the controls hidden SHOWS them (never play/pause), so zapping can't pause by accident;
 *  - UP/DOWN with the controls hidden change channel; LEFT/RIGHT reveal the controls and then move
 *    focus along them — there is no timeline, so no scrubbing;
 *  - BACK closes a panel first, then hides the controls, then leaves (to the guide);
 *  - one auto-hide delay shared with VOD, never while paused, on an error or with a panel open.
 */
internal object LiveControlsPolicy {

    enum class KeyAction {
        /** Not ours (the focused control, an open panel, the BackHandlers). */
        PASS,

        /** Ours but nothing to run: the KeyUp half of a handled press. */
        CONSUME,

        /** A key while the controls are up: let focus/the control have it, restart the timer. */
        PASS_AND_KEEP_ALIVE,
        SHOW_CONTROLS,
        TOGGLE_PAUSE,
        ZAP_PREVIOUS,
        ZAP_NEXT,
        ZAP_BACK,
        HIDE_CHANNEL,
    }

    private val directional = setOf(
        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
    )
    private val media = setOf(Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause)
    private val channelKeys = setOf(Key.ChannelUp, Key.ChannelDown, Key.LastChannel)

    fun keyAction(
        key: Key,
        isKeyDown: Boolean,
        controlsVisible: Boolean,
        paused: Boolean,
        /** The channel list, a track dialog or a hide notice holds focus: its keys are its own. */
        panelOpen: Boolean,
        /** A held key's auto-repeat: never toggles pause twice. */
        isRepeat: Boolean = false,
    ): KeyAction {
        if (panelOpen) return KeyAction.PASS
        val ours = key in directional || key in media || key in channelKeys || key == Key.Menu
        if (!ours) return KeyAction.PASS
        // Media and channel keys mean the same thing whether or not the controls are up.
        if (key in media || key in channelKeys) {
            if (!isKeyDown || (isRepeat && key in media)) return KeyAction.CONSUME
            return when (key) {
                Key.MediaPlayPause -> KeyAction.TOGGLE_PAUSE
                Key.MediaPlay -> if (paused) KeyAction.TOGGLE_PAUSE else KeyAction.SHOW_CONTROLS
                Key.MediaPause -> if (!paused) KeyAction.TOGGLE_PAUSE else KeyAction.SHOW_CONTROLS
                // CH+ follows the remote's number: the next (higher-numbered) channel.
                Key.ChannelUp -> KeyAction.ZAP_NEXT
                Key.ChannelDown -> KeyAction.ZAP_PREVIOUS
                else -> KeyAction.ZAP_BACK
            }
        }
        if (controlsVisible) {
            // The buttons own the D-pad now; every press keeps them up. MENU is not a control key.
            if (key == Key.Menu) return if (isKeyDown) KeyAction.HIDE_CHANNEL else KeyAction.CONSUME
            return KeyAction.PASS_AND_KEEP_ALIVE
        }
        if (!isKeyDown) return KeyAction.CONSUME
        return when (key) {
            Key.DirectionUp -> KeyAction.ZAP_PREVIOUS
            Key.DirectionDown -> KeyAction.ZAP_NEXT
            Key.Menu -> KeyAction.HIDE_CHANNEL
            // OK, LEFT, RIGHT: bring the controls up; never pause, never scrub.
            else -> KeyAction.SHOW_CONTROLS
        }
    }

    enum class BackAction { CLOSE_PANEL, HIDE_CONTROLS, EXIT }

    fun backAction(panelOpen: Boolean, controlsVisible: Boolean): BackAction = when {
        panelOpen -> BackAction.CLOSE_PANEL
        controlsVisible -> BackAction.HIDE_CONTROLS
        else -> BackAction.EXIT
    }

    fun mayAutoHide(controlsVisible: Boolean, paused: Boolean, failed: Boolean, panelOpen: Boolean): Boolean =
        controlsVisible && !paused && !failed && !panelOpen

    /**
     * T10 (W2 device pass): a failed tune (or a reconnect) on fullscreen live left the picture black
     * with nothing on screen until OK brought the controls up. Trouble reveals the overlay by itself,
     * so its status line ("Can't play" + the reason, or "Reconnecting") is seen at once. An open panel
     * keeps its focus; controls already up stay as they are.
     */
    fun revealForTrouble(failed: Boolean, reconnecting: Boolean, controlsVisible: Boolean, panelOpen: Boolean): Boolean =
        (failed || reconnecting) && !controlsVisible && !panelOpen

    enum class Status { LIVE, PAUSED, TUNING, RECONNECTING, FAILED }

    /** Tuning first (an error may still belong to the channel being left), then failure. */
    fun status(tuning: Boolean, reconnecting: Boolean, paused: Boolean, failed: Boolean): Status = when {
        tuning -> Status.TUNING
        failed -> Status.FAILED
        reconnecting -> Status.RECONNECTING
        paused -> Status.PAUSED
        else -> Status.LIVE
    }

    /** Elapsed share of the airing programme for the non-seekable line; null when unknown. */
    fun programmeProgress(startMs: Long?, endMs: Long?, nowMs: Long): Float? {
        if (startMs == null || endMs == null || startMs <= 0 || endMs <= startMs) return null
        return ((nowMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }

    data class Buttons(
        val retry: Boolean,
        val subtitles: Boolean,
        val audio: Boolean,
        val channelList: Boolean,
        val favourite: Boolean,
    )

    /**
     * Which controls show. Retry only on an error. Subtitles when the stream has any; audio when
     * there is a choice (one track is no choice). The channel list and favourite only where there
     * is a lineup (the guide), not for a one-off stream (a Sports launch).
     */
    fun buttons(failed: Boolean, subtitleTracks: Int, audioTracks: Int, hasLineup: Boolean): Buttons = Buttons(
        retry = failed,
        subtitles = subtitleTracks > 0,
        audio = audioTracks > 1,
        channelList = hasLineup,
        favourite = hasLineup,
    )
}
