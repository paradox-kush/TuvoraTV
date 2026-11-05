package com.nuvio.tv.ui.screens.player.clean.live

import androidx.compose.ui.input.key.Key
import com.nuvio.tv.core.contracts.LivePausePhase

/**
 * F28: every decision of the TV live overlay, in one place, without Compose.
 *
 * One overlay serves the guide's fullscreen and the clean live player, built from the VOD player's
 * own pieces. The owner's rules (2026-10-03) are encoded here and nowhere else:
 *  - OK with the controls hidden SHOWS them (never play/pause), so zapping can't pause by accident;
 *  - UP/DOWN with the controls hidden change channel; guide LEFT opens its channel browser.
 *    Other directions reveal the controls and then move
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
        OPEN_CHANNEL_MENU,
        OPEN_CHANNEL_LIST,

        /** Time-shifted with the controls hidden: LEFT / RIGHT scrub inside the saved window. */
        SCRUB_BACK,
        SCRUB_FORWARD,
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
        hasLineup: Boolean = false,
        /**
         * The controls on screen were brought up by a zap and nothing else has touched them since:
         * UP/DOWN keep zapping instead of reaching the buttons, whose DOWN hides the controls —
         * that hid the channel name mid-zap and left only a spinner.
         */
        zapReveal: Boolean = false,
        /** Time-shifted (live pause / rewind): the saved window can be scrubbed. */
        scrubbable: Boolean = false,
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
        if (scrubbable && !controlsVisible && (key == Key.DirectionLeft || key == Key.DirectionRight)) {
            if (!isKeyDown) return KeyAction.CONSUME
            return if (key == Key.DirectionLeft) KeyAction.SCRUB_BACK else KeyAction.SCRUB_FORWARD
        }
        if (key == Key.DirectionLeft && hasLineup && !controlsVisible) {
            return if (isKeyDown && !isRepeat) KeyAction.OPEN_CHANNEL_LIST else KeyAction.CONSUME
        }
        if (controlsVisible && zapReveal && (key == Key.DirectionUp || key == Key.DirectionDown)) {
            if (!isKeyDown) return KeyAction.CONSUME
            return if (key == Key.DirectionUp) KeyAction.ZAP_PREVIOUS else KeyAction.ZAP_NEXT
        }
        if (controlsVisible) {
            // The buttons own the D-pad now; every press keeps them up. MENU is not a control key.
            if (key == Key.Menu) return if (isKeyDown && !isRepeat) KeyAction.OPEN_CHANNEL_MENU else KeyAction.CONSUME
            return KeyAction.PASS_AND_KEEP_ALIVE
        }
        if (!isKeyDown) return KeyAction.CONSUME
        return when (key) {
            Key.DirectionUp -> KeyAction.ZAP_PREVIOUS
            Key.DirectionDown -> KeyAction.ZAP_NEXT
            Key.Menu -> if (isRepeat) KeyAction.CONSUME else KeyAction.OPEN_CHANNEL_MENU
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

    /** Never while a channel is still tuning: its name and "Tuning" are what the viewer is waiting on. */
    fun mayAutoHide(
        controlsVisible: Boolean,
        paused: Boolean,
        failed: Boolean,
        panelOpen: Boolean,
        tuning: Boolean = false,
    ): Boolean = controlsVisible && !paused && !failed && !panelOpen && !tuning

    /**
     * T10 (W2 device pass): a failed tune (or a reconnect) on fullscreen live left the picture black
     * with nothing on screen until OK brought the controls up. Trouble reveals the overlay by itself,
     * so its status line ("Can't play" + the reason, or "Reconnecting") is seen at once. An open panel
     * keeps its focus; controls already up stay as they are.
     */
    fun revealForTrouble(failed: Boolean, reconnecting: Boolean, controlsVisible: Boolean, panelOpen: Boolean): Boolean =
        (failed || reconnecting) && !controlsVisible && !panelOpen

    enum class Status { LIVE, PAUSED, TUNING, RECONNECTING, FAILED, PREPARING, SHIFTED }

    /** Live pause / rewind takes over the badge while it is in play; otherwise the base status stands. */
    fun withLivePause(base: Status, phase: LivePausePhase, paused: Boolean): Status = when (phase) {
        LivePausePhase.OFF -> base
        LivePausePhase.PREPARING -> Status.PREPARING
        LivePausePhase.SHIFTED -> if (paused) Status.PAUSED else Status.SHIFTED
        LivePausePhase.FAILED -> Status.PAUSED
    }

    enum class ScrubDirection { BACK, FORWARD }

    /** 10 s a press; a held key speeds up (30 s, then 60 s). */
    fun scrubStepMs(repeatCount: Int): Long = when {
        repeatCount >= 8 -> 60_000L
        repeatCount >= 3 -> 30_000L
        else -> 10_000L
    }

    /** Where a scrub lands: always inside the saved window. */
    fun scrubTarget(positionMs: Long, windowStartMs: Long, windowEndMs: Long, direction: ScrubDirection, stepMs: Long): Long {
        val raw = if (direction == ScrubDirection.BACK) positionMs - stepMs else positionMs + stepMs
        return raw.coerceIn(windowStartMs, maxOf(windowStartMs, windowEndMs))
    }

    /** "−03:12" (or "−1:03:12" past an hour): how far behind live the viewer is. */
    fun behindLabel(behindMs: Long): String {
        val total = (behindMs.coerceAtLeast(0L) + 500L) / 1000L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val ss = s.toString().padStart(2, '0')
        return if (h > 0) "\u2212$h:${m.toString().padStart(2, '0')}:$ss" else "\u2212${m.toString().padStart(2, '0')}:$ss"
    }

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
     * Zapping retunes every press, and each stream reports its own tracks a moment later, so the
     * subtitle/audio buttons would pop in and out and shove the row around under the viewer's
     * thumb. While tuning, keep what the last settled stream offered; everything else (Retry above
     * all) follows reality.
     */
    fun holdWhileTuning(settled: Buttons?, current: Buttons, tuning: Boolean): Buttons =
        if (!tuning || settled == null) current
        else current.copy(subtitles = settled.subtitles, audio = settled.audio)

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
