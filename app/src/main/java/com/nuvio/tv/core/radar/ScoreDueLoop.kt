package com.nuvio.tv.core.radar

/** The delay's re-render chain: wait for the next due snapshot, signal, repeat until nothing is pending. No network. */
object ScoreDueLoop {
    suspend fun run(nowMs: () -> Long, nextDueAtMs: (Long) -> Long?, wait: suspend (Long) -> Unit, onDue: suspend () -> Unit) {
        while (true) {
            val now = nowMs()
            val due = nextDueAtMs(now) ?: return
            wait((due - now).coerceAtLeast(0L) + 50L)
            onDue()
        }
    }
}
