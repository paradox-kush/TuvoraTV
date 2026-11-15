package com.nuvio.tv.ui.screens.iptv

/** What the zap banner's heading says. Pure. */
internal object GuideZapBannerPolicy {
    enum class Heading {
        /** The channel's name: the picture is (or is becoming) this channel. */
        NAME,
        /** "Switch to <aim>?": a surf aim waits for OK or the countdown while the old channel still plays. */
        SWITCH_TO,
    }

    /**
     * Device pass 2, D4: while a surf aim waits, the heading asks "Switch to <aim>?" so the pending target is
     * unmistakable (the name alone read as if the picture had already changed).
     */
    fun heading(surfing: Boolean): Heading = if (surfing) Heading.SWITCH_TO else Heading.NAME

    /** "Still watching <playing>" sits right under the heading (true) rather than down by the countdown. */
    fun stillWatchingUnderHeading(surfing: Boolean): Boolean = surfing
}
