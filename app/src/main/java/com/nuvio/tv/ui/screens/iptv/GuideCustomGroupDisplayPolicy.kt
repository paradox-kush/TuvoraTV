package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.overlay.ChannelOverlay
import com.nuvio.tv.core.iptv.overlay.IptvChannelOverlayPolicy

/** Custom groups keep their member order while sharing channel names and hides with the guide. */
internal object GuideCustomGroupDisplayPolicy {
    fun displayed(channels: List<GuideChannel>, overlay: Map<String, ChannelOverlay>): List<GuideChannel> =
        IptvChannelOverlayPolicy.displayed(
            channels.mapIndexed { index, row -> IptvChannelOverlayPolicy.Tagged(row.entityId, index, row) },
            overlay, honorOrder = false, withName = { row, name -> row.copy(name = name) },
        )

    fun focusAfterRefresh(previousId: String?, channels: List<GuideChannel>): String? =
        previousId?.takeIf { id -> channels.any { it.contentId == id } } ?: channels.firstOrNull()?.contentId
}
