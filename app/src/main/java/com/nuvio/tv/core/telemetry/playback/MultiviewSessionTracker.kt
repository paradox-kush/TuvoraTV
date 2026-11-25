package com.nuvio.tv.core.telemetry.playback

/** What one multiview use looked like, sent once at close as `multiview_session`. */
data class MultiviewSessionSummary(
    val tilesMax: Int,
    val durationSeconds: Long,
    val tileFailures: Int,
    /** Peak tiles playing at once; each is one hardware decoder instance on TV (tile mode is Media3 only). */
    val hwDecodersInUse: Int,
    val endReason: MultiviewEndReason,
) {
    fun toProperties(pssMbPeak: Int): Map<String, Any> = linkedMapOf(
        "schema_version" to 1,
        "tiles_max" to tilesMax,
        "duration_s_bucket" to PlaybackTelemetryBuckets.durationSeconds(durationSeconds),
        "tile_failures" to tileFailures,
        "hw_decoders_in_use" to hwDecodersInUse,
        "pss_mb_peak" to pssMbPeak,
        "end_reason" to endReason.wire,
    )
}

/**
 * Pure state of ONE multiview use (the screen is a singleton in practice). Fed by the multiview screen's
 * state; main thread in production but kept lock-free-simple and `@Synchronized` so a memory sample from the
 * telemetry thread can update the PSS peak.
 */
class MultiviewSessionTracker(private val nowMs: () -> Long) {
    private var openedAtMs: Long? = null
    private var tilesMax = 0
    private var playingPeak = 0
    private var failures = 0
    private var failedNow: Set<Int> = emptySet()
    private var lastTiles = 0
    private var lastPlaying = 0
    private var backgrounded = false
    private var trimmed = false
    private var pssPeakMb = 0

    @Synchronized
    fun isOpen(): Boolean = openedAtMs != null

    /** True when this call opened the session (a second call while open is ignored). */
    @Synchronized
    fun open(): Boolean {
        if (openedAtMs != null) return false
        openedAtMs = nowMs()
        tilesMax = 0; playingPeak = 0; failures = 0; failedNow = emptySet()
        lastTiles = 0; lastPlaying = 0; backgrounded = false; trimmed = false; pssPeakMb = 0
        return true
    }

    /** The grid changed. Returns how many tiles newly entered the failed state (each one is a `tile_failed`). */
    @Synchronized
    fun onTiles(tileCount: Int, playing: Int, failedTileIds: Set<Int>): Int {
        if (openedAtMs == null) return 0
        lastTiles = tileCount
        lastPlaying = playing
        if (tileCount > tilesMax) tilesMax = tileCount
        if (playing > playingPeak) playingPeak = playing
        val fresh = (failedTileIds - failedNow).size
        failedNow = failedTileIds
        failures += fresh
        return fresh
    }

    @Synchronized fun onBackgrounded() { if (openedAtMs != null) backgrounded = true }
    @Synchronized fun onForegrounded() { backgrounded = false }
    @Synchronized fun onMemoryTrim() { if (openedAtMs != null) trimmed = true }

    @Synchronized
    fun onMemorySample(pssMb: Int) {
        if (openedAtMs != null && pssMb > pssPeakMb) pssPeakMb = pssMb
    }

    @Synchronized
    fun pssPeakMb(): Int = pssPeakMb

    /** Ends the session; null when none was open. Idempotent. */
    @Synchronized
    fun close(): MultiviewSessionSummary? {
        val opened = openedAtMs ?: return null
        openedAtMs = null
        val reason = when {
            trimmed -> MultiviewEndReason.MEMORY
            backgrounded -> MultiviewEndReason.APP_BACKGROUND
            tilesMax == 0 -> MultiviewEndReason.OTHER
            failures > 0 && lastTiles > 0 && lastPlaying == 0 -> MultiviewEndReason.ERROR
            else -> MultiviewEndReason.USER
        }
        return MultiviewSessionSummary(
            tilesMax = tilesMax,
            durationSeconds = ((nowMs() - opened).coerceAtLeast(0L)) / 1000L,
            tileFailures = failures,
            hwDecodersInUse = playingPeak,
            endReason = reason,
        )
    }
}
