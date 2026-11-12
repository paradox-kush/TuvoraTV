package com.nuvio.tv.ui.screens.iptv

/**
 * Where the guide puts its highlight when fullscreen closes. Identities, never an old row number or a
 * channel removed by a catalog update. The channel being WATCHED wins: zapping in fullscreen changes
 * it, and a highlight left on the row you entered from would name one channel while another plays.
 * Then the row you entered from, then the first row.
 */
internal object GuideFocusRestorePolicy {
    fun target(playing: String?, origin: String?, visibleIds: List<String>): String? =
        playing?.takeIf { it in visibleIds } ?: origin?.takeIf { it in visibleIds } ?: visibleIds.firstOrNull()

    /**
     * Where the highlight goes when the listed rows change under it (a hide, an unhide, a catalog
     * update). The focused row stays if it is still listed; a removed row hands focus to the row that
     * slid up into its place, else the nearest one above, else the first. Never left on an identity
     * that is no longer a row: with nothing to aim at the list's own restorer picks an arbitrary one
     * (UX168: ~3 rows up). Null stays null, nothing is focused so nothing is invented.
     */
    fun afterListChange(focused: String?, previousIds: List<String>, currentIds: List<String>): String? {
        if (focused == null) return null
        if (focused in currentIds) return focused
        val at = previousIds.indexOf(focused)
        if (at < 0) return currentIds.firstOrNull()
        val current = currentIds.toSet()
        return previousIds.drop(at + 1).firstOrNull { it in current }
            ?: previousIds.take(at).lastOrNull { it in current }
            ?: currentIds.firstOrNull()
    }
}
