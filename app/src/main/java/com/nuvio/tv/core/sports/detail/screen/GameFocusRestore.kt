package com.nuvio.tv.core.sports.detail.screen

/**
 * Remembers which Sports card opened a game so Back returns focus to THAT card, not the first one. Armed when the
 * detail opens, taken once by the card when it composes again; stale after [TTL_MS] so a card that scrolls into view
 * much later can never steal focus.
 */
object GameFocusRestore {
    const val TTL_MS = 120_000L

    private var id: String? = null
    private var armedAt = 0L

    @Synchronized
    fun arm(gameId: String, nowMs: Long) { id = gameId; armedAt = nowMs }

    /** True exactly once for the armed game while it is fresh. */
    @Synchronized
    fun take(gameId: String?, nowMs: Long): Boolean {
        if (gameId == null || id != gameId) return false
        val fresh = nowMs - armedAt <= TTL_MS
        id = null
        return fresh
    }

    @Synchronized
    fun peek(gameId: String?, nowMs: Long): Boolean = gameId != null && id == gameId && nowMs - armedAt <= TTL_MS
}
