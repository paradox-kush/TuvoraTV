package com.nuvio.tv.playback.mpv

import com.nuvio.tv.core.contracts.LivePauseWindow

/** One `demuxer-cache-state/seekable-ranges` entry, in seconds on mpv's timeline. */
data class MpvCacheRange(val startSec: Double, val endSec: Double)

/**
 * The time-shift window of a live mpv stream, from its demuxer cache. Pure: the backend feeds it the
 * observed `demuxer-cache-state` and `time-pos` (shadow state, never a call into mpv on Main).
 *
 * The window is the seekable range the play position is in (mpv lists them oldest first, the live edge
 * last); a position in no range (a gap, or just past the edge) uses the last one, clamped into it.
 */
object MpvSeekWindow {
    /** mpv reports time-pos a few ms before a freshly started range; that is still this range. */
    private const val TOLERANCE_SEC = 0.5

    fun fromCacheState(ranges: List<MpvCacheRange>, timePosSec: Double?): LivePauseWindow? {
        if (timePosSec == null || !timePosSec.isFinite()) return null
        val sane = ranges.filter { it.startSec.isFinite() && it.endSec.isFinite() && it.endSec > it.startSec }
        if (sane.isEmpty()) return null
        val range = sane.firstOrNull {
            timePosSec >= it.startSec - TOLERANCE_SEC && timePosSec <= it.endSec + TOLERANCE_SEC
        } ?: sane.last()
        val start = (range.startSec * 1000).toLong()
        val end = (range.endSec * 1000).toLong()
        return LivePauseWindow(start, end, (timePosSec * 1000).toLong().coerceIn(start, end))
    }
}
