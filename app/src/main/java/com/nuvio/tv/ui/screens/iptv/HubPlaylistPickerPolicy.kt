package com.nuvio.tv.ui.screens.iptv

/**
 * Sim pass 2026-11-16 (UX): the hub's playlist picker opened with focus on "Manage playlists", so the playlist in use
 * (marked "Current") was one press away and a stray OK left the hub. It opens on the current playlist. Pure.
 */
internal object HubPlaylistPickerPolicy {
    /** The playlist row (index into [accountIds]) focus opens on, or null for the first row ("Manage playlists"). */
    fun focusIndex(accountIds: List<String>, selectedId: String?): Int? =
        selectedId?.let { accountIds.indexOf(it) }?.takeIf { it >= 0 }
}
