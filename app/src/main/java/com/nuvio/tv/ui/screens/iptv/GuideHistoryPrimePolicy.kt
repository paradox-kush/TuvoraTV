package com.nuvio.tv.ui.screens.iptv

/**
 * Which channel's catch-up history to fetch when a lineup has just landed.
 *
 * The first channel is marked focused as the list lands, so the UI's own focus event for it is a
 * no-op in `onChannelFocused` and its history fetch never runs. The caller must therefore fetch it
 * on arrival; this decides whether there is anything to fetch.
 */
object GuideHistoryPrimePolicy {
    fun primeTarget(
        channels: List<GuideChannel>,
        focusedChannelId: String?,
        catchUpSupported: Boolean,
    ): GuideChannel? {
        if (!catchUpSupported || focusedChannelId == null) return null
        return channels.firstOrNull { it.contentId == focusedChannelId && it.streamId > 0 }
    }
}
