package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.Availability
import com.nuvio.tv.core.sports.detail.BlockType
import com.nuvio.tv.core.sports.detail.DetailLoad
import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.GameDetail
import com.nuvio.tv.core.sports.detail.GameRef
import com.nuvio.tv.core.sports.detail.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDetailUiReducerTest {
    private val ref = GameRef("tsdb:1", "4387", "A", "B", 0L)
    private val full = GameDetail.skeleton(ref).copy(
        blocks = listOf(GameBlock.WinProbability(SourceId.ESPN, 60.0)), availability = Availability.FULL,
    )
    private val sheetOnly = GameDetail.skeleton(ref)
    private val loading = GameDetailUiReducer.initial(1_000)

    @Test fun `starts loading with a deadline`() {
        assertTrue("loading", loading is GameDetailUiState.Loading)
        assertEquals("deadline", 1_000 + GameDetailUiReducer.LOAD_DEADLINE_MS, (loading as GameDetailUiState.Loading).deadlineAtMs)
    }
    @Test fun `loaded with blocks shows the screen`() =
        assertEquals("loaded", GameDetailUiState.Loaded(full), GameDetailUiReducer.onLoad(loading, DetailLoad.Loaded(full), 2_000))
    @Test fun `a match sheet with no blocks falls back to the existing match sheet`() =
        assertEquals("no data", GameDetailUiState.NoData, GameDetailUiReducer.onLoad(loading, DetailLoad.Loaded(sheetOnly), 2_000))
    @Test fun `empty means no data when nothing was shown`() =
        assertEquals("no data", GameDetailUiState.NoData, GameDetailUiReducer.onLoad(loading, DetailLoad.Empty, 2_000))
    @Test fun `failure with nothing shown is Failed with retry never NoData`() =
        assertEquals("failed", GameDetailUiState.Failed(timedOut = false), GameDetailUiReducer.onLoad(loading, DetailLoad.Failed(), 2_000))
    @Test fun `a failed tick keeps the last good detail and marks it stale`() {
        val shown = GameDetailUiState.Loaded(full)
        assertEquals("stale", GameDetailUiState.Loaded(full, stale = true), GameDetailUiReducer.onLoad(shown, DetailLoad.Failed(), 9_000))
    }
    @Test fun `a later good tick clears stale`() =
        assertEquals("fresh", GameDetailUiState.Loaded(full), GameDetailUiReducer.onLoad(GameDetailUiState.Loaded(full, stale = true), DetailLoad.Loaded(full), 9_000))
    @Test fun `an empty answer after a good detail keeps showing it`() {
        val shown = GameDetailUiState.Loaded(full)
        assertEquals("kept", shown, GameDetailUiReducer.onLoad(shown, DetailLoad.Empty, 9_000))
    }
    @Test fun `deadline ends a stuck load as timed out`() {
        val ended = GameDetailUiReducer.onDeadline(loading, 1_000 + GameDetailUiReducer.LOAD_DEADLINE_MS)
        assertEquals("timed out", GameDetailUiState.Failed(timedOut = true), ended)
    }
    @Test fun `deadline before it expires changes nothing`() =
        assertSame("same", loading, GameDetailUiReducer.onDeadline(loading, 2_000))
    @Test fun `deadline never touches a loaded screen`() {
        val shown = GameDetailUiState.Loaded(full)
        assertSame("same", shown, GameDetailUiReducer.onDeadline(shown, 99_999_999))
    }
    @Test fun `work that finishes after the deadline still replaces Failed`() {
        val timedOut = GameDetailUiState.Failed(timedOut = true)
        assertEquals("loaded", GameDetailUiState.Loaded(full), GameDetailUiReducer.onLoad(timedOut, DetailLoad.Loaded(full), 50_000))
    }
    @Test fun `retry restarts loading with a fresh deadline`() {
        val r = GameDetailUiReducer.retry(5_000) as GameDetailUiState.Loading
        assertEquals("deadline", 5_000 + GameDetailUiReducer.LOAD_DEADLINE_MS, r.deadlineAtMs)
    }
    @Test fun `a block-less final game with only a header is still no data`() {
        val fin = sheetOnly.copy(state = com.nuvio.tv.core.sports.detail.GameState.FINAL)
        assertEquals("no data", GameDetailUiState.NoData, GameDetailUiReducer.onLoad(loading, DetailLoad.Loaded(fin), 1))
        assertTrue("type guard", BlockType.WIN_PROBABILITY in full.blocks.map { it.type })
    }
}
