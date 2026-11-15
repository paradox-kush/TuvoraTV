package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.multiview.TileLoadBounds
import com.nuvio.tv.core.multiview.TileOutcome

/**
 * The fork-side backing of [TileLoadBounds]: a tile's stall window and outcome report go through
 * [BoundedLoad] on [LoadSurface.MULTIVIEW_TILE], so they share the stall override, the `iptv_load`
 * event and the closed vocabulary of every other IPTV / Live TV load. The multiview packages stay on
 * the neutral port and never name this class (the firewall).
 *
 * Properties: `surface=multiview_tile`, `outcome` (loaded | failed | timeout), `duration_ms`,
 * `source_type`, `tile_count`, and `failure` (a [com.nuvio.tv.core.multiview.TileFailure] name) on a
 * failure. Never a host, URL, account, channel name or engine message.
 */
internal class BoundedLoadTileBounds : TileLoadBounds {
    override val stallMs: Long get() = BoundedLoad.stallMs(LoadSurface.MULTIVIEW_TILE)

    override fun report(outcome: TileOutcome, sourceType: String, tileCount: Int) {
        val extra = buildMap<String, Any> {
            put("source_type", sourceType)
            put("tile_count", tileCount)
            when (outcome) {
                is TileOutcome.Failed -> put("failure", outcome.failure.name)
                is TileOutcome.TimedOut -> put("failure", com.nuvio.tv.core.multiview.TileFailure.NoData.name)
                is TileOutcome.Loaded -> Unit
            }
        }
        when (outcome) {
            is TileOutcome.Loaded -> BoundedLoad.reportLoaded(LoadSurface.MULTIVIEW_TILE, outcome.durationMs, extra)
            is TileOutcome.Failed -> BoundedLoad.report(LoadSurface.MULTIVIEW_TILE, timedOut = false, outcome.durationMs, extra)
            is TileOutcome.TimedOut -> BoundedLoad.report(LoadSurface.MULTIVIEW_TILE, timedOut = true, outcome.durationMs, extra)
        }
    }
}
