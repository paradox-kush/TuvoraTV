package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Fork (C1, ANR rule): every libmpv property read takes mpv's core lock and can block for seconds
 * while the core is busy (demuxer reconnect, decoder teardown), so the player never reads libmpv on
 * Main. Reads run on ONE serial background lane and the caller (on Main) gets the result back.
 * Writes from [NuvioMpvSurfaceView] (pause, seek, track select) still run on Main.
 */
internal object MpvReads {
    @OptIn(ExperimentalCoroutinesApi::class)
    val context: CoroutineContext = Dispatchers.IO.limitedParallelism(1)

    suspend fun <T> read(block: () -> T): T = withContext(context) { block() }
}

/** Everything the progress tick and the startup watchdog need from libmpv, read in one go. */
internal data class MpvTickSample(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val subtitleText: String? = null,
    val pausedForCache: Boolean = false,
    val coreIdle: Boolean = false,
    val idleActive: Boolean = false,
    val fromRequestedMedia: Boolean = false,
    val eofReached: Boolean = false,
    val liveStream: Boolean = false,
    val demuxerCacheSec: Double = 0.0,
    val videoTrackSelected: Boolean = false,
    val isHdrVideo: Boolean? = null,
)
