package com.nuvio.tv.core.radar

/**
 * Remembers recent snapshots per key so the UI can show the value as it was `delayMs` ago. Pure
 * (time is passed in), thread-safe, bounded. Recording happens where a fetch result lands.
 */
class DelayedValueBuffer<K, V>(private val maxPerKey: Int = 8) {
    private class Snap<V>(val atMs: Long, val value: V)

    private val map = HashMap<K, ArrayList<Snap<V>>>()

    /** Stores a snapshot only when it differs from the key's latest. */
    @Synchronized
    fun record(key: K, value: V, atMs: Long) {
        val list = map.getOrPut(key) { ArrayList() }
        if (list.isNotEmpty() && list.last().value == value) return
        list.add(Snap(atMs, value))
        // Keep the newest snapshot old enough to serve the longest delay, plus everything newer.
        val cutoff = atMs - MAX_DELAY_MS
        val keepFrom = list.indexOfLast { it.atMs <= cutoff }
        if (keepFrom > 0) repeat(keepFrom) { list.removeAt(0) }
        while (list.size > maxPerKey) list.removeAt(0)
    }

    /** Newest snapshot with `atMs <= nowMs - delayMs`; null if none is old enough. delayMs == 0 -> newest. */
    @Synchronized
    fun valueAt(key: K, nowMs: Long, delayMs: Long): V? {
        val list = map[key] ?: return null
        if (delayMs <= 0L) return list.lastOrNull()?.value
        return list.lastOrNull { it.atMs <= nowMs - delayMs }?.value
    }

    @Synchronized
    fun hasKey(key: K): Boolean = map[key]?.isNotEmpty() == true

    @Synchronized
    fun snapshotCount(key: K): Int = map[key]?.size ?: 0

    /** When the earliest still-pending snapshot (any key) becomes due; null when nothing is pending. */
    @Synchronized
    fun nextChangeAtMs(nowMs: Long, delayMs: Long): Long? {
        if (delayMs <= 0L) return null
        var best: Long? = null
        for (list in map.values) for (s in list) {
            val due = s.atMs + delayMs
            if (due > nowMs && (best == null || due < best)) best = due
        }
        return best
    }

    @Synchronized
    fun clear() = map.clear()

    private companion object { const val MAX_DELAY_MS = 60_000L }
}
