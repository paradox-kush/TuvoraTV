package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream

/**
 * Upstream's "fall back to the next source on failure" re-resolves and replays other sources after a
 * failed start. That is wrong for anything that owns a bounded reconnect of its own: an IPTV / Stalker
 * channel or episode (its link is minted per play and single-use), a catch-up replay, a matched IPTV
 * copy, or any single-use link. Replaying those, or auto-switching to another source behind the user's
 * back, spends the line and re-triggers mint loops. Pure so it tests without the player.
 */
internal object SourceFallbackEligibility {
    fun mayFallBackFrom(
        iptvId: String?,
        isCatchUp: Boolean,
        isOwnSourceLabel: Boolean,
        singleUseLink: Boolean,
    ): Boolean = iptvId == null && !isCatchUp && !isOwnSourceLabel && !singleUseLink

    /** An IPTV copy is never an automatic fallback target: reaching it mints a single-use link. */
    fun mayFallBackTo(stream: Stream, isOwnSourceLabel: (String?) -> Boolean): Boolean =
        stream.deviceSourceKind != "iptv" && !isOwnSourceLabel(stream.addonName)
}
