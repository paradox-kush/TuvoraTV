package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.contracts.PlaybackResumeOffer

/**
 * What the player does with the position another app left on the server (media-servers design D3, owner decisions
 * 2026-10-06). The SOURCE decides whether its position is worth using (newer, far enough from Tuvora's, not nearly
 * finished - and whether Tuvora has a record of its own at all, which makes it [PlaybackResumeOffer.autoStart]); this
 * only maps that onto the player:
 *  - nothing local -> [Decision.AutoResume] (the server's place IS the place, no question asked);
 *  - a local record that differs -> [Decision.Offer] ("Continue at hh:mm?", a choice - never applied silently);
 *  - no offer -> [Decision.None] (Tuvora's own record resumes as usual).
 */
internal object ServerResumePolicy {
    sealed interface Decision {
        data object None : Decision
        data class AutoResume(val positionMs: Long) : Decision
        data class Offer(val positionMs: Long) : Decision
    }

    fun decide(offer: PlaybackResumeOffer?): Decision {
        val position = offer?.positionMs?.takeIf { it > 0L } ?: return Decision.None
        return if (offer.autoStart) Decision.AutoResume(position) else Decision.Offer(position)
    }
}
