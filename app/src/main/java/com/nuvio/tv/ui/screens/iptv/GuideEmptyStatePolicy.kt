package com.nuvio.tv.ui.screens.iptv

/**
 * Which empty state the live guide shows for an empty channel list, and the ONE action it offers (B11, IPTV redesign
 * 2026-11-15). A screen with no focus target is a dead end on a remote, so every empty view names its cause and holds
 * exactly one focused action. A failure is never drawn here: Failed has its own screen with Try again (BoundedLoad).
 */
internal object GuideEmptyStatePolicy {

    enum class Kind {
        /** Empty Favorites: teach the gesture that fills it. */
        FAVORITES_HINT,

        /** Nothing watched yet in Recent. */
        RECENT_EMPTY,

        /** The Search row before anything was typed. */
        SEARCH_PROMPT,

        /** A search that matched nothing. */
        SEARCH_NO_MATCH,

        /** All channels empty: the playlist itself sent nothing. */
        PLAYLIST_EMPTY,

        /** Any provider or custom group with no channels. */
        NO_CHANNELS,
    }

    /** The one focused action of an empty state. */
    enum class Action {
        /** Show All channels, focus on row 1. */
        BROWSE_ALL,

        /** Open the channel-search keyboard. */
        TYPE_SEARCH,

        /** Drop the query and go back to the group shown before the search. */
        CLEAR_SEARCH,

        /** Open this playlist's settings. */
        PLAYLIST_SETTINGS,

        /** Re-open the group column on the shown group. */
        PICK_GROUP,
    }

    /** UX13 + B11: [special] is the selected category's kind, [searchQuery] the guide's search text. */
    fun kind(special: GuideSpecial?, searchQuery: String = ""): Kind = when (special) {
        GuideSpecial.FAVORITES, GuideSpecial.ALL_FAVORITES -> Kind.FAVORITES_HINT
        GuideSpecial.RECENT -> Kind.RECENT_EMPTY
        GuideSpecial.SEARCH -> if (searchQuery.isBlank()) Kind.SEARCH_PROMPT else Kind.SEARCH_NO_MATCH
        GuideSpecial.ALL -> Kind.PLAYLIST_EMPTY
        GuideSpecial.GROUP, null -> Kind.NO_CHANNELS
    }

    fun action(kind: Kind): Action = when (kind) {
        Kind.FAVORITES_HINT, Kind.RECENT_EMPTY -> Action.BROWSE_ALL
        Kind.SEARCH_PROMPT -> Action.TYPE_SEARCH
        Kind.SEARCH_NO_MATCH -> Action.CLEAR_SEARCH
        Kind.PLAYLIST_EMPTY -> Action.PLAYLIST_SETTINGS
        Kind.NO_CHANNELS -> Action.PICK_GROUP
    }
}

/**
 * B11: the actions of a failed guide load, in focus order. The first one takes the entry focus. When the provider
 * blocked us a retry will not help, so Playlist settings comes first (03-guide-states "Load failed").
 */
internal object GuideFailureActionsPolicy {
    enum class Action { TRY_AGAIN, PLAYLIST_SETTINGS }

    fun actions(blockedByProvider: Boolean): List<Action> =
        if (blockedByProvider) listOf(Action.PLAYLIST_SETTINGS, Action.TRY_AGAIN)
        else listOf(Action.TRY_AGAIN, Action.PLAYLIST_SETTINGS)
}
