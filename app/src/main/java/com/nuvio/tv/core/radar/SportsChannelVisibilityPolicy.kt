package com.nuvio.tv.core.radar

import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamChannel
import com.nuvio.tv.core.iptv.identity.IptvIdentity
import com.nuvio.tv.core.iptv.overlay.IptvHiddenItemsPolicy
import com.nuvio.tv.core.iptv.overlay.OverlaySnapshot

/**
 * TV counterpart of the common matcher policy: what the viewer hid never reaches a result or a probe.
 *
 * Two costs, two stages. Category selection and hidden groups are a set lookup per channel, so they apply to the
 * whole lineup before scoring ([groupVisible]). A hidden single channel is known only by its identity HASH, which
 * costs ~0.2 ms per channel on a 4-core Onn box: hashing a 26k-channel lineup took 4.2 s, per playlist, per game
 * open, and ran the game screen's 6 s lookup out of time before the name pass even started. So a hidden channel is
 * decided only for the few channels that MATCHED ([hiddenChannels]), never for the whole lineup.
 */
internal object SportsChannelVisibilityPolicy {
    fun filter(
        account: XtreamAccount,
        channels: List<XtreamChannel>,
        categories: List<IptvHiddenItemsPolicy.NamedCategory>,
        overlay: OverlaySnapshot,
    ): List<XtreamChannel> {
        val hidden = hiddenChannels(overlay)
        return groupVisible(account, channels, categories, overlay)
            .filterNot { hidden?.invoke(account.id, it.name, it.epgChannelId) == true }
    }

    /** Selection and hidden groups only: cheap per channel, applied to the whole lineup before scoring. */
    fun groupVisible(
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
        val hiddenGroups = IptvHiddenItemsPolicy.hiddenCategoryIds(account.id, XtreamAccount.TYPE_LIVE, categories, overlay.categories)
        return if (hiddenGroups.isEmpty()) selected else selected.filter { it.categoryId == null || it.categoryId !in hiddenGroups }
    }

    /**
     * Whether a channel (playlist id, name, guide id) is hidden by the viewer, or null when nothing is hidden. Ask it
     * only of matched channels: each answer hashes the channel's identity.
     */
    fun hiddenChannels(
        overlay: OverlaySnapshot,
        entityOf: (playlistId: String, name: String, epgChannelId: String?) -> String = IptvIdentity::entityId,
    ): ((String, String, String?) -> Boolean)? {
        if (overlay.channels.values.none { it.hidden }) return null
        return { playlistId, name, epgChannelId -> overlay.channels[entityOf(playlistId, name, epgChannelId)]?.hidden == true }
    }

    fun needsCategoryNames(account: XtreamAccount, overlay: OverlaySnapshot): Boolean =
        overlay.categories.values.any { it.hidden } || !account.categorySelections.live.isNullOrEmpty()
}
