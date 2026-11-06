package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.contracts.LivePausePhase

/** The guide player's decisions about live pause / rewind, without Compose or the player. */
internal object GuideLivePausePolicy {
    /**
     * Back from fullscreen to the docked preview. The preview has no controls to resume or scrub a
     * saved part, so a time-shifted viewer returns to the live edge instead of leaving a frozen frame.
     */
    fun goLiveOnCollapse(phase: LivePausePhase): Boolean = phase != LivePausePhase.OFF

    /** Retry on the overlay belongs to live pause only when live pause is what failed. */
    fun retryGoesToPause(phase: LivePausePhase): Boolean = phase == LivePausePhase.FAILED
}
