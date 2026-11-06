package com.nuvio.tv.core.iptv

/** Which guide row a replay's playhead is in (or about to enter) - pure, so it tests without the guide store. */
object CatchUpNextProgramme {

    data class Row(val title: String, val startMs: Long, val endMs: Long)

    /** The row covering [atMs], else the first one that starts after it; degenerate rows never count. */
    fun pick(rows: List<Row>, atMs: Long): Row? {
        val real = rows.filter { it.startMs > 0L && it.endMs > it.startMs }
        return real.firstOrNull { atMs >= it.startMs && atMs < it.endMs }
            ?: real.filter { it.startMs > atMs }.minByOrNull { it.startMs }
    }
}
