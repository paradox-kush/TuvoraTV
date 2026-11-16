package com.nuvio.tv.ui.screens.radar

/**
 * Where D-pad focus lands when the league page opens. The Follow button used to take it, so a stray OK
 * right after opening a league unfollowed it. Focus goes to the first game card instead (Follow stays one
 * D-pad Up away); only a league with no games falls back to Follow, so the page is never focus-less.
 *
 * Fixtures load on demand, so the decision is re-asked as they arrive and fires once ([placed]): later
 * refreshes never pull focus away from where the user has moved it.
 */
internal object LeagueEntryFocusPolicy {
    enum class Target { FIRST_GAME, FOLLOW }

    /** Null = nothing to do now (already placed, or still waiting for fixtures to load). */
    fun target(hasGames: Boolean, loaded: Boolean, placed: Boolean): Target? = when {
        placed -> null
        hasGames -> Target.FIRST_GAME
        loaded -> Target.FOLLOW
        else -> null
    }
}
