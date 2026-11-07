package com.nuvio.tv.core.sports.detail.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameDetailFocusTest {
    private val play = WatchUi(WatchMode.BROWSING, null, PrimaryAction.Play(WatchCandidate("a", "A", 0, 1)), WatchStatus.READY, 0)
    private val none = play.copy(primary = PrimaryAction.None, status = WatchStatus.ALREADY_ON_IT)

    @Test fun `initial focus is the primary action`() =
        assertEquals("primary", FocusKey.PRIMARY, GameDetailFocus.initial(GameDetailUiState.NoData.let { loaded() }, play))
    @Test fun `with no primary action focus lands on Channels`() =
        assertEquals("channels", FocusKey.CHANNELS, GameDetailFocus.initial(loaded(), none))
    @Test fun `a failed load focuses Retry`() =
        assertEquals("retry", FocusKey.RETRY, GameDetailFocus.initial(GameDetailUiState.Failed(false), none))
    @Test fun `loading and no-data have nothing to focus`() {
        assertNull("loading", GameDetailFocus.initial(GameDetailUiReducer.initial(0), none))
        assertNull("nodata", GameDetailFocus.initial(GameDetailUiState.NoData, none))
    }
    @Test fun `focus is kept while its control still exists`() =
        assertEquals("kept", FocusKey.CHANNELS, GameDetailFocus.repair(FocusKey.CHANNELS, setOf(FocusKey.PRIMARY, FocusKey.CHANNELS)))
    @Test fun `when the focused control disappears focus moves to the next best not to nowhere`() {
        assertEquals("primary gone", FocusKey.CHANNELS, GameDetailFocus.repair(FocusKey.PRIMARY, setOf(FocusKey.CHANNELS)))
        assertEquals("retry gone", FocusKey.PRIMARY, GameDetailFocus.repair(FocusKey.RETRY, setOf(FocusKey.PRIMARY, FocusKey.CHANNELS)))
    }
    @Test fun `nothing focused yet takes the best available`() =
        assertEquals("best", FocusKey.PRIMARY, GameDetailFocus.repair(null, setOf(FocusKey.CHANNELS, FocusKey.PRIMARY)))
    @Test fun `no controls means no focus`() = assertNull("none", GameDetailFocus.repair(FocusKey.PRIMARY, emptySet()))
    @Test fun `a hidden Channels button never holds focus`() {
        val noChannels = play.copy(primary = PrimaryAction.Rematch(false), status = WatchStatus.NO_CHANNELS)
        assertEquals("only the primary", setOf(FocusKey.PRIMARY), GameDetailFocus.controls(loaded(), noChannels))
        assertEquals("focus lands on find channels", FocusKey.PRIMARY, GameDetailFocus.initial(loaded(), noChannels))
    }
    @Test fun `controls present follow the watch action`() {
        assertEquals("with primary", setOf(FocusKey.PRIMARY, FocusKey.CHANNELS), GameDetailFocus.controls(loaded(), play))
        assertEquals("without", setOf(FocusKey.CHANNELS), GameDetailFocus.controls(loaded(), none))
        assertEquals("failed", setOf(FocusKey.RETRY), GameDetailFocus.controls(GameDetailUiState.Failed(true), none))
    }

    private fun loaded() = GameDetailUiState.Loaded(
        com.nuvio.tv.core.sports.detail.GameDetail.skeleton(com.nuvio.tv.core.sports.detail.GameRef("tsdb:1", "1", "A", "B", 0L)),
    )
}
