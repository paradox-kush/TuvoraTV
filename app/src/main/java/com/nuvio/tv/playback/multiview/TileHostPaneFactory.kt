package com.nuvio.tv.playback.multiview

import com.nuvio.tv.core.multiview.MultiviewPanePlayer
import com.nuvio.tv.core.multiview.TileLoadBounds
import com.nuvio.tv.core.multiview.TileOpenGate
import com.nuvio.tv.playback.core.DecodeHealthTileLink
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.TileHostConfig
import com.nuvio.tv.playback.host.AndroidCleanLiveHostInput
import com.nuvio.tv.playback.host.CleanLiveHostFactory
import com.nuvio.tv.playback.live.LiveChannelSelectionPort
import kotlinx.coroutines.CoroutineScope

/**
 * Builds the tile players for one multiview screen: it is the `createPlayer` argument of the session
 * core. [hostInput] supplies the slot's surface owner, activity and lifecycle (the screen owns the
 * views); this factory adds the tile flag, so every host it creates is a tile-mode host.
 *
 * One [TileOpenGate] per screen staggers the opens.
 */
internal class TileHostPaneFactory(
    private val scope: CoroutineScope,
    private val profileId: PlaybackProfileId,
    private val hostFactory: CleanLiveHostFactory,
    private val channelSelection: LiveChannelSelectionPort,
    private val bounds: TileLoadBounds,
    private val gate: TileOpenGate,
    private val tileCount: () -> Int,
    private val nowMs: () -> Long,
    private val hostInput: (slot: Int, tile: TileHostConfig) -> AndroidCleanLiveHostInput,
) {
    /** One decode-health cap per multiview screen: four tiles on a bad provider report at most 5 events in all. */
    private val decodeHealth = DecodeHealthTileLink(tileCount = tileCount)

    fun create(slot: Int, bufferBytes: Int, changed: () -> Unit): MultiviewPanePlayer<TileChannel> {
        val tile = TileHostConfig(bufferBytes, decodeHealth = decodeHealth)
        return TileHostPanePlayer(
            scope = scope,
            profileId = profileId,
            channelSelection = channelSelection,
            createHost = { hostFactory.create(hostInput(slot, tile)) },
            gate = gate,
            bounds = bounds,
            tileCount = tileCount,
            nowMs = nowMs,
            changed = changed,
        )
    }
}
