package com.nuvio.tv.playback.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * How many times one app process may abandon a player whose shutdown never finished.
 *
 * A player that cannot be proven shut down used to poison its engine until the screen closed. The
 * owner accepted one deliberate exception per process: drop the stuck player and build a fresh one,
 * so the next channel plays. A second stuck shutdown is fatal again (the user is told to restart),
 * because repeated abandons would leak a native player each time.
 *
 * Pure and thread-safe; no Android imports. One instance per process, shared by every session.
 */
class AbandonedPlayerBudget(private val maxAbandons: Int = DEFAULT_MAX_ABANDONS) {
    init {
        require(maxAbandons >= 0) { "maxAbandons must not be negative" }
    }

    private val consumed = AtomicInteger(0)

    /** True exactly [maxAbandons] times over the life of this instance, false afterwards. */
    fun tryConsume(): Boolean {
        while (true) {
            val current = consumed.get()
            if (current >= maxAbandons) return false
            if (consumed.compareAndSet(current, current + 1)) return true
        }
    }

    /** Abandons granted so far. */
    val used: Int get() = consumed.get()

    companion object {
        const val DEFAULT_MAX_ABANDONS = 1
    }
}
