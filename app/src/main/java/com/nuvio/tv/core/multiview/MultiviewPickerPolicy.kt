package com.nuvio.tv.core.multiview

/** The groups the channel picker may open on, fastest-useful first. */
internal enum class PickerGroup { RECENT, FAVORITES, ALL }

/**
 * Where the channel picker opens: the first of Recent, Favorites, All channels that has a channel that is not already on
 * screen, with focus on that channel, so the usual add is OK (open), OK (take the top one).
 */
internal object PickerOpeningPolicy {
    val first: PickerGroup = PickerGroup.RECENT

    sealed interface Step {
        /** Stay on this group; focus the channel at [index], or keep focus on the group when it has none. */
        data class Settle(val index: Int?) : Step

        /** This group has nothing to add: look at [group] next. */
        data class TryNext(val group: PickerGroup) : Step
    }

    fun firstOffScreen(keys: List<String>, onScreen: Set<String>): Int? =
        keys.indexOfFirst { it !in onScreen }.takeIf { it >= 0 }

    fun step(group: PickerGroup, keys: List<String>, onScreen: Set<String>): Step {
        val off = firstOffScreen(keys, onScreen)
        if (off != null) return Step.Settle(off)
        return when (group) {
            PickerGroup.RECENT -> Step.TryNext(PickerGroup.FAVORITES)
            PickerGroup.FAVORITES -> Step.TryNext(PickerGroup.ALL)
            PickerGroup.ALL -> Step.Settle(if (keys.isEmpty()) null else 0)
        }
    }
}

/** Entering a group's channel list (RIGHT or OK on a group, a search, the opening landing): when, and on which row. */
internal object PickerEntryPolicy {
    sealed interface Step {
        /** Nothing was asked for. */
        data object Idle : Step

        /** Asked for, but the group has not committed or its channels are still loading: look again when that changes. */
        data object Wait : Step

        /** The group has no channels: focus stays on the group. */
        data object StayOnGroup : Step

        /** Focus the channel row at [index]. */
        data class Enter(val index: Int) : Step
    }

    /** The row [entryKey] when it is in the list, else the first channel not on screen, else the first one. */
    fun step(
        pendingGroup: String?,
        selectedGroup: String?,
        loading: Boolean,
        keys: List<String>,
        entryKey: String?,
        onScreen: Set<String>,
    ): Step = when {
        pendingGroup == null -> Step.Idle
        selectedGroup != pendingGroup || loading -> Step.Wait
        keys.isEmpty() -> Step.StayOnGroup
        else -> Step.Enter(
            entryKey?.let { key -> keys.indexOf(key) }?.takeIf { it >= 0 }
                ?: PickerOpeningPolicy.firstOffScreen(keys, onScreen)
                ?: 0,
        )
    }
}

/** What the channel column says when it has nothing to list. */
internal enum class PickerEmpty { SEARCH_IDLE, SEARCH_NONE, FAVORITES, RECENT, GROUP, PLAYLIST_ROW }

internal object PickerEmptyPolicy {
    enum class Row { PLAYLIST, SEARCH, OTHER }
    enum class Selected { SEARCH, FAVORITES, RECENT, OTHER }

    /** Null when the column has channels to list (or is loading, or failed): those states have their own look. */
    fun forColumn(row: Row, selected: Selected, searchQuery: String, hasChannels: Boolean): PickerEmpty? = when {
        row == Row.PLAYLIST -> PickerEmpty.PLAYLIST_ROW
        row == Row.SEARCH && searchQuery.isBlank() -> PickerEmpty.SEARCH_IDLE
        hasChannels -> null
        row == Row.SEARCH || selected == Selected.SEARCH ->
            if (searchQuery.isBlank()) PickerEmpty.SEARCH_IDLE else PickerEmpty.SEARCH_NONE
        selected == Selected.FAVORITES -> PickerEmpty.FAVORITES
        selected == Selected.RECENT -> PickerEmpty.RECENT
        else -> PickerEmpty.GROUP
    }
}
