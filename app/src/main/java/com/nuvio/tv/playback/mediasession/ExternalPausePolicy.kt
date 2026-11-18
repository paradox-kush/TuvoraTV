package com.nuvio.tv.playback.mediasession

/**
 * Which pauses reaching the media session are the viewer's, and which are the platform's echo of OUR display switch.
 *
 * The primary fix is upstream: playback is held still across the switch (PlaybackOutputHold) so the HDMI re-sync
 * happens while nothing plays. This is only the backstop for the pause the system sends anyway (Fire OS pauses the
 * session when the HDMI audio sink drops, ~1.2 s after the mode request on an AFTKM). Amazon requires apps to
 * pause on a real HDMI disconnect (developer.amazon.com/docs/fire-tv/handling-hdmi-events.html), so the window is
 * short and anchored to the switch's COMPLETION, and a removed non-HDMI output (headphones, Bluetooth) always wins.
 *
 * The viewer's own pause (overlay, remote key) reaches the session controller directly, never through here.
 */
internal object ExternalPausePolicy {
    /** After the display confirmed the new mode (and the hold settled): the last echoes. */
    const val BACKSTOP_WINDOW_MS = 3_500L

    /** A non-HDMI output removal this recent is a real "becoming noisy": it pauses. */
    const val NON_HDMI_REMOVAL_WINDOW_MS = 5_000L

    /** HDMI sink types (AudioDeviceInfo TYPE_HDMI = 9, TYPE_HDMI_ARC = 10, TYPE_HDMI_EARC = 29): the display's own. */
    fun isHdmiOutput(audioDeviceType: Int): Boolean = audioDeviceType == 9 || audioDeviceType == 10 || audioDeviceType == 29

    data class SwitchState(
        val inProgress: Boolean = false,
        val completedAtMs: Long? = null,
        val nonHdmiOutputRemovedAtMs: Long? = null,
    )

    fun isDisplaySwitchPause(state: SwitchState, nowMs: Long): Boolean {
        val removed = state.nonHdmiOutputRemovedAtMs?.let { nowMs - it }
        if (removed != null && removed in 0..NON_HDMI_REMOVAL_WINDOW_MS) return false
        if (state.inProgress) return true
        val sinceCompletion = state.completedAtMs?.let { nowMs - it } ?: return false
        return sinceCompletion in 0..BACKSTOP_WINDOW_MS
    }
}

/** What the output controller (writer) and the audio-device watcher tell the media session (reader). */
internal object DisplaySwitchLedger {
    @Volatile private var inProgress = false
    @Volatile private var completedAtMs: Long? = null
    @Volatile private var nonHdmiRemovedAtMs: Long? = null

    fun switchStarted() { inProgress = true }

    fun switchCompleted(nowMs: Long) {
        completedAtMs = nowMs
        inProgress = false
    }

    fun nonHdmiOutputRemoved(nowMs: Long) { nonHdmiRemovedAtMs = nowMs }

    fun state() = ExternalPausePolicy.SwitchState(inProgress, completedAtMs, nonHdmiRemovedAtMs)
}
