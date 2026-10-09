package com.nuvio.tv.core.build

/** The hint an empty screen gives when no add-on supplies its content (twin of Mobile's HomeNoAddonsCardPolicy). */
enum class NoAddonsHint {
    /** Full builds: point at add-ons. */
    INSTALL_ADDONS,

    /** Store builds hide add-ons, so point at IPTV setup. */
    ADD_IPTV_PLAYLIST,

    /** Store builds with a playlist already: asking for one is wrong (UX38). */
    PLAYLIST_PRESENT,
}

object NoAddonsHintPolicy {
    fun hint(addonsEnabled: Boolean, hasAnyIptvPlaylist: Boolean): NoAddonsHint =
        when {
            addonsEnabled -> NoAddonsHint.INSTALL_ADDONS
            hasAnyIptvPlaylist -> NoAddonsHint.PLAYLIST_PRESENT
            else -> NoAddonsHint.ADD_IPTV_PLAYLIST
        }
}
