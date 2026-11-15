package com.nuvio.tv.ui.screens.iptv

/**
 * Change 4 of the IPTV redesign (2026-11-15, 05-fullscreen, A3): UP / DOWN keep zapping in full screen (the owner's
 * 2026-10-03 rule), now with a compact banner instead of the control bar. A playlist whose links are single-use
 * (Stalker) SURFS instead: UP / DOWN only aim, the banner names the aimed channel over the one still playing, and the
 * tune happens on OK or after [SURF_COMMIT_MS] — one tune per decision instead of one per press. Pure.
 */
internal object LiveZapModePolicy {
    enum class Mode {
        /** Xtream / M3U: each settled press tunes (the existing settle debounce). */
        SWITCH,

        /** Single-use links: aim, then OK or the countdown tunes. */
        SURF,
    }

    /** A3 default: surf for single-use-link playlists only. Flip to false to switch at once everywhere. */
    const val SURF_FOR_SINGLE_USE_LINKS = true

    /** How long an aim rests before it tunes by itself (05: "auto-tune after 1.5 s"). */
    const val SURF_COMMIT_MS = 1_500L

    /** How long the zap banner stays up after the last zap (05: "goes away by itself after 4 s"). */
    const val BANNER_MS = 4_000L

    fun mode(singleUseLinks: Boolean, surfForSingleUse: Boolean = SURF_FOR_SINGLE_USE_LINKS): Mode =
        if (singleUseLinks && surfForSingleUse) Mode.SURF else Mode.SWITCH

    /** What OK does in full screen with the controls hidden. */
    enum class OkAction { TUNE_AIM_NOW, SHOW_CONTROLS }

    /** During a surf countdown OK tunes the aim at once; otherwise it brings the control bar up (never pauses). */
    fun okAction(surfAim: String?, playing: String?): OkAction =
        if (surfAim != null && surfAim != playing) OkAction.TUNE_AIM_NOW else OkAction.SHOW_CONTROLS

    /** Back during a surf countdown cancels the aim (nothing tunes); otherwise it is the usual Back. */
    fun backCancelsSurf(surfAim: String?, playing: String?): Boolean = surfAim != null && surfAim != playing

    /** The banner shows on a zap only while the control bar is down; with the bar up the bar names the channel. */
    fun showsBanner(controlsVisible: Boolean): Boolean = !controlsVisible
}
