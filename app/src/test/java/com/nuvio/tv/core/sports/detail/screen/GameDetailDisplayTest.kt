package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.Availability
import com.nuvio.tv.core.sports.detail.BlockType
import com.nuvio.tv.core.sports.detail.DetailLoad
import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.GameDetail
import com.nuvio.tv.core.sports.detail.GameHeader
import com.nuvio.tv.core.sports.detail.GameRef
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.Side
import com.nuvio.tv.core.sports.detail.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The load reducer runs on the RAW detail; the spoiler render only replaces what is displayed. */
class GameDetailDisplayTest {
    private val ref = GameRef("tsdb:1", "4387", "A", "B", 0L)
    // A live game whose only blocks are spoiler blocks and whose availability is not FULL: the scrub empties it.
    private val raw = GameDetail.skeleton(ref).copy(
        state = GameState.LIVE,
        header = GameHeader.Teams(Side("A", score = "2"), Side("B", score = "1")),
        blocks = listOf(GameBlock.Stats(SourceId.ESPN, emptyList())),
        availability = Availability.MATCH_SHEET,
    )
    private val scrubbed = GameSpoilerFilter.scrub(raw)
    private val loading = GameDetailUiReducer.initial(0L)

    @Test
    fun `a scrubbed model with no blocks is not NoData`() {
        assertTrue("precondition: scrub empties the blocks", scrubbed.blocks.isEmpty())
        val rawState = GameDetailUiReducer.onLoad(loading, DetailLoad.Loaded(raw), 1L)
        assertEquals("raw is Loaded", GameDetailUiState.Loaded(raw), rawState)
        val shown = GameDetailUiReducer.display(rawState, scrubbed)
        assertEquals("scrubbed model shown, with the reveal chip route", GameDetailUiState.Loaded(scrubbed), shown)
    }

    @Test
    fun `hide arriving by sync replaces a Loaded screen that showed numbers`() {
        val rawState = GameDetailUiState.Loaded(raw)
        val shown = GameDetailUiReducer.display(rawState, scrubbed) as GameDetailUiState.Loaded
        assertEquals("numbers are gone", null, (shown.detail.header as GameHeader.Teams).home.score)
    }

    @Test
    fun `a timer re-render does not clear stale`() {
        val rawState = GameDetailUiState.Loaded(raw, stale = true)
        val shown = GameDetailUiReducer.display(rawState, scrubbed)
        assertEquals("still stale", GameDetailUiState.Loaded(scrubbed, stale = true), shown)
    }

    @Test
    fun `before any load the display stays Loading`() {
        assertEquals("loading", loading, GameDetailUiReducer.display(loading, null))
    }
}
