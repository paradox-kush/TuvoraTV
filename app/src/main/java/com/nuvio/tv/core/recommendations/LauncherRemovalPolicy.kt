package com.nuvio.tv.core.recommendations

/**
 * "I removed it from my TV home screen, so it stays gone."
 *
 * Owner decision 2026-11-24: a removed item comes back only after it is played again in the app.
 * A different episode of the same show is a different item (`wn_<id>_sXeY`) and may publish.
 * Removing from the launcher never touches the in-app Continue Watching list.
 *
 * Removals are tracked per item, shared by both launcher surfaces: the Watch Next row id is
 * `wn_<key>` and the preview channel id is `<key>`, so both normalise to `<key>`.
 *
 * Pure: no Android types.
 */
object LauncherRemovalPolicy {

    private const val WATCH_NEXT_PREFIX = "wn_"
    private const val MAX_REMEMBERED = 500
    private const val SEPARATOR = '|'

    /** Both surfaces' ids map to the same key. */
    fun normalize(internalId: String): String = internalId.removePrefix(WATCH_NEXT_PREFIX)

    /**
     * @param removedAtMs when the viewer removed this item from the launcher, or null if they did not.
     * @param lastEngagedAtMs when the viewer last watched it in the app.
     */
    fun mayPublish(internalId: String, removedAtMs: Long?, lastEngagedAtMs: Long): Boolean {
        if (internalId.isBlank()) return false
        if (removedAtMs == null) return true
        return lastEngagedAtMs > removedAtMs
    }

    /** [mayPublish] against the stored removals. */
    fun mayPublish(internalId: String, removed: Map<String, Long>, lastEngagedAtMs: Long): Boolean =
        mayPublish(internalId, removed[normalize(internalId)], lastEngagedAtMs)

    fun recordRemoval(removed: Map<String, Long>, internalId: String, atMs: Long): Map<String, Long> {
        val key = normalize(internalId)
        if (key.isBlank()) return removed
        val updated = removed + (key to maxOf(atMs, removed[key] ?: Long.MIN_VALUE))
        if (updated.size <= MAX_REMEMBERED) return updated
        // Forget the oldest removals first; they have long since left every days-cap window.
        return updated.entries.sortedByDescending { it.value }.take(MAX_REMEMBERED).associate { it.key to it.value }
    }

    /** `<removedAtMs>|<key>` per entry: the timestamp first, so keys may contain any character. */
    fun encode(removed: Map<String, Long>): Set<String> =
        removed.mapTo(LinkedHashSet()) { (key, at) -> "$at$SEPARATOR$key" }

    fun decode(raw: Set<String>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (entry in raw) {
            val split = entry.indexOf(SEPARATOR)
            if (split <= 0) continue
            val at = entry.substring(0, split).toLongOrNull() ?: continue
            val key = entry.substring(split + 1)
            if (key.isBlank()) continue
            out[key] = maxOf(at, out[key] ?: Long.MIN_VALUE)
        }
        return out
    }
}
