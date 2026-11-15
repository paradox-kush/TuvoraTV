package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.multiview.TileFailure
import com.nuvio.tv.core.multiview.TileOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BoundedLoadTileBoundsTest {
    private val events = ArrayList<Pair<String, Map<String, Any>>>()
    private val realSink = BoundedLoad.sink
    private val bounds = BoundedLoadTileBounds()

    @Before
    fun capture() {
        BoundedLoad.resetReportsForTests()
        BoundedLoad.sink = { name, props -> events += name to props }
    }

    @After
    fun restore() {
        BoundedLoad.sink = realSink
        BoundedLoad.resetReportsForTests()
        BoundedLoad.stallOverrideMsForTests = null
    }

    @Test
    fun `a tile open has the twenty second stall window and reports every outcome`() {
        assertEquals(20_000L, bounds.stallMs)
        assertEquals("multiview_tile", LoadSurface.MULTIVIEW_TILE.key)
        assertTrue(LoadSurface.MULTIVIEW_TILE.reportsEveryOutcome)
    }

    @Test
    fun `the shared stall override applies to tiles too`() {
        BoundedLoad.stallOverrideMsForTests = 250
        assertEquals(250L, bounds.stallMs)
    }

    @Test
    fun `a loaded tile reports outcome duration source type and tile count`() {
        bounds.report(TileOutcome.Loaded(1_800), "stalker", 4)
        val (event, props) = events.single()
        assertEquals("iptv_load", event)
        assertEquals("multiview_tile", props["surface"])
        assertEquals("loaded", props["outcome"])
        assertEquals(1_800L, props["duration_ms"])
        assertEquals("stalker", props["source_type"])
        assertEquals(4, props["tile_count"])
        assertNull(props["failure"])
    }

    @Test
    fun `a stalled tile reports a timeout with the no-data failure`() {
        bounds.report(TileOutcome.TimedOut(20_000), "xtream", 2)
        val props = events.single().second
        assertEquals("timeout", props["outcome"])
        assertEquals("NoData", props["failure"])
    }

    @Test
    fun `a failed tile reports the typed failure name only`() {
        bounds.report(TileOutcome.Failed(TileFailure.ProviderRefused, 600), "m3u", 3)
        val props = events.single().second
        assertEquals("failed", props["outcome"])
        assertEquals("ProviderRefused", props["failure"])
        assertEquals("closed vocabulary", setOf("surface", "outcome", "duration_ms", "source_type", "tile_count", "failure"), props.keys)
    }

    @Test
    fun `failures past the per-surface cap are not capped for a surface that reports every outcome`() {
        repeat(25) { bounds.report(TileOutcome.Failed(TileFailure.Network, 10), "xtream", 2) }
        assertEquals(25, events.size)
        assertFalse(events.isEmpty())
    }
}
