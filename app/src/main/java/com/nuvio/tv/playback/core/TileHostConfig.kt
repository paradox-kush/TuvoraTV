package com.nuvio.tv.playback.core

/**
 * Multiview tile mode for one clean live host. A tile is one of several live players on screen at
 * once, so it must stay out of everything a single full-screen player owns: audio focus, the
 * system media session, the display mode, tunnelled/passthrough/offloaded audio, and the mpv
 * engine. [bufferBytes] is this tile's share of the download-ahead budget
 * ([com.nuvio.tv.core.multiview.MultiviewBufferBudget.bytesPerPane]).
 *
 * Pure data: what each field turns off is decided in one place, `TileHostComposition`.
 */
data class TileHostConfig(
    val bufferBytes: Int,
    /** Adaptive streams are capped here; a tile never needs more than a quarter-screen picture. */
    val maxVideo: VideoDimensions = MAX_VIDEO,
) {
    init {
        require(bufferBytes > 0) { "Tile buffer must be positive" }
    }

    companion object {
        val MAX_VIDEO = VideoDimensions(1280, 720)
    }
}
