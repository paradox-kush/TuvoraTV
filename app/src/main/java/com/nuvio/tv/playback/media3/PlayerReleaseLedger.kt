package com.nuvio.tv.playback.media3

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide count of player releases still in flight.
 *
 * Tile players release off the main thread (see [PlayerThread]), so "the next player opens after the old
 * decoders are free" is no longer implied by the main thread being blocked. A budget TV (2 GB Onn) cannot hold
 * three leftover codecs while a fullscreen player opens, so every new Media3 player first [awaitIdle]s here.
 *
 * The wait is bounded: a release that never ends must not freeze the next channel (a stuck player is the
 * session's abandon path, not this ledger's job). Pure; no Android imports.
 */
internal class PlayerReleaseLedger {
    private val count = MutableStateFlow(0)

    val pending: Int get() = count.value

    /** One release in flight. [end] is idempotent. */
    class Token internal constructor(private val onEnd: () -> Unit) {
        private val ended = AtomicBoolean(false)
        fun end() {
            if (ended.compareAndSet(false, true)) onEnd()
        }
    }

    fun begin(): Token {
        count.update { it + 1 }
        return Token { count.update { it - 1 } }
    }

    /** True when no release is pending (now or within [timeoutMs]); false when the deadline passed first. */
    suspend fun awaitIdle(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { count.first { it == 0 } } != null
}
