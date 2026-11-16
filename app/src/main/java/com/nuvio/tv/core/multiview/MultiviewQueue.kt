// Ported from homelessbrian/NuvioPlusIPTV @ a86756891 (GPL-3.0), MultiviewQueue.kt (without the LiveTvNotice call).
package com.nuvio.tv.core.multiview

sealed interface QueueResult {
    /**
     * Whether the notice offers "Open now" (and so owns D-pad focus). A duplicate or a full queue must not leave
     * the viewer at a dead end; a plain Added is passive, the channel row it came from keeps focus.
     */
    val offersOpenNow: Boolean

    data class Added(val count: Int) : QueueResult { override val offersOpenNow get() = false }
    data class AlreadyQueued(val count: Int) : QueueResult { override val offersOpenNow get() = true }
    data object Full : QueueResult { override val offersOpenNow get() = true }
}

/**
 * Channels set aside with "Add to Multiview". Held in memory as channel keys only (no stream links);
 * each is re-checked against what the active profile may watch when multiview takes them.
 * The caller shows the text for each [QueueResult].
 */
class MultiviewQueue(private val max: Int = MAX) {
    private val keys = ArrayList<String>()

    @Synchronized
    fun add(key: String): QueueResult {
        if (key in keys) return QueueResult.AlreadyQueued(keys.size)
        if (keys.size >= max) return QueueResult.Full
        keys.add(key)
        return QueueResult.Added(keys.size)
    }

    /** Hands over the set-aside channels and empties the list. */
    @Synchronized
    fun take(): List<String> = keys.toList().also { keys.clear() }

    companion object {
        const val MAX = MultiviewLayoutPolicy.MAX_TILES
    }
}
