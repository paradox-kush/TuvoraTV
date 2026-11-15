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
