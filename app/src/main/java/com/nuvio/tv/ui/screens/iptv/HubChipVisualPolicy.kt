package com.nuvio.tv.ui.screens.iptv

/**
 * The container treatment a hub header control wears.
 *
 * Three roles, three surfaces, deliberately never shared: a D-pad user has to be able to tell
 * "this is the section I am on" from "this is where my remote is pointing" at ten feet. The old
 * header encoded both with Primary — solid when focused, 26% when merely selected — so the two
 * differed only by alpha, and the selected tab read as disabled.
 */
enum class HubChipSurface {
    /** Resting: no container at all, matching the sidebar's idle nav items. */
    None,

    /** The active section while focus is elsewhere: a soft neutral fill, no accent, no border. */
    SoftFill,

    /** Wherever the remote is pointing: a solid accent fill. Outranks selection. */
    AccentFill,
}

/**
 * Focus outranks selection, because the focused control is the one the next key press acts on.
 * A control can be both; it renders as focused.
 */
internal fun hubChipSurface(selected: Boolean, focused: Boolean): HubChipSurface = when {
    focused -> HubChipSurface.AccentFill
    selected -> HubChipSurface.SoftFill
    else -> HubChipSurface.None
}
