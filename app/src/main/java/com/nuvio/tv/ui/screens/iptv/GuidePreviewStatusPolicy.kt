package com.nuvio.tv.ui.screens.iptv

import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.playback.ui.LivePlaybackUiStatusCode

/**
 * What the docked guide preview says about itself. The retry line used to print the enum
 * (`status.name.lowercase()`: "handing off"), nothing said "Tuning" before the first frame, and nothing said which
 * channel was playing once focus moved to another row (UX333, UX356, UX357, UX327).
 */
internal object GuidePreviewStatusPolicy {
    /** The sentence for a non-terminal status; null where the picture needs no caption (paused, stopped, releasing). */
    @StringRes
    fun statusRes(code: LivePlaybackUiStatusCode): Int? = when (code) {
        LivePlaybackUiStatusCode.RESOLVING, LivePlaybackUiStatusCode.STARTING -> R.string.iptv_guide_tuning
        LivePlaybackUiStatusCode.BUFFERING -> R.string.iptv_guide_preview_buffering
        LivePlaybackUiStatusCode.RECONNECTING, LivePlaybackUiStatusCode.RECOVERING -> R.string.iptv_guide_preview_reconnecting
        LivePlaybackUiStatusCode.HANDING_OFF -> R.string.iptv_guide_tuning
        LivePlaybackUiStatusCode.PAUSED, LivePlaybackUiStatusCode.RELEASING, LivePlaybackUiStatusCode.STOPPED -> null
    }

    /**
     * The docked pill: "Tuning…" while a spinner is up, except during a reconnect, which keeps its own words
     * (the attempt line). The reconnect's spinner used to win and the pill read "Tuning…" the whole time.
     */
    @StringRes
    fun pillRes(code: LivePlaybackUiStatusCode?, spinnerVisible: Boolean): Int? {
        val statusRes = code?.let(::statusRes)
        if (statusRes == R.string.iptv_guide_preview_reconnecting) return statusRes
        return if (spinnerVisible || statusRes == R.string.iptv_guide_tuning) R.string.iptv_guide_tuning else statusRes
    }

    sealed interface Label {
        data object None : Label
        data object Tuning : Label
        data class Playing(val name: String) : Label
    }

    /**
     * The caption over the preview. Tuning wins until the first frame; once playing, "Playing: X" appears only
     * while the aim (focus) is on another channel, so the picture is never mistaken for the focused row. A
     * failure is named by its own copy beside the channel, so no caption then.
     */
    fun label(tuning: Boolean, playingName: String?, aimedIsPlaying: Boolean, failed: Boolean): Label = when {
        tuning -> Label.Tuning
        failed -> Label.None
        playingName.isNullOrBlank() -> Label.None
        aimedIsPlaying -> Label.None
        else -> Label.Playing(playingName)
    }

    /** OK on a failed channel retries (GuidePlaybackInteractionPolicy.click); the failure copy says so. */
    fun offersRetryHint(failureShown: Boolean): Boolean = failureShown
}
