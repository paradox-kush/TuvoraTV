package com.nuvio.tv.ui.screens.iptv

/** Which empty-state message the live guide shows for an empty channel list. */
internal object GuideEmptyStatePolicy {

    enum class Kind {
        /** Empty Favorites: teach the gesture that fills it ("Hold OK on a channel…"). */
        FAVORITES_HINT,

        /** Any other empty view: the generic "No channels here". */
        NO_CHANNELS,
    }

    /** UX13: only the Favorites view gets the how-to; [special] is the selected category's kind. */
    fun kind(special: GuideSpecial?): Kind =
        if (special == GuideSpecial.FAVORITES) Kind.FAVORITES_HINT else Kind.NO_CHANNELS
}
