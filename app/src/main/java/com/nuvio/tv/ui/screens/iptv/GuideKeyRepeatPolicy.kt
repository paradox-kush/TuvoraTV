package com.nuvio.tv.ui.screens.iptv

/**
 * B56: the Live TV guide lists had no D-pad repeat gate, so on a very large category a held key
 * queued focus moves faster than the screen could compose them; focus then kept travelling after
 * release ("dead-loops") and never settled on a channel. Vertical repeats are gated and stale
 * (queued too long) repeats are dropped, which bounds the backlog. Horizontal repeats are
 * untouched: holding LEFT/RIGHT inside the timeline is intentional travel.
 *
 * One instance per list; not thread-safe (key events arrive on the main thread).
 */
class GuideKeyRepeatPolicy {
    enum class Key { Vertical, Horizontal }
    enum class Decision { Accept, Drop, Pass }

    private var lastAcceptedMs: Long = Long.MIN_VALUE

    fun decide(key: Key, repeatCount: Int, eventTimeMs: Long, nowMs: Long): Decision {
        if (key == Key.Horizontal) return Decision.Pass
        if (repeatCount == 0) {
            lastAcceptedMs = nowMs
            return Decision.Accept
        }
        if (nowMs - eventTimeMs > MAX_STALE_MS) return Decision.Drop
        if (lastAcceptedMs != Long.MIN_VALUE && nowMs - lastAcceptedMs < VERTICAL_GATE_MS) return Decision.Drop
        lastAcceptedMs = nowMs
        return Decision.Accept
    }

    companion object {
        /** Same gate the other big TV lists use (dpadRepeatThrottle). */
        const val VERTICAL_GATE_MS = 112L

        /** A repeat older than this when it reaches us is backlog, not intent. */
        const val MAX_STALE_MS = 150L
    }
}
