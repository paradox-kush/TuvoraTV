package com.nuvio.tv.core.radar

import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamChannel
import com.nuvio.tv.core.iptv.identity.IptvIdentity
import com.nuvio.tv.core.iptv.overlay.IptvHiddenItemsPolicy
import com.nuvio.tv.core.iptv.overlay.OverlaySnapshot

/** TV counterpart of the common matcher policy: visibility is applied before scoring/probes. */
internal object SportsChannelVisibilityPolicy {
    fun filter(
        account: XtreamAccount,
        channels: List<XtreamChannel>,
        categories: List<IptvHiddenItemsPolicy.NamedCategory>,
        overlay: OverlaySnapshot,
    ): List<XtreamChannel> {
        if (!account.availableOnDevice || !account.typeEnabled(XtreamAccount.TYPE_LIVE)) return emptyList()
        val selection = account.categorySelections.live
        if (selection != null && selection.isEmpty()) return emptyList()
        val selected = if (selection == null) channels else {
            val names = categories.associate { it.id to it.name }
            channels.filter { account.allowsCategory(XtreamAccount.TYPE_LIVE, it.categoryId, names[it.categoryId]) }
        }
        return IptvHiddenItemsPolicy.visibleHits(
            items = selected,
            hiddenCategoryIds = IptvHiddenItemsPolicy.hiddenCategoryIds(
                account.id, XtreamAccount.TYPE_LIVE, categories, overlay.categories,
            ),
            channelOverlay = overlay.channels,
            categoryOf = { it.categoryId },
            entityOf = { IptvIdentity.entityId(account.id, it.name, it.epgChannelId) },
        )
    }

    fun needsCategoryNames(account: XtreamAccount, overlay: OverlaySnapshot): Boolean =
        overlay.categories.values.any { it.hidden } || !account.categorySelections.live.isNullOrEmpty()
}
