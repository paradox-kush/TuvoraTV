package com.nuvio.tv.playback.mediasession

/**
 * Which pauses reaching the media session are the viewer's, and which are the platform's.
 *
 * Fire OS (AFTKM, device pass 2026-11-18): the live frame-rate / resolution match switches the HDMI mode,
 * the HDMI audio sink drops for a moment, the system broadcasts AUDIO_BECOMING_NOISY and pauses the active
 * session through its MediaController. Nobody pressed anything: the player then sat PAUSED on the first
 * frame of the channel, the controls up, with the viewer left to find Play. A pause that arrives inside the
 * display switch's own window is that echo, not a command.
 *
 * The viewer's own pause (the on-screen Play/Pause, the remote's play/pause key) never travels through this
 * path: it reaches the session controller directly. Only another controller's pause is judged here.
 */
internal object ExternalPausePolicy {
    /** Switch + HDMI re-sync + the system's reaction: ~1.3 s on the AFTKM; the slowest TVs take a few seconds. */
    const val DISPLAY_SWITCH_WINDOW_MS = 8_000L

    /** True when [nowMs] falls inside the window that follows the app's own display-mode request. */
    fun isDisplaySwitchPause(lastModeRequestAtMs: Long?, nowMs: Long): Boolean {
        val sinceRequestMs = lastModeRequestAtMs?.let { nowMs - it } ?: return false
        return sinceRequestMs in 0..DISPLAY_SWITCH_WINDOW_MS
    }
}

/** When the app last asked the display for another mode; the media session reads it, the output controller writes it. */
internal object DisplaySwitchLedger {
    @Volatile
    private var lastRequestAtMs: Long? = null

    fun noteModeRequest(nowMs: Long) {
        lastRequestAtMs = nowMs
    }

    fun lastRequestAtMs(): Long? = lastRequestAtMs
}
