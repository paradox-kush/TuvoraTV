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

    /** [previous] is the lineup the viewer was in: a removed row hands focus to its neighbour, not row 1. */
    fun focusAfterRefresh(previousId: String?, channels: List<GuideChannel>, previous: List<GuideChannel> = emptyList()): String? =
        previousId?.let {
            GuideFocusRestorePolicy.afterListChange(it, previous.map { c -> c.contentId }, channels.map { c -> c.contentId })
        } ?: channels.firstOrNull()?.contentId
}
