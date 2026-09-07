package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.overlay.ChannelOverlay
import com.nuvio.tv.core.iptv.overlay.IptvChannelOverlayPolicy

/**
 * Pure decision for the Live guide's default "All channels" browse view: apply the personalization
 * channel overlay (pin-float, hide-drop, reorder, rename) to the FULL provider list, THEN cap.
 *
 * Capping first — the shipped bug — dropped a channel pinned past the cap before its pin could float
 * it to the front, so a pin set on the website never appeared on TV for large providers (the pinned
 * channel sat past ALL_CAP = 600). Overlay-then-cap floats it into the visible window.
 *
 * Generic over the row type: the caller supplies the durable [entityId] (the overlay's key) and an
 * optional [withName] for rename. No DB / network / identity call here (house pattern:
 * IptvChannelOverlayPolicy, RadarLiveRefreshPolicy), so it unit-tests in isolation.
 */
object GuideAllChannelsCapPolicy {

    fun <T> capped(
        channels: List<T>,
        overlay: Map<String, ChannelOverlay>,
        cap: Int,
        entityId: (T) -> String,
        withName: (T, newName: String) -> T = { r, _ -> r },
    ): List<T> {
        // No personalization: keep provider order, cap. (Also skips tagging the whole catalog.)
        if (overlay.isEmpty()) return channels.take(cap)
        // Apply the overlay to the FULL list (pin-float, hide-drop, reorder, rename) THEN cap, so a
        // channel pinned past the cap floats into the visible window instead of being dropped first.
        val tagged = channels.mapIndexed { i, c -> IptvChannelOverlayPolicy.Tagged(entityId(c), i, c) }
        return IptvChannelOverlayPolicy.displayed(tagged, overlay, honorOrder = true, withName = withName)
            .take(cap)
    }
}
