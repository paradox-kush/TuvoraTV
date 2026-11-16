package com.nuvio.tv.ui.screens.radar

import com.nuvio.tv.ui.screens.radar.LeagueEntryFocusPolicy.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeagueEntryFocusPolicyTest {
    @Test fun gamesPresent_focusTheFirstGameNotFollow() =
        assertEquals("entry focus must not be on Follow", Target.FIRST_GAME, LeagueEntryFocusPolicy.target(hasGames = true, loaded = true, placed = false))

    @Test fun gamesArriveBeforeLoadedFlag_stillFirstGame() =
        assertEquals("cached fixtures show at once", Target.FIRST_GAME, LeagueEntryFocusPolicy.target(hasGames = true, loaded = false, placed = false))

    @Test fun stillLoading_waitsInsteadOfFocusingFollow() =
        assertNull("no game yet and not loaded: do not fall back to Follow", LeagueEntryFocusPolicy.target(hasGames = false, loaded = false, placed = false))

    @Test fun loadedWithNoGames_followIsTheOnlyTarget() =
        assertEquals("never leave the page focus-less", Target.FOLLOW, LeagueEntryFocusPolicy.target(hasGames = false, loaded = true, placed = false))

    @Test fun alreadyPlaced_neverStealsFocusAgain() {
        assertNull("refresh must not move focus", LeagueEntryFocusPolicy.target(hasGames = true, loaded = true, placed = true))
        assertNull("placed, empty", LeagueEntryFocusPolicy.target(hasGames = false, loaded = true, placed = true))
    }
}
