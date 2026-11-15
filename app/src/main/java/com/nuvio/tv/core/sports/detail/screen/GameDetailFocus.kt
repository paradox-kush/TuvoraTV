package com.nuvio.tv.core.sports.detail.screen

enum class FocusKey {
    PRIMARY, CHANNELS, RETRY, PREVIEW,
    /** The section chips of the right column; one stable key per section so focus survives a refresh by id. */
    SECTION_PLAYS, SECTION_STATS, SECTION_LEADERS, SECTION_LINEUPS, SECTION_STANDINGS;

    val section: GameSection? get() = when (this) {
        SECTION_PLAYS -> GameSection.PLAYS; SECTION_STATS -> GameSection.STATS; SECTION_LEADERS -> GameSection.LEADERS
        SECTION_LINEUPS -> GameSection.LINEUPS; SECTION_STANDINGS -> GameSection.STANDINGS
        else -> null
    }

    companion object {
        fun of(s: GameSection): FocusKey? = when (s) {
            GameSection.OVERVIEW -> null
            GameSection.PLAYS -> SECTION_PLAYS; GameSection.STATS -> SECTION_STATS; GameSection.LEADERS -> SECTION_LEADERS
            GameSection.LINEUPS -> SECTION_LINEUPS; GameSection.STANDINGS -> SECTION_STANDINGS
        }
    }
}

enum class FocusDir { LEFT, RIGHT, UP, DOWN }

/** Pure focus plan: which control takes focus first, and where focus goes when its control disappears. */
object GameDetailFocus {
    private val chips = listOf(FocusKey.SECTION_PLAYS, FocusKey.SECTION_STATS, FocusKey.SECTION_LEADERS, FocusKey.SECTION_LINEUPS, FocusKey.SECTION_STANDINGS)
    private val order = listOf(FocusKey.PRIMARY, FocusKey.CHANNELS) + chips + listOf(FocusKey.PREVIEW, FocusKey.RETRY)

    fun controls(state: GameDetailUiState, watch: WatchUi, preview: PreviewSlot = PreviewSlot.Hidden, sections: List<GameSection> = emptyList()): Set<FocusKey> = when (state) {
        is GameDetailUiState.Loaded -> buildSet {
            if (watch.primary != PrimaryAction.None) add(FocusKey.PRIMARY)
            if (watch.showChannels) add(FocusKey.CHANNELS)
            sections.mapNotNullTo(this) { FocusKey.of(it) }
        }
        is GameDetailUiState.Failed -> setOf(FocusKey.RETRY)
        else -> emptySet()
    }

    fun initial(state: GameDetailUiState, watch: WatchUi, preview: PreviewSlot = PreviewSlot.Hidden): FocusKey? = repair(null, controls(state, watch, preview))

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

    /**
     * D-pad movement over the screen's controls. Rows: PRIMARY, CHANNELS (left column), the section chips (right column).
     * RIGHT: PRIMARY -> CHANNELS -> the selected chip -> the next chips; LEFT reverses. UP from PRIMARY/CHANNELS -> the selected chip;
     * DOWN from any chip -> PRIMARY (else CHANNELS). Missing controls are skipped; null = no move (the system's default applies).
     * [selected] is the chip whose panel is showing (focusing a chip selects it).
     */
    fun move(from: FocusKey, dir: FocusDir, available: Set<FocusKey>, selected: FocusKey?): FocusKey? {
        val shown = chips.filter { it in available }
        val entry = selected?.takeIf { it in shown } ?: shown.firstOrNull()
        val left = listOf(FocusKey.PRIMARY, FocusKey.CHANNELS).filter { it in available }
        return when (dir) {
            FocusDir.RIGHT -> when (from) {
                FocusKey.PRIMARY -> if (FocusKey.CHANNELS in available) FocusKey.CHANNELS else entry
                FocusKey.CHANNELS -> entry
                in chips -> shown.getOrNull(shown.indexOf(from) + 1)
                else -> null
            }
            FocusDir.LEFT -> when (from) {
                FocusKey.CHANNELS -> FocusKey.PRIMARY.takeIf { it in available }
                in chips -> shown.getOrNull(shown.indexOf(from) - 1) ?: left.lastOrNull()
                else -> null
            }
            FocusDir.UP -> if (from == FocusKey.PRIMARY || from == FocusKey.CHANNELS) entry else null
            FocusDir.DOWN -> if (from in chips) left.firstOrNull() else null
        }
    }
}
