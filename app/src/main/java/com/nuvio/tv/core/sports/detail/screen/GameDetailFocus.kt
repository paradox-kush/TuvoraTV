package com.nuvio.tv.core.sports.detail.screen

enum class FocusKey { PRIMARY, CHANNELS, RETRY }

/** Pure focus plan: which control takes focus first, and where focus goes when its control disappears. */
object GameDetailFocus {
    private val order = listOf(FocusKey.PRIMARY, FocusKey.CHANNELS, FocusKey.RETRY)

    fun controls(state: GameDetailUiState, watch: WatchUi): Set<FocusKey> = when (state) {
        is GameDetailUiState.Loaded -> buildSet {
            if (watch.primary != PrimaryAction.None) add(FocusKey.PRIMARY)
            add(FocusKey.CHANNELS)
        }
        is GameDetailUiState.Failed -> setOf(FocusKey.RETRY)
        else -> emptySet()
    }

    fun initial(state: GameDetailUiState, watch: WatchUi): FocusKey? = repair(null, controls(state, watch))

    /** Keeps [current] while it exists, else the best remaining control; null only when there is none. */
    fun repair(current: FocusKey?, available: Set<FocusKey>): FocusKey? =
        if (current != null && current in available) current else order.firstOrNull { it in available }
}
