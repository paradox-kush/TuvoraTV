package com.nuvio.tv.core.sports.detail.screen

enum class FocusKey { PRIMARY, CHANNELS, RETRY }

/** Pure focus plan: which control takes focus first, and where focus goes when its control disappears. */
object GameDetailFocus {
    private val order = listOf(FocusKey.PRIMARY, FocusKey.CHANNELS, FocusKey.RETRY)

    fun controls(state: GameDetailUiState, watch: WatchUi): Set<FocusKey> = when (state) {
        is GameDetailUiState.Loaded -> buildSet {
            if (watch.primary != PrimaryAction.None) add(FocusKey.PRIMARY)
            if (watch.showChannels) add(FocusKey.CHANNELS)
        }
        is GameDetailUiState.Failed -> setOf(FocusKey.RETRY)
        else -> emptySet()
    }

    fun initial(state: GameDetailUiState, watch: WatchUi): FocusKey? = repair(null, controls(state, watch))

    /**
     * Where focus should be NOW. Until the viewer moves focus themselves ([userMoved]), the primary action wins
     * the moment it exists (it usually appears after the channel match finished, when Channels already holds
     * focus). Once they have moved, their choice is kept while it exists.
     */
    fun target(current: FocusKey?, userMoved: Boolean, available: Set<FocusKey>): FocusKey? =
        if (!userMoved && FocusKey.PRIMARY in available) FocusKey.PRIMARY else repair(current, available)

    /** Keeps [current] while it exists, else the best remaining control; null only when there is none. */
    fun repair(current: FocusKey?, available: Set<FocusKey>): FocusKey? =
        if (current != null && current in available) current else order.firstOrNull { it in available }
}
