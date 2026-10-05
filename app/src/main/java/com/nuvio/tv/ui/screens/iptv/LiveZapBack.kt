package com.nuvio.tv.ui.screens.iptv

/**
 * F08: the "previous channel" jump (zap-back) for TV live — the cable-box LAST/RECALL key.
 *
 * Follows SETTLED channels, i.e. what actually played, not every row a multi-press zap walked
 * past: zapping through ten channels to reach one leaves the one you started on as "previous".
 * A re-settle on the same channel (a retry, a recovery re-tune) is not a zap and keeps it.
 *
 * Pure and immutable so it tests without the player (house pattern: LivePlaybackFreezePolicy).
 */
internal data class LiveZapBack(val current: String? = null, val previous: String? = null) {

    fun onSettled(contentId: String): LiveZapBack =
        if (contentId == current) this else LiveZapBack(current = contentId, previous = current)

    /**
     * The channel to jump back to, or null when there is none. It must be in the [lineup] on screen:
     * the playback owner refuses a channel outside the published lineup, and a jump that silently
     * does nothing is worse than one that is not offered.
     */
    fun target(lineup: Collection<String>): String? =
        previous?.takeIf { it != current && it in lineup }
}
