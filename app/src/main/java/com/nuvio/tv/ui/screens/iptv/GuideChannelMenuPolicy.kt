package com.nuvio.tv.ui.screens.iptv

/** Channel actions are always explicit; opening a menu never changes the lineup. */
object GuideChannelMenuPolicy {

    enum class Action { MOVE_UP, MOVE_DOWN, REMOVE_FAVORITE, HIDE }

    /**
     * Whether focus goes back to the channel's row once the menu closes. A move keeps the row; an
     * action that removes it hands focus to its Undo notice instead (UX168: aiming the restore at the
     * row being hidden took focus from Undo, then dropped it on the Live TV tab when the row left).
     */
    fun focusReturnsToRow(action: Action): Boolean = when (action) {
        Action.MOVE_UP, Action.MOVE_DOWN -> true
        Action.REMOVE_FAVORITE, Action.HIDE -> false
    }

    fun menu(
        special: GuideSpecial?,
        isPinned: Boolean,
        indexInList: Int,
        listSize: Int,
    ): List<Action> {
        val favouritesView = special == GuideSpecial.FAVORITES || special == GuideSpecial.ALL_FAVORITES
        val pinnedInProviderView = isPinned && (special == null || special == GuideSpecial.ALL)
        if (!favouritesView && !pinnedInProviderView) return listOf(Action.HIDE)
        return buildList {
            if (indexInList > 0) add(Action.MOVE_UP)
            if (indexInList in 0 until listSize - 1) add(Action.MOVE_DOWN)
            add(if (favouritesView) Action.REMOVE_FAVORITE else Action.HIDE)
        }
    }
}
