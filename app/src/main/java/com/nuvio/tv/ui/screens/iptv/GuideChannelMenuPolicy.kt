package com.nuvio.tv.ui.screens.iptv

/**
 * F03 — what MENU on a guide row offers. It used to hide the channel straight away, which stays the
 * answer for an ordinary channel (null = hide now, as before). Two places now offer a small menu:
 *
 *  - the Favorites / All favorites views: Move up / Move down (the synced favourites order) and
 *    Remove from Favorites — a hide would do nothing visible there (the viewer's own list);
 *  - a PINNED channel in a provider view: Move up / Move down among the pinned ones (the synced pin
 *    positions) and Hide channel.
 *
 * A move that would leave the list's end is left out. Pure.
 */
object GuideChannelMenuPolicy {

    enum class Action { MOVE_UP, MOVE_DOWN, REMOVE_FAVORITE, HIDE }

    fun menu(
        special: GuideSpecial?,
        isPinned: Boolean,
        indexInList: Int,
        listSize: Int,
    ): List<Action>? {
        val favouritesView = special == GuideSpecial.FAVORITES || special == GuideSpecial.ALL_FAVORITES
        val pinnedInProviderView = isPinned && (special == null || special == GuideSpecial.ALL)
        if (!favouritesView && !pinnedInProviderView) return null
        return buildList {
            if (indexInList > 0) add(Action.MOVE_UP)
            if (indexInList in 0 until listSize - 1) add(Action.MOVE_DOWN)
            add(if (favouritesView) Action.REMOVE_FAVORITE else Action.HIDE)
        }
    }
}
