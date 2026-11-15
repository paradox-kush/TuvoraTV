package com.nuvio.tv.core.multiview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tiles open one at a time so a box does not start four manifests, four decoders and four provider
 * sessions in the same instant: the next tile starts when the previous one shows its first frame (or
 * ends in a failure, or is released), or after [MAX_WAIT_MS], whichever comes first.
 */
internal object TileOpenStaggerPolicy {
    const val MAX_WAIT_MS = 2_000L

    /** How much longer the next tile waits for [previous]; 0 = start now. */
    fun remainingWaitMs(previousSettled: Boolean, previousStartedAtMs: Long, nowMs: Long): Long {
        if (previousSettled) return 0L
        return (MAX_WAIT_MS - (nowMs - previousStartedAtMs)).coerceIn(0L, MAX_WAIT_MS)
    }
}

/**
 * First-come order for tile opens. [awaitTurn] returns a [Ticket] once the previous ticket has settled
 * or [TileOpenStaggerPolicy.MAX_WAIT_MS] has passed; the opener settles its ticket with [Ticket.settle]
 * on the first frame, on failure and on release (settling twice is harmless).
 */
internal class TileOpenGate(
    private val nowMs: () -> Long,
    private val maxWaitMs: Long = TileOpenStaggerPolicy.MAX_WAIT_MS,
) {
    class Ticket internal constructor(internal val startedAtMs: Long) {
        private val settled = CompletableDeferred<Unit>()
        internal val isSettled: Boolean get() = settled.isCompleted
        fun settle() {
            settled.complete(Unit)
        }

        internal suspend fun awaitSettled() = settled.await()
    }

    private val queue = Mutex()
    private var previous: Ticket? = null

    suspend fun awaitTurn(): Ticket = queue.withLock {
        val before = previous
        if (before != null) {
            val wait = TileOpenStaggerPolicy.remainingWaitMs(before.isSettled, before.startedAtMs, nowMs())
                .coerceAtMost(maxWaitMs)
            if (wait > 0L) withTimeoutOrNull(wait) { before.awaitSettled() }
        }
        Ticket(nowMs()).also { previous = it }
    }
}
